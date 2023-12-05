// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.metrics.CallbackMetric1;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Singleton
public class ReplicationQueueMetrics {
  private static final String EVENTS = "events";
  private static final String TASKS = "tasks";
  public static final String REPLICATION_QUEUE_METRICS = "ReplicationQueueMetrics";

  private final Counter1<String> tasksScheduled;
  private final Counter1<String> tasksCancelled;
  private final Counter1<String> tasksNotScheduled;
  private final Counter1<String> tasksRescheduled;
  private final Counter1<String> tasksCompleted;
  private final Counter1<String> tasksMerged;
  private final Counter1<String> tasksFailed;
  private final Counter1<String> tasksRetrying;
  private final Counter0 eventsQueuedBeforeStartup;
  private final Counter1<String> tasksCancelledMaxRetries;
  private final MetricMaker metricMaker;
  private final Field<String> sourceField;
  private final Counter1<String> tasksStarted;
  private final Set<RegistrationHandle> metricsHandles;

  private final Counter1<String> refsFetchStarted;
  private final Counter1<String> refsFetchCompleted;
  private final Counter1<String> refsFetchFailed;

  public class RunnableWithMetrics implements Runnable {
    private final Source source;
    private final Runnable runnable;

    public RunnableWithMetrics(Source source, Runnable runnable) {
      this.source = source;
      this.runnable = runnable;
    }

    @Override
    public void run() {
      incrementTaskStarted(source);
      incrementFetchRefsStarted(source, runnable);

      runnable.run();
      if (runnable instanceof Completable) {
        Completable completedRunnable = (Completable) runnable;
        if (completedRunnable.hasSucceeded()) {
          incrementTaskCompleted(source);
          incrementFetchRefsCompleted(source, runnable);
        } else {
          incrementTaskFailed(source);
          incrementFetchRefsFailed(source, runnable);
        }
      }
    }
  }

  @Inject
  public ReplicationQueueMetrics(
      @PluginName String pluginName, @Named(REPLICATION_QUEUE_METRICS) MetricMaker metricMaker) {
    metricsHandles = new HashSet<>();

    sourceField =
        Field.ofString(
                "source",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("source", fieldValue)))
            .build();

    eventsQueuedBeforeStartup =
        registerMetric(
            metricMaker.newCounter(
                "events/queued_before_startup",
                new Description("Replication events queued before startup")
                    .setCumulative()
                    .setUnit(EVENTS)));

    tasksScheduled =
        registerMetric(
            metricMaker.newCounter(
                "tasks/scheduled",
                new Description("Replication tasks scheduled").setCumulative().setUnit(TASKS),
                sourceField));

    tasksStarted =
        registerMetric(
            metricMaker.newCounter(
                "tasks/started",
                new Description("Replication tasks started").setCumulative().setUnit(TASKS),
                sourceField));

    tasksRescheduled =
        registerMetric(
            metricMaker.newCounter(
                "tasks/rescheduled",
                new Description("Replication tasks rescheduled").setCumulative().setUnit(TASKS),
                sourceField));

    tasksCancelled =
        registerMetric(
            metricMaker.newCounter(
                "tasks/cancelled",
                new Description("Replication tasks cancelled").setCumulative().setUnit(TASKS),
                sourceField));

    tasksFailed =
        registerMetric(
            metricMaker.newCounter(
                "tasks/failed",
                new Description("Replication tasks failed").setCumulative().setUnit(TASKS),
                sourceField));

    tasksRetrying =
        registerMetric(
            metricMaker.newCounter(
                "tasks/retrying",
                new Description("Replication tasks retrying").setCumulative().setUnit(TASKS),
                sourceField));

    tasksNotScheduled =
        registerMetric(
            metricMaker.newCounter(
                "tasks/not_scheduled",
                new Description("Replication tasks not scheduled").setCumulative().setUnit(TASKS),
                sourceField));

    tasksCompleted =
        registerMetric(
            metricMaker.newCounter(
                "tasks/completed",
                new Description("Replication tasks completed").setCumulative().setUnit(TASKS),
                sourceField));

    tasksMerged =
        registerMetric(
            metricMaker.newCounter(
                "tasks/merged",
                new Description("Replication tasks merged").setCumulative().setUnit(TASKS),
                sourceField));

    tasksCancelledMaxRetries =
        registerMetric(
            metricMaker.newCounter(
                "tasks/failed_max_retries",
                new Description("Replication tasks cancelled for maximum number of retries")
                    .setCumulative()
                    .setUnit(TASKS),
                sourceField));

