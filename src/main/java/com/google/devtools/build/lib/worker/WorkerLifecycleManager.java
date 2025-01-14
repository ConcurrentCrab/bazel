// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.worker.WorkerProcessStatus.Status;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.EvictionConfig;
import org.apache.commons.pool2.impl.EvictionPolicy;

/**
 * This class kills idle persistent workers at intervals, if the total worker resource usage is
 * above a specified limit. Must be used as singleton.
 */
final class WorkerLifecycleManager extends Thread {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private boolean isWorking = false;
  private boolean emptyEvictionWasLogged = false;
  private final WorkerPool workerPool;
  private final WorkerOptions options;

  private Reporter reporter;
  private EventBus eventBus;

  public WorkerLifecycleManager(WorkerPool workerPool, WorkerOptions options) {
    this.workerPool = workerPool;
    this.options = options;
  }

  public void setReporter(Reporter reporter) {
    this.reporter = reporter;
  }

  public void setEventBus(EventBus eventBus) {
    this.eventBus = eventBus;
  }

  @Override
  public void run() {
    if (options.totalWorkerMemoryLimitMb == 0 && options.workerMemoryLimitMb == 0) {
      return;
    }

    String msg =
        String.format(
            "Worker Lifecycle Manager starts work with (total limit: %d MB, limit: %d MB,"
                + " shrinking: %s)",
            options.totalWorkerMemoryLimitMb,
            options.workerMemoryLimitMb,
            options.shrinkWorkerPool ? "enabled" : "disabled");
    logger.atInfo().log("%s", msg);
    if (options.workerVerbose && this.reporter != null) {
      reporter.handle(Event.info(msg));
    }

    isWorking = true;

    // This loop works until method stopProcessing() called by WorkerModule.
    while (isWorking) {
      try {
        Thread.sleep(options.workerMetricsPollInterval.toMillis());
      } catch (InterruptedException e) {
        logger.atInfo().withCause(e).log("received interrupt in worker life cycle manager");
        break;
      }

      ImmutableList<WorkerProcessMetrics> workerProcessMetrics =
          WorkerProcessMetricsCollector.instance().getLiveWorkerProcessMetrics();

      if (options.totalWorkerMemoryLimitMb > 0) {
        try {
          evictWorkers(workerProcessMetrics);
        } catch (InterruptedException e) {
          logger.atInfo().withCause(e).log("received interrupt in worker life cycle manager");
          break;
        }
      }

      if (options.workerMemoryLimitMb > 0) {
        killLargeWorkers(workerProcessMetrics, options.workerMemoryLimitMb);
      }
    }

    isWorking = false;
  }

  void stopProcessing() {
    isWorking = false;
  }

  /** Kills any worker that uses more than {@code limitMb} MB of memory. */
  void killLargeWorkers(ImmutableList<WorkerProcessMetrics> workerProcessMetrics, int limitMb) {
    ImmutableList<WorkerProcessMetrics> large =
        workerProcessMetrics.stream()
            .filter(m -> m.getUsedMemoryInKb() / 1000 > limitMb)
            .collect(toImmutableList());

    for (WorkerProcessMetrics l : large) {
      String msg;

      ImmutableList<Integer> workerIds = l.getWorkerIds();
      Optional<ProcessHandle> ph = ProcessHandle.of(l.getProcessId());
      if (ph.isPresent()) {
        msg =
            String.format(
                "Killing %s worker %s (pid %d) because it is using more memory than the limit (%dMB"
                    + " > %dMB)",
                l.getMnemonic(),
                workerIds.size() == 1 ? workerIds.get(0) : workerIds,
                l.getProcessId(),
                l.getUsedMemoryInKb() / 1000,
                limitMb);
        logger.atInfo().log("%s", msg);
        // TODO(b/310640400): Converge APIs in killing workers, rather than killing via the process
        //  handle here (resulting in errors in execution), perhaps we want to wait till the worker
        //  is returned before killing it.
        ph.get().destroyForcibly();
        boolean wasKilled =
            l.getStatus()
                .maybeUpdateStatus(WorkerProcessStatus.Status.KILLED_DUE_TO_MEMORY_PRESSURE);
        // We want to always report this as this is a potential source of build failure.
        if (this.reporter != null) {
          reporter.handle(Event.warn(msg));
        }
        if (eventBus != null && wasKilled) {
          l.getWorkerIds()
              .forEach(
                  workerId ->
                      eventBus.post(
                          new WorkerEvictedEvent(workerId, l.getWorkerKeyHash(), l.getMnemonic())));
        }
      }
    }
  }

