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
import static com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck.HEALTHCHECK_NAME_SUFFIX;
import static com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck.PERIOD_OF_TIME_FIELD;
import static com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck.PROJECTS_FILTER_FIELD;
<<<<<<< HEAD
=======
import static org.mockito.Mockito.when;
>>>>>>> bc59557 (Implement outstanding-tasks healthcheck)

import com.google.gerrit.entities.Project;
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
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PullReplicationTasksHealthCheckTest {
  private static final String PLUGIN_NAME = "pull-replication";
  private static final String SECTION_NAME = PLUGIN_NAME + HEALTHCHECK_NAME_SUFFIX;
  private static final long TEST_TIMEOUT_MILLIS = 5000L;
  private static final long TEST_INTERVAL_MILLIS = 100L;

  private final List<String> projectsToCheck = List.of("foo", "bar/baz");
  private final int periodOfCheckSec = 10;
  @Mock private SourcesCollection sourcesCollection;

  @Mock private Source source;
  @Mock private Source anotherSource;

  @Before
  public void setUp() {
    when(sourcesCollection.getAll()).thenReturn(List.of(source));
  }

  @Test
  public void shouldReadConfig() {
    Injector injector = testInjector(new TestModule(projectsToCheck, periodOfCheckSec + " sec"));

    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(check.getProjects()).containsExactlyElementsIn(projectsToCheck);
    assertThat(check.getPeriodOfTimeSec()).isEqualTo(periodOfCheckSec);
  }

  @Test
  public void shouldOnlyCheckTasksForReposThatMatchTheRepoFilter() throws InterruptedException {
    String repo = "foo";
    when(source.pendingTasksCountForRepo(Project.nameKey(repo))).thenReturn(1L).thenReturn(0L);
    when(source.inflightTasksCountForRepo(Project.nameKey(repo))).thenReturn(1L).thenReturn(0L);

    Injector injector = testInjector(new TestModule(List.of(repo), periodOfCheckSec + " sec"));

    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    List<HealthCheck.Result> checkResults = new ArrayList<>();
    eventually(
        Duration.ofMillis(TEST_TIMEOUT_MILLIS),
        Duration.ofMillis(TEST_INTERVAL_MILLIS),
        () -> {
          checkResults.add(check.run().result);
          assertThat(checkResults)
              .containsExactly(
                  HealthCheck.Result.FAILED, HealthCheck.Result.FAILED, HealthCheck.Result.PASSED);
        });
  }

  @Test
  public void shouldCheckAllOutstandingTasksWhenRepoFilterIsNotConfigured()
      throws InterruptedException {
    List<String> noRepoFilter = List.of();
    when(source.pendingTasksCount()).thenReturn(1L).thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(1L).thenReturn(0L);

    Injector injector = testInjector(new TestModule(noRepoFilter, periodOfCheckSec + " sec"));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    List<HealthCheck.Result> checkResults = new ArrayList<>();
    eventually(
        Duration.ofMillis(TEST_TIMEOUT_MILLIS),
        Duration.ofMillis(TEST_INTERVAL_MILLIS),
        () -> {
          checkResults.add(check.run().result);
          assertThat(checkResults)
              .containsExactly(
                  HealthCheck.Result.FAILED, HealthCheck.Result.FAILED, HealthCheck.Result.PASSED);
        });
  }

  @Test
  public void shouldCheckTasksAcrossSources() throws InterruptedException {
    when(sourcesCollection.getAll()).thenReturn(List.of(source, anotherSource));
    when(source.pendingTasksCount())
        .thenReturn(1L)
        .thenReturn(0L)
        .thenReturn(0L)
        .thenReturn(0L)
        .thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(1L).thenReturn(0L).thenReturn(0L).thenReturn(0L);
    when(anotherSource.pendingTasksCount()).thenReturn(1L).thenReturn(0L).thenReturn(0L);
    when(anotherSource.inflightTasksCount()).thenReturn(1L).thenReturn(0L);

    Injector injector = testInjector(new TestModule(List.of(), periodOfCheckSec + " sec"));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    List<HealthCheck.Result> checkResults = new ArrayList<>();
    eventually(
        Duration.ofMillis(TEST_TIMEOUT_MILLIS),
        Duration.ofMillis(TEST_INTERVAL_MILLIS),
        () -> {
          checkResults.add(check.run().result);
          assertThat(checkResults)
              .containsExactly(
                  HealthCheck.Result.FAILED,
                  HealthCheck.Result.FAILED,
                  HealthCheck.Result.FAILED,
                  HealthCheck.Result.FAILED,
                  HealthCheck.Result.PASSED);
        });
  }

  private static void eventually(Duration timeout, Duration interval, Runnable assertion)
      throws InterruptedException {
    Instant start = Instant.now();
    Instant max = start.plus(timeout);

    boolean failed;
    do {
      try {
        assertion.run();
        failed = false;
      } catch (Throwable e) {
        failed = true;
        if (Instant.now().isAfter(max)) {
          throw e;
        } else {
          Thread.sleep(interval.toMillis());
        }
      }
    } while (failed);
  }

  private Injector testInjector(AbstractModule testModule) {
    return Guice.createInjector(new HealthCheckExtensionApiModule(), testModule);
  }

  private class TestModule extends AbstractModule {
    Config config;
    ConfigResource configResource;
    private final HealthCheckConfig healthCheckConfig = HealthCheckConfig.DEFAULT_CONFIG;

    public TestModule(List<String> projects, String periodOfTime) {
      this.config = new Config();
      config.setStringList(
          HealthCheckConfig.HEALTHCHECK, SECTION_NAME, PROJECTS_FILTER_FIELD, projects);
      config.setString(
          HealthCheckConfig.HEALTHCHECK, SECTION_NAME, PERIOD_OF_TIME_FIELD, periodOfTime);
      configResource =
          new ConfigResource() {
            @Override
            public Config getConfig() {
              return config;
            }

            @Override
            public String getVersion() {
              return "";
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
      bind(SourcesCollection.class).toInstance(sourcesCollection);
    }
  }
}