    refsFetchStarted =
        registerMetric(
            metricMaker.newCounter(
                "fetch/refs/started",
                new Description("Refs for which fetch operation have started")
                    .setCumulative()
                    .setUnit(TASKS),
                sourceField));
    refsFetchCompleted =
        registerMetric(
            metricMaker.newCounter(
                "fetch/refs/completed",
                new Description("Refs for which fetch operation have completed")
                    .setCumulative()
                    .setUnit(TASKS),
                sourceField));
    refsFetchFailed =
        registerMetric(
            metricMaker.newCounter(
                "fetch/refs/failed",
                new Description("Refs for which fetch operation have failed")
                    .setCumulative()
                    .setUnit(TASKS),
                sourceField));

    this.metricMaker = metricMaker;
  }

  private <T extends RegistrationHandle> T registerMetric(T metricHandle) {
    metricsHandles.add(metricHandle);
    return metricHandle;
  }

  void start(ReplicationQueue queue) {
    initCallbackMetrics(
        queue,
        Source::inflightTasksCount,
        "tasks/inflight",
        "In-flight replication tasks per source");
    initCallbackMetrics(
        queue, Source::pendingTasksCount, "tasks/pending", "Pending replication tasks per source");
  }

  void stop() {
    metricsHandles.forEach(RegistrationHandle::remove);
  }

  private void initCallbackMetrics(
      ReplicationQueue queue,
      Function<Source, Long> sourceMetricFunc,
      String metricName,
      String description) {
    CallbackMetric1<String, Long> metric =
        registerMetric(
            metricMaker.newCallbackMetric(
                metricName,
                Long.class,
                new Description(description).setGauge().setUnit(TASKS),
                sourceField));
    registerMetric(
        metricMaker.newTrigger(
            metric,
            () -> {
              List<Source> sources = queue.sourcesCollection().getAll();
              if (sources.isEmpty()) {
                metric.forceCreate("");
              } else {
                sources.forEach(
                    (source) -> {
                      metric.set(source.getRemoteConfigName(), sourceMetricFunc.apply(source));
                      metric.prune();
                    });
              }
            }));
  }

  public void incrementTaskScheduled(Source source) {
    tasksScheduled.increment(source.getRemoteConfigName());
  }

  public void incrementTaskCancelled(Source source) {
    tasksCancelled.increment(source.getRemoteConfigName());
  }

  public void incrementQueuedBeforStartup() {
    eventsQueuedBeforeStartup.increment();
  }

  public void incrementTaskCompleted(Source source) {
    tasksCompleted.increment(source.getRemoteConfigName());
  }

  public void failTask(Source source) {
    tasksFailed.increment(source.getRemoteConfigName());
  }

  public void incrementTaskMerged(Source source) {
    tasksMerged.increment(source.getRemoteConfigName());
  }

  public void incrementTaskNotScheduled(Source source) {
    tasksNotScheduled.increment(source.getRemoteConfigName());
  }

  public void incrementTaskRescheduled(Source source) {
    tasksRescheduled.increment(source.getRemoteConfigName());
  }

  public void incrementTaskFailed(Source source) {
    tasksFailed.increment(source.getRemoteConfigName());
  }

  public void incrementTaskRetrying(Source source) {
    tasksRetrying.increment(source.getRemoteConfigName());
  }

  public void incrementTaskCancelledMaxRetries(Source source) {
    tasksCancelledMaxRetries.increment(source.getRemoteConfigName());
  }

  public void incrementTaskStarted(Source source) {
    tasksStarted.increment(source.getRemoteConfigName());
  }

  public void incrementFetchRefsStarted(Source source, Runnable runnableTask) {
    if (runnableTask instanceof FetchOne) {
      refsFetchStarted.incrementBy(
          source.getRemoteConfigName(), ((FetchOne) runnableTask).getRefs().size());
    }
  }

  public void incrementFetchRefsCompleted(Source source, Runnable runnableTask) {
    if (runnableTask instanceof FetchOne) {
      refsFetchCompleted.incrementBy(
          source.getRemoteConfigName(), ((FetchOne) runnableTask).getRefs().size());
    }
  }

  public void incrementFetchRefsFailed(Source source, Runnable runnableTask) {
    if (runnableTask instanceof FetchOne) {
      refsFetchFailed.incrementBy(
          source.getRemoteConfigName(), ((FetchOne) runnableTask).getRefs().size());
    }
  }

  public Runnable runWithMetrics(Source source, Runnable runnableTask) {
    if (runnableTask instanceof RunnableWithMetrics) {
      return runnableTask;
    }

    return new RunnableWithMetrics(source, runnableTask);
  }
}