  @VisibleForTesting // productionVisibility = Visibility.PRIVATE
  void evictWorkers(ImmutableList<WorkerProcessMetrics> workerProcessMetrics)
      throws InterruptedException {

    if (options.totalWorkerMemoryLimitMb == 0) {
      return;
    }

    int workerMemoryUsage =
        workerProcessMetrics.stream().mapToInt(metric -> metric.getUsedMemoryInKb() / 1000).sum();

    // TODO: Remove after b/274608075 is fixed.
    if (!workerProcessMetrics.isEmpty()) {
      logger.atInfo().atMostEvery(1, TimeUnit.MINUTES).log(
          "total worker memory %dMB while limit is %dMB - details: %s",
          workerMemoryUsage,
          options.totalWorkerMemoryLimitMb,
          workerProcessMetrics.stream()
              .map(
                  metric ->
                      metric.getWorkerIds()
                          + " "
                          + metric.getMnemonic()
                          + " "
                          + metric.getUsedMemoryInKb()
                          + "kB")
              .collect(Collectors.joining(", ")));
    }

    if (workerMemoryUsage <= options.totalWorkerMemoryLimitMb) {
      return;
    }

    ImmutableSet<WorkerProcessMetrics> candidates =
        collectEvictionCandidates(
            workerProcessMetrics, options.totalWorkerMemoryLimitMb, workerMemoryUsage);

    if (!candidates.isEmpty() || !emptyEvictionWasLogged) {
      String msg;
      if (candidates.isEmpty()) {
        msg =
            String.format(
                "Could not find any worker eviction candidates. Worker memory usage: %d MB, Memory"
                    + " limit: %d MB",
                workerMemoryUsage, options.totalWorkerMemoryLimitMb);
      } else {
        ImmutableSet<Integer> workerIdsToEvict =
            candidates.stream().flatMap(m -> m.getWorkerIds().stream()).collect(toImmutableSet());
        msg =
            String.format(
                "Attempting eviction of %d workers with ids: %s",
                workerIdsToEvict.size(), workerIdsToEvict);
      }

      logger.atInfo().log("%s", msg);
      if (options.workerVerbose && this.reporter != null) {
        reporter.handle(Event.info(msg));
      }
    }

    ImmutableSet<Integer> evictedWorkers = evictCandidates(workerPool, candidates);

    if (!evictedWorkers.isEmpty() || !emptyEvictionWasLogged) {
      String msg =
          String.format(
              "Total evicted idle workers %d. With ids: %s", evictedWorkers.size(), evictedWorkers);
      logger.atInfo().log("%s", msg);
      if (options.workerVerbose && this.reporter != null) {
        reporter.handle(Event.info(msg));
      }

      emptyEvictionWasLogged = candidates.isEmpty();
    }

    if (eventBus != null) {
      for (WorkerProcessMetrics metric : workerProcessMetrics) {
        for (Integer workerId : metric.getWorkerIds()) {
          if (evictedWorkers.contains(workerId)) {
            eventBus.post(
                new WorkerEvictedEvent(workerId, metric.getWorkerKeyHash(), metric.getMnemonic()));
          }
        }
      }
    }

    // TODO(b/300067854): Shrinking of the worker pool happens on worker keys that are active at the
    //  time of polling, but doesn't shrink the pools of idle workers. We might be wrongly
    //  penalizing lower memory usage workers (but more active) by shrinking their pool sizes
    //  instead of higher memory usage workers (but less active) and are killed directly with
    //  {@code #evictCandidates()} (where shrinking doesn't happen).
    if (options.shrinkWorkerPool) {
      List<WorkerProcessMetrics> notEvictedWorkerProcessMetrics =
          workerProcessMetrics.stream()
              .filter(metric -> !evictedWorkers.containsAll(metric.getWorkerIds()))
              .collect(Collectors.toList());

      // TODO(b/300067854): There is a precision error here when converting Kb to Mb and then
      //  casting to int (e.g. [1001 Kb ... 1999 Kb] will pass a limit check of 1 Mb below).
      int notEvictedWorkerMemoryUsage =
          notEvictedWorkerProcessMetrics.stream()
              .mapToInt(metric -> metric.getUsedMemoryInKb() / 1000)
              .sum();

      if (notEvictedWorkerMemoryUsage <= options.totalWorkerMemoryLimitMb) {
        return;
      }

      postponeInvalidation(notEvictedWorkerProcessMetrics, notEvictedWorkerMemoryUsage);
    }
  }

