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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.inject.AbstractModule;
import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;

public class PullReplicationHealthCheckModule extends AbstractModule {
  private static final FluentLogger flogger = FluentLogger.forEnclosingClass();
  private static final String HEALTHCHECK_EXTENSION_MODULE_CLASS_NAME =
      "com.googlesource.gerrit.plugins.healthcheck.HealthCheckExtensionApiModule";

  @Override
  protected void configure() {
    registerHealthChecksIfHealthcheckPluginIsInstalled();
  }

  private void registerHealthChecksIfHealthcheckPluginIsInstalled() {
    try {
      Class.forName(HEALTHCHECK_EXTENSION_MODULE_CLASS_NAME);
      DynamicSet.bind(binder(), HealthCheck.class).to(PullReplicationTasksHealthCheck.class);
    } catch (Exception e) {
      flogger.atFine().withCause(e).log(
          "Unable to find %s, is healthcheck plugin installed?",
          HEALTHCHECK_EXTENSION_MODULE_CLASS_NAME);
      flogger.atFine().log("Skipping registration of pull replication health checks");
    }
  }
}
