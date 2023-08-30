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
import com.google.gerrit.metrics.CallbackMetric1;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Singleton;
import java.util.List;
import java.util.function.Function;

@Singleton
public class ReplicationQueueMetrics {
  private static final String EVENTS = "events";
  private static final String TASKS = "tasks";

  private final Counter1<String> tasksScheduled;
  private final Counter1<String> tasksCancelled;
  private final Counter1<String> tasksNotScheduled;
  private final Counter1<String> tasksRescheduled;
  private final Counter1<String> tasksCompleted;
  private final Counter1<String> tasksMerged;
  private final Counter1<String> tasksFailed;
  private final Counter1<String> tasksRetrying;
  private final Counter0 eventsFired;
  private final Counter0 eventsQueuedBeforeStartup;
  private final Counter1<String> tasksCancelledMaxRetries;
  private final MetricMaker metricMaker;
  private final Field<String> sourceField;

  public ReplicationQueueMetrics(@PluginName String pluginName, MetricMaker metricMaker) {
    sourceField =
        Field.ofString(
                "source",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("source", fieldValue)))
            .build();

    eventsFired =
        metricMaker.newCounter(
            "events/fired",
            new Description("Replication event fired").setCumulative().setUnit(EVENTS));

    eventsQueuedBeforeStartup =
        metricMaker.newCounter(
            "events/queued_before_startup",
            new Description("Replication events queued before startup")
                .setCumulative()
                .setUnit(EVENTS));

    tasksScheduled =
        metricMaker.newCounter(
            "task/scheduled",
            new Description("Replication tasks scheduled").setCumulative().setUnit(TASKS),
            sourceField);

    tasksRescheduled =
        metricMaker.newCounter(
            "task/rescheduled",
            new Description("Replication tasks rescheduled").setCumulative().setUnit(TASKS),
            sourceField);

    tasksCancelled =
        metricMaker.newCounter(
            "task/cancelled",
            new Description("Replication tasks cancelled").setCumulative().setUnit(TASKS),
            sourceField);

    tasksFailed =
        metricMaker.newCounter(
            "task/failed",
            new Description("Replication tasks failed").setCumulative().setUnit(TASKS),
            sourceField);

    tasksRetrying =
        metricMaker.newCounter(
            "task/retrying",
            new Description("Replication tasks retrying").setCumulative().setUnit(TASKS),
            sourceField);

    tasksNotScheduled =
        metricMaker.newCounter(
            "task/not_scheduled",
            new Description("Replication tasks not scheduled").setCumulative().setUnit(TASKS),
            sourceField);

    tasksCompleted =
        metricMaker.newCounter(
            "tasks/completed",
            new Description("Replication tasks completed").setCumulative().setUnit(TASKS),
            sourceField);

    tasksMerged =
        metricMaker.newCounter(
            "tasks/merged",
            new Description("Replication tasks merged").setCumulative().setUnit(TASKS),
            sourceField);

    tasksCancelledMaxRetries =
        metricMaker.newCounter(
            "tasks/completed",
            new Description("Replication tasks cancelled for maximum number of retries")
                .setCumulative()
                .setUnit(TASKS),
            sourceField);

    this.metricMaker = metricMaker;
  }

  void initCallbackMetrics(ReplicationQueue queue) {
    initCallbackMetrics(
        queue,
        Source::inflightTasksCount,
        "tasks/inflight",
        "In-flight replication tasks per source");
    initCallbackMetrics(
        queue, Source::pendingTasksCount, "tasks/pending", "Pending replication tasks per source");
  }

  private void initCallbackMetrics(
      ReplicationQueue queue,
      Function<Source, Long> sourceMetricFunc,
      String metricName,
      String description) {
    CallbackMetric1<String, Long> metric =
        metricMaker.newCallbackMetric(
            metricName,
            Long.class,
            new Description(description).setGauge().setUnit(TASKS),
            sourceField);
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
        });
  }

  public void incrementTaskScheduled(Source source) {
    tasksScheduled.increment(source.getRemoteConfigName());
  }

  public void incrementTaskCancelled(Source source) {
    tasksCancelled.increment(source.getRemoteConfigName());
  }

  public void incrementEventFired() {
    eventsFired.increment();
  }

  public void incrementQueuedBeforStartup() {
    eventsQueuedBeforeStartup.increment();
  }

  public void completeTask(Source source) {
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
}
