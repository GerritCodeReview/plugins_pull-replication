// Copyright (C) 2015 The Android Open Source Project
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
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.Histogram1;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.TimeUnit;

@Singleton
public class FetchReplicationMetrics {
  private final Timer1<String> executionTime;
  private final Timer1<String> end2EndExecutionTime;
  private final Timer1<String> fetchTaskSchedulingTime;
  private final Histogram1<String> executionDelay;
  private final Histogram1<String> executionRetries;

  @Inject
  FetchReplicationMetrics(@PluginName String pluginName, MetricMaker metricMaker) {
    Field<String> SOURCE_FIELD =
        Field.ofString(
                "pull_replication",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("pull_replication", fieldValue)))
            .build();

    executionTime =
        metricMaker.newTimer(
            "replication_latency",
            new Description("Time spent fetching from remote source.")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            SOURCE_FIELD);

    end2EndExecutionTime =
        metricMaker.newTimer(
            "replication_end_2_end_latency",
            new Description("Time spent end-2-end fetching from remote source.")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            SOURCE_FIELD);

    fetchTaskSchedulingTime =
        metricMaker.newTimer(
            "replication_fetch_task_scheduling_latency",
            new Description("Time spent scheduling a fetch task to the remote target.")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            SOURCE_FIELD);

    executionDelay =
        metricMaker.newHistogram(
            "replication_delay",
            new Description("Time spent waiting before fetching from remote source")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            SOURCE_FIELD);

    executionRetries =
        metricMaker.newHistogram(
            "replication_retries",
            new Description("Number of retries when fetching from remote sources")
                .setCumulative()
                .setUnit("retries"),
            SOURCE_FIELD);
  }

  /**
   * Start the replication latency timer from a source.
   *
   * @param name the source name.
   * @return the timer context.
   */
  public Timer1.Context<String> start(String name) {
    return executionTime.start(name);
  }

  /**
   * Start the end-to-end replication latency timer from a source.
   *
   * @param name the source name.
   * @return the timer context.
   */
  public Timer1.Context<String> startEnd2End(String name) {
    return end2EndExecutionTime.start(name);
  }

  /**
   * Record the end-to-end replication latency timer from a source.
   *
   * @param name the source name.
   * @param metricNanos the timer value in nanos
   */
  public void recordEnd2End(String name, long metricNanos) {
    end2EndExecutionTime.record(name, metricNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Record the replication delay and retry metrics for a source.
   *
   * @param name the source name.
   * @param delay replication delay in milliseconds.
   * @param retries number of retries.
   */
  public void record(String name, long delay, long retries) {
    executionDelay.record(name, delay);
    executionRetries.record(name, retries);
  }

  /**
   * Start the fetch task scheduling timer from a source.
   *
   * @param name the target name
   * @return the timer context
   */
  public Timer1.Context<String> startFetchTaskSchedulingTimer(String name) {
    return fetchTaskSchedulingTime.start(name);
  }
}
