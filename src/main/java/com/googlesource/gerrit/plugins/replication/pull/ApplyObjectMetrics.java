// Copyright (C) 2020 The Android Open Source Project
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
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.logging.PluginMetadata;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class ApplyObjectMetrics {
  private final Timer1<String> executionTime;
  private final Timer1<String> end2EndTime;

  @Inject
  ApplyObjectMetrics(@PluginName String pluginName, MetricMaker metricMaker) {
    Field<String> objectLatencyField =
        Field.ofString(
                "object_latency",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("object_latency", fieldValue)))
            .build();
    executionTime =
        metricMaker.newTimer(
            "apply_object_latency",
            new Description("Time spent applying object from remote source.")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            objectLatencyField);

    Field<String> end2EndLatencyField =
        Field.ofString(
                "end_2_end_latency",
                (metadataBuilder, fieldValue) ->
                    metadataBuilder
                        .pluginName(pluginName)
                        .addPluginMetadata(PluginMetadata.create("end_2_end_latency", fieldValue)))
            .build();
    end2EndTime =
        metricMaker.newTimer(
            "apply_object_end_2_end_latency",
            new Description("Time spent for e2e replication with the apply object REST API")
                .setCumulative()
                .setUnit(Description.Units.MILLISECONDS),
            end2EndLatencyField);
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
   * Start the replication latency timer from a source.
   *
   * @param name the source name.
   * @return the timer context.
   */
  public Timer1.Context<String> startEnd2End(String name) {
    return end2EndTime.start(name);
  }
}
