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

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.metrics.MetricMaker;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckConfig;
import com.googlesource.gerrit.plugins.healthcheck.check.AbstractHealthCheck;

@Singleton
public class PullReplicationTasksHealthCheck extends AbstractHealthCheck {

  @Inject
  public PullReplicationTasksHealthCheck(
      ListeningExecutorService executor,
      HealthCheckConfig config,
      @PluginName String name,
      MetricMaker metricMaker) {
    super(executor, config, name + "-tasks", metricMaker);
  }

  @Override
  protected Result doCheck() throws Exception {
    return Result.PASSED;
  }
}
