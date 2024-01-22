// Copyright (C) 2024 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.health;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckConfig;
import com.googlesource.gerrit.plugins.healthcheck.check.AbstractHealthCheck;
import com.googlesource.gerrit.plugins.replication.ConfigResource;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

@Singleton
public class PullReplicationTasksHealthCheck extends AbstractHealthCheck {
  private static final long DEFAULT_PERIOD_OF_TIME_SECS = 10L;
  public static final String HEALTHCHECK_NAME_SUFFIX = "-outstanding-tasks";
  private final Set<String> projects;
  private final long periodOfTimeSec;

  @Inject
  public PullReplicationTasksHealthCheck(
      ListeningExecutorService executor,
      HealthCheckConfig healthCheckConfig,
      ConfigResource configResource,
      @PluginName String name,
      MetricMaker metricMaker) {
    super(executor, healthCheckConfig, name + HEALTHCHECK_NAME_SUFFIX, metricMaker);
    String healthCheckName = name + HEALTHCHECK_NAME_SUFFIX;

    Config replicationConfig = configResource.getConfig();
    this.projects =
        Set.of(
            replicationConfig.getStringList(
                HealthCheckConfig.HEALTHCHECK, healthCheckName, "projects"));
    this.periodOfTimeSec =
        ConfigUtil.getTimeUnit(
            replicationConfig,
            HealthCheckConfig.HEALTHCHECK,
            healthCheckName,
            "periodOfTime",
            DEFAULT_PERIOD_OF_TIME_SECS,
            TimeUnit.SECONDS);
  }

  public long getPeriodOfTimeSec() {
    return periodOfTimeSec;
  }

  public Set<String> getProjects() {
    return ImmutableSet.copyOf(projects);
  }

  @Override
  protected Result doCheck() throws Exception {
    return Result.PASSED;
  }
}
