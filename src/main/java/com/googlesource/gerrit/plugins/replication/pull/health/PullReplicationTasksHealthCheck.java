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

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckConfig;
import com.googlesource.gerrit.plugins.healthcheck.check.AbstractHealthCheck;
import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;
import com.googlesource.gerrit.plugins.replication.MergedConfigResource;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

@Singleton
public class PullReplicationTasksHealthCheck extends AbstractHealthCheck {
  private static final FluentLogger flogger = FluentLogger.forEnclosingClass();
  private static final long DEFAULT_PERIOD_OF_TIME_SECS = 10L;
  public static final String HEALTHCHECK_NAME_SUFFIX = "-outstanding-tasks";
  public static final String PROJECTS_FILTER_FIELD = "projects";
  public static final String PERIOD_OF_TIME_FIELD = "periodOfTime";
  private final Set<String> projects;
  private final long periodOfTimeNanos;
  private final SourcesCollection sourcesCollection;
  private final Ticker ticker;
  private Optional<Long> successfulSince = Optional.empty();
  private boolean isCaughtUp;

  @Inject
  public PullReplicationTasksHealthCheck(
      ListeningExecutorService executor,
      HealthCheckConfig healthCheckConfig,
      MergedConfigResource configResource,
      @PluginName String name,
      MetricMaker metricMaker,
      SourcesCollection sourcesCollection,
      Ticker ticker) {
    super(executor, healthCheckConfig, name + HEALTHCHECK_NAME_SUFFIX, metricMaker);
    String healthCheckName = name + HEALTHCHECK_NAME_SUFFIX;

    Config replicationConfig = configResource.getConfig();
    this.projects =
        Set.of(
            replicationConfig.getStringList(
                HealthCheckConfig.HEALTHCHECK, healthCheckName, PROJECTS_FILTER_FIELD));
    this.periodOfTimeNanos =
        ConfigUtil.getTimeUnit(
            replicationConfig,
            HealthCheckConfig.HEALTHCHECK,
            healthCheckName,
            PERIOD_OF_TIME_FIELD,
            DEFAULT_PERIOD_OF_TIME_SECS,
            TimeUnit.NANOSECONDS);
    this.sourcesCollection = sourcesCollection;
    this.ticker = ticker;
  }

  public long getPeriodOfTimeNanos() {
    return periodOfTimeNanos;
  }

  public Set<String> getProjects() {
    return ImmutableSet.copyOf(projects);
  }

  @Override
  protected Result doCheck() throws Exception {
    if (isCaughtUp) {
      return Result.PASSED;
    }
    long checkTime = ticker.read();
    List<Source> sources = sourcesCollection.getAll();
    boolean hasNoOutstandingTasks =
        sources.stream()
            .allMatch(
                source -> {
                  if (projects.isEmpty()) {
                    return source.pendingTasksCount() == 0 && source.inflightTasksCount() == 0;
                  } else {
                    return projects.stream()
                        .allMatch(
                            project ->
                                source.zeroPendingTasksForRepo(Project.nameKey(project))
                                    && source.zeroInflightTasksForRepo(Project.nameKey(project)));
                  }
                });
    successfulSince =
        hasNoOutstandingTasks ? successfulSince.or(() -> Optional.of(checkTime)) : Optional.empty();
    return reportResult(checkTime);
  }

  private HealthCheck.Result reportResult(long checkTime) {
    Optional<Result> maybePassed =
        successfulSince.filter(ss -> checkTime >= ss + periodOfTimeNanos).map(ss -> Result.PASSED);
    isCaughtUp = maybePassed.isPresent();

    if (successfulSince.isPresent()) {
      if (maybePassed.filter(res -> res.equals(Result.PASSED)).isPresent()) {
        flogger.atFine().log(
            "Instance has caught up with all outstanding pull-replication tasks, so will be marked HEALTHY");
      } else {
        flogger.atFine().log(
            "[STATUS: UNHEALTHY] Instance has been healthy since: %s, but needs to be consistently healthy until: %s so that it can be marked as healthy overall",
            Instant.ofEpochMilli(Duration.ofNanos(successfulSince.get()).toMillis()),
            Instant.ofEpochMilli(
                Duration.ofNanos(successfulSince.get() + periodOfTimeNanos).toMillis()));
      }
    }
    return maybePassed.orElse(Result.FAILED);
  }
}
