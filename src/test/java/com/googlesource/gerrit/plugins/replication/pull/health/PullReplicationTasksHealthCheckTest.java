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

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.metrics.MetricMaker;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckConfig;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckExtensionApiModule;
import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;
import com.googlesource.gerrit.plugins.replication.ConfigResource;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class PullReplicationTasksHealthCheckTest {
  private static final String PLUGIN_NAME = "pull-replication";

  @Test
  public void shouldAlwaysPass() {
    Injector injector = testInjector(new TestModule(List.of("foo", "bar/baz"), 10L));

    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(check.run().result).isEqualTo(HealthCheck.Result.PASSED);
  }

  private Injector testInjector(AbstractModule testModule) {
    return Guice.createInjector(new HealthCheckExtensionApiModule(), testModule);
  }

  private class TestModule extends AbstractModule {
    Config config;
    ConfigResource configResource;
    private final HealthCheckConfig healthCheckConfig = HealthCheckConfig.DEFAULT_CONFIG;

    public TestModule(List<String> projects, long periodOfTime) {
      this.config = new Config();
      config.setStringList(
          HealthCheckConfig.HEALTHCHECK, "pull-replication-tasks", "projects", projects);
      config.setLong(
          HealthCheckConfig.HEALTHCHECK, "pull-replication-tasks", "periodOfTime", periodOfTime);
      configResource =
          new ConfigResource() {
            @Override
            public Config getConfig() {
              return config;
            }

            @Override
            public String getVersion() {
              return null;
            }
          };
    }

    @Override
    protected void configure() {
      bind(Config.class).toInstance(config);
      bind(ConfigResource.class).toInstance(configResource);
      bind(MetricMaker.class).toInstance(new DisabledMetricMaker());
      bind(HealthCheckConfig.class).toInstance(healthCheckConfig);
      bind(String.class).annotatedWith(PluginName.class).toInstance(PLUGIN_NAME);
    }
  }
}