  private void postponeInvalidation(
      List<WorkerProcessMetrics> workerProcessMetrics, int notEvictedWorkerMemoryUsage) {
    ImmutableSet<WorkerProcessMetrics> potentialCandidates =
        getCandidates(
            workerProcessMetrics, options.totalWorkerMemoryLimitMb, notEvictedWorkerMemoryUsage);

    if (!potentialCandidates.isEmpty()) {
      String msg =
          String.format(
              "Postponing eviction of worker ids: %s",
              potentialCandidates.stream()
                  .flatMap(m -> m.getWorkerIds().stream())
                  .collect(toImmutableList()));
      logger.atInfo().log("%s", msg);
      if (options.workerVerbose && this.reporter != null) {
        reporter.handle(Event.info(msg));
      }
      potentialCandidates.forEach(
          m -> m.getStatus().maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE));
    }
  }

  /**
   * Applies eviction police for candidates. Returns the worker ids of evicted workers. We don't
   * guarantee that every candidate is going to be evicted. Returns worker ids of evicted workers.
   */
  private static ImmutableSet<Integer> evictCandidates(
      WorkerPool pool, ImmutableSet<WorkerProcessMetrics> candidates) throws InterruptedException {
    CandidateEvictionPolicy policy = new CandidateEvictionPolicy(candidates);
    pool.evictWithPolicy(policy);
    return policy.getEvictedWorkers();
  }

  /** Collects worker candidates to evict. Chooses workers with the largest memory consumption. */
  @SuppressWarnings("JdkCollectors")
  ImmutableSet<WorkerProcessMetrics> collectEvictionCandidates(
      ImmutableList<WorkerProcessMetrics> workerProcessMetrics,
      int memoryLimitMb,
      int workerMemoryUsageMb)
      throws InterruptedException {
    // TODO(b/300067854): Consider rethinking the strategy here. The current logic kills idle
    //  workers that have lower memory usage if the other higher memory usage workers are active
    //  (where killing them would have brought the memory usage under the limit). This means we
    //  could be killing memory compliant and performant workers unnecessarily; i.e. this strategy
    //  maximizes responsiveness towards being compliant to the memory limit with no guarantees of
    //  making it immediately compliant. Since we can't guarantee immediate compliance, tradeoff
    //  some of this responsiveness by just killing or marking workers as killed in descending
    //  memory usage and waiting for the active workers to be returned later (where they are then
    //  killed).
    Set<Integer> idleWorkers = getIdleWorkers();

    List<WorkerProcessMetrics> idleWorkerProcessMetrics =
        workerProcessMetrics.stream()
            .filter(metric -> metric.getWorkerIds().stream().anyMatch(idleWorkers::contains))
            .collect(Collectors.toList());

    return getCandidates(idleWorkerProcessMetrics, memoryLimitMb, workerMemoryUsageMb);
  }

  /**
   * Chooses the WorkerProcessMetrics of workers with the most usage of memory. Selects workers
   * until total memory usage is less than memoryLimitMb.
   */
  private static ImmutableSet<WorkerProcessMetrics> getCandidates(
      List<WorkerProcessMetrics> workerProcessMetrics, int memoryLimitMb, int usedMemoryMb) {

    workerProcessMetrics.sort(new MemoryComparator());
    ImmutableSet.Builder<WorkerProcessMetrics> candidates = ImmutableSet.builder();
    int freeMemoryMb = 0;
    for (WorkerProcessMetrics metric : workerProcessMetrics) {
      candidates.add(metric);
      freeMemoryMb += metric.getUsedMemoryInKb() / 1000;

      if (usedMemoryMb - freeMemoryMb <= memoryLimitMb) {
        break;
      }
    }

    return candidates.build();
  }

  /**
   * Calls workerPool.evict() to collect information, but doesn't kill any workers during this
   * process.
   */
  private Set<Integer> getIdleWorkers() throws InterruptedException {
    InfoEvictionPolicy infoEvictionPolicy = new InfoEvictionPolicy();
    workerPool.evictWithPolicy(infoEvictionPolicy);
    return infoEvictionPolicy.getWorkerIds();
  }

  /**
   * Eviction policy for WorkerPool. Only collects ids of idle workers, doesn't evict any of them.
   */
  private static class InfoEvictionPolicy implements EvictionPolicy<Worker> {
    private final Set<Integer> workerIds = new HashSet<>();

    public InfoEvictionPolicy() {}

    @Override
    public boolean evict(EvictionConfig config, PooledObject<Worker> underTest, int idleCount) {
      workerIds.add(underTest.getObject().getWorkerId());
      return false;
    }

    public Set<Integer> getWorkerIds() {
      return workerIds;
    }
  }

  /** Eviction policy for WorkerPool. Evict all idle workers, which were passed in constructor. */
  private static class CandidateEvictionPolicy implements EvictionPolicy<Worker> {
    private final ImmutableSet<Integer> workerIdsToEvict;
    private final Set<Integer> evictedWorkers;

    public CandidateEvictionPolicy(ImmutableSet<WorkerProcessMetrics> workerProcessMetrics) {
      this.workerIdsToEvict =
          workerProcessMetrics.stream()
              .flatMap(m -> m.getWorkerIds().stream())
              .collect(toImmutableSet());
      this.evictedWorkers = new HashSet<>();
    }

    @Override
    public boolean evict(EvictionConfig config, PooledObject<Worker> underTest, int idleCount) {
      int workerId = underTest.getObject().getWorkerId();
      if (workerIdsToEvict.contains(workerId)) {
        evictedWorkers.add(workerId);
        // Eviction through an EvictionPolicy doesn't go through the #returnObject and
        // #invalidateObject code paths and directly calls #destroy, so we'll need to specify that
        // explicitly here.
        underTest
            .getObject()
            .getStatus()
            .maybeUpdateStatus(Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE);
        logger.atInfo().log(
            "Evicting worker %d with mnemonic %s",
            workerId, underTest.getObject().getWorkerKey().getMnemonic());
        return true;
      }
      return false;
    }

    public ImmutableSet<Integer> getEvictedWorkers() {
      return ImmutableSet.copyOf(evictedWorkers);
    }
  }

  /** Compare worker metrics by memory consumption in descending order. */
  private static class MemoryComparator implements Comparator<WorkerProcessMetrics> {
    @Override
    public int compare(WorkerProcessMetrics m1, WorkerProcessMetrics m2) {
      return m2.getUsedMemoryInKb() - m1.getUsedMemoryInKb();
    }
  }
}
