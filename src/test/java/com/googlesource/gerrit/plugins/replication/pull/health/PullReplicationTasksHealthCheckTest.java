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
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.google.common.testing.FakeTicker;
import com.google.gerrit.common.Nullable;
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
import com.googlesource.gerrit.plugins.replication.MergedConfigResource;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
  private static final String ZERO_PERIOD_OF_TIME = "0 sec";
  private static final Optional<String> NO_PROJECT_FILTER = Optional.empty();

  private final int periodOfTimeMillis = 10;
  private final String periodOfTimeMillisStr = periodOfTimeMillis + " ms";
  private final FakeTicker fakeTicker = new FakeTicker();
  @Mock private SourcesCollection sourcesCollection;

  @Mock private Source source;
  @Mock private Source anotherSource;

  @Before
  public void setUp() {
    when(sourcesCollection.getAll()).thenReturn(List.of(source));
  }

  @Test
  public void shouldReadConfig() {
    List<String> projectsToCheck = List.of("foo", "bar/baz");
    Injector injector = testInjector(new TestModule(projectsToCheck, periodOfTimeMillisStr));

    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(check.getProjects()).containsExactlyElementsIn(projectsToCheck);
    assertThat(check.getPeriodOfTimeNanos())
        .isEqualTo(TimeUnit.MILLISECONDS.toNanos(periodOfTimeMillis));
  }

  @Test
  public void shouldOnlyCheckTasksForReposThatMatchTheRepoFilter() {
    int numIterations = 3;
    String repo = "foo";
    when(source.zeroPendingTasksForRepo(Project.nameKey(repo))).thenReturn(false).thenReturn(true);
    when(source.zeroInflightTasksForRepo(Project.nameKey(repo))).thenReturn(false).thenReturn(true);

    PullReplicationTasksHealthCheck check = newPullReplicationTasksHealthCheck(Optional.of(repo));

    List<HealthCheck.Result> checkResults = runNTimes(numIterations, check);
    assertThat(checkResults)
        .containsExactly(
            HealthCheck.Result.FAILED, HealthCheck.Result.FAILED, HealthCheck.Result.PASSED);
  }

  @Test
  public void shouldCheckAllOutstandingTasksWhenRepoFilterIsNotConfigured() {
    when(source.pendingTasksCount()).thenReturn(1L).thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(1L).thenReturn(0L);

    PullReplicationTasksHealthCheck check = newPullReplicationTasksHealthCheck(NO_PROJECT_FILTER);

    List<HealthCheck.Result> checkResults = runNTimes(3, check);
    assertThat(checkResults)
        .containsExactly(
            HealthCheck.Result.FAILED, HealthCheck.Result.FAILED, HealthCheck.Result.PASSED);
  }

  @Test
  public void shouldCheckTasksAcrossSources() {
    when(sourcesCollection.getAll()).thenReturn(List.of(source, anotherSource));
    when(source.pendingTasksCount()).thenReturn(1L).thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(1L).thenReturn(0L);
    when(anotherSource.pendingTasksCount()).thenReturn(1L).thenReturn(0L);
    when(anotherSource.inflightTasksCount()).thenReturn(1L).thenReturn(0L);

    PullReplicationTasksHealthCheck check = newPullReplicationTasksHealthCheck(NO_PROJECT_FILTER);

    List<HealthCheck.Result> checkResults = runNTimes(5, check);
    assertThat(checkResults)
        .containsExactly(
            HealthCheck.Result.FAILED,
            HealthCheck.Result.FAILED,
            HealthCheck.Result.FAILED,
            HealthCheck.Result.FAILED,
            HealthCheck.Result.PASSED);
  }

  @Test
  public void shouldFailIfCheckDoesNotReportHealthyConsistentlyOverPeriodOfTime() {
    long healthCheckPeriodOfTimeMsec = 50L;
    String healthCheckPeriodOfTime = healthCheckPeriodOfTimeMsec + " ms";
    int checkInterations = 3;
    Duration checkInterval =
        Duration.ofMillis(healthCheckPeriodOfTimeMsec).dividedBy(checkInterations);
    long fakeTimerStartNanos = fakeTicker.read();
    when(source.pendingTasksCount()).thenReturn(0L).thenReturn(1L).thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(0L);

    Injector injector = testInjector(new TestModule(List.of(), healthCheckPeriodOfTime));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    List<HealthCheck.Result> checkResults =
        runNTimes(checkInterations, check, () -> fakeTicker.advance(checkInterval));

    assertThat(checkResults).doesNotContain(HealthCheck.Result.PASSED);
    assertThat(fakeTicker.read())
        .isAtLeast(Duration.ofMillis(periodOfTimeMillis).plusNanos(fakeTimerStartNanos).toNanos());
  }

  @Test
  public void shouldFailOnFirstInvocationEvenIfThereAreNoOutstandingTasksAndANonZeroPeriodOfTime() {
    mockSourceWithNoOutstandingTasks();

    Injector injector = testInjector(new TestModule(List.of(), periodOfTimeMillisStr));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(check.run().result).isEqualTo(HealthCheck.Result.FAILED);
  }

  @Test
  public void
      shouldReportHealthyOnFirstInvocationIfThereAreNoOutstandingTasksAndAZeroPeriodOfTime() {
    mockSourceWithNoOutstandingTasks();

    Injector injector = testInjector(new TestModule(List.of(), ZERO_PERIOD_OF_TIME));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(check.run().result).isEqualTo(HealthCheck.Result.PASSED);
  }

  @Test
  public void shouldAlwaysReportHealthyAfterItHasCaughtUpWithOutstandingTasks() {
    int numCheckIterations = 2;
    when(source.pendingTasksCount()).thenReturn(0L, 0L, 1L);
    when(source.inflightTasksCount()).thenReturn(0L);

    Injector injector = testInjector(new TestModule(List.of(), periodOfTimeMillisStr));
    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);

    assertThat(
            runNTimes(
                numCheckIterations,
                check,
                () -> fakeTicker.advance(Duration.ofMillis(periodOfTimeMillis))))
        .containsExactly(HealthCheck.Result.FAILED, HealthCheck.Result.PASSED);

    assertThat(check.run().result).isEqualTo(HealthCheck.Result.PASSED);
  }

  private Injector testInjector(AbstractModule testModule) {
    return Guice.createInjector(new HealthCheckExtensionApiModule(), testModule);
  }

  private List<HealthCheck.Result> runNTimes(int nTimes, PullReplicationTasksHealthCheck check) {
    return runNTimes(nTimes, check, null);
  }

  private PullReplicationTasksHealthCheck newPullReplicationTasksHealthCheck(
      Optional<String> projectNameToCheck) {
    Injector injector =
        testInjector(new TestModule(projectNameToCheck.stream().toList(), ZERO_PERIOD_OF_TIME));

    PullReplicationTasksHealthCheck check =
        injector.getInstance(PullReplicationTasksHealthCheck.class);
    return check;
  }

  private List<HealthCheck.Result> runNTimes(
      int nTimes, PullReplicationTasksHealthCheck check, @Nullable Runnable postRunFunc) {
    List<HealthCheck.Result> results = new ArrayList<>();
    for (int i = 0; i < nTimes; i++) {
      results.add(check.run().result);
      if (postRunFunc != null) {
        postRunFunc.run();
      }
    }

    return results;
  }

  private void mockSourceWithNoOutstandingTasks() {
    when(source.pendingTasksCount()).thenReturn(0L);
    when(source.inflightTasksCount()).thenReturn(0L);
  }

  private class TestModule extends AbstractModule {
    Config config;
    MergedConfigResource configResource;
    private final HealthCheckConfig healthCheckConfig = HealthCheckConfig.DEFAULT_CONFIG;

    public TestModule(List<String> projects, String periodOfTime) {
      this.config = new Config();
      config.setStringList(
          HealthCheckConfig.HEALTHCHECK, SECTION_NAME, PROJECTS_FILTER_FIELD, projects);
      config.setString(
          HealthCheckConfig.HEALTHCHECK, SECTION_NAME, PERIOD_OF_TIME_FIELD, periodOfTime);
      configResource =
          MergedConfigResource.withBaseOnly(
              new ConfigResource() {
                @Override
                public Config getConfig() {
                  return config;
                }

                @Override
                public String getVersion() {
                  return "";
                }
              });
    }

    @Override
    protected void configure() {
      bind(Config.class).toInstance(config);
      bind(MergedConfigResource.class).toInstance(configResource);
      bind(MetricMaker.class).toInstance(new DisabledMetricMaker());
      bind(HealthCheckConfig.class).toInstance(healthCheckConfig);
      bind(String.class).annotatedWith(PluginName.class).toInstance(PLUGIN_NAME);
      bind(SourcesCollection.class).toInstance(sourcesCollection);
      bind(Ticker.class).toInstance(fakeTicker);
    }
  }
}
