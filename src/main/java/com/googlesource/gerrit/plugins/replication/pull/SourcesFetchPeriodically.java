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

package com.googlesource.gerrit.plugins.replication.pull;

import static com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration.DEFAULT_PERIODIC_FETCH_DISABLED;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.common.base.Suppliers;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ReplicationFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

@Singleton
class SourcesFetchPeriodically {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final WorkQueue workQueue;
  private final Provider<SourcesCollection> sources;
  private final Provider<SourceFetchPeriodically.Factory> fetchAllCreator;
  private final List<ScheduledSource> scheduled;

  @Inject
  SourcesFetchPeriodically(
      WorkQueue workQueue,
      Provider<SourcesCollection> sources,
      Provider<SourceFetchPeriodically.Factory> fetchAllCreator) {
    this.workQueue = workQueue;
    this.sources = sources;
    this.fetchAllCreator = fetchAllCreator;
    this.scheduled = new ArrayList<>();
  }

  void start() {
    scheduled.addAll(scheduleFetchAll(workQueue, sources.get(), fetchAllCreator.get()));
  }

  ReplicationFilter skipFromReplicateAllOnPluginStart() {
    Set<Project.NameKey> projectsToSkip =
        scheduled.stream()
            .flatMap(scheduledSource -> scheduledSource.sourceFetch.projectsToFetch())
            .collect(toSet());
    if (projectsToSkip.isEmpty()) {
      return ReplicationFilter.all();
    }

    return new SkipProjectsFilter(projectsToSkip);
  }

  private List<ScheduledSource> scheduleFetchAll(
      WorkQueue workQueue,
      SourcesCollection sources,
      SourceFetchPeriodically.Factory fetchAllCreator) {
    Supplier<ScheduledExecutorService> queue =
        Suppliers.memoize(() -> workQueue.createQueue(1, "PeriodicallyFetchFromSources"));
    return sources.getAll().stream()
        .filter(source -> source.fetchEvery() > DEFAULT_PERIODIC_FETCH_DISABLED)
        .map(
            source -> {
              logger.atInfo().log(
                  "Enabling periodic (every %ds) fetch of all refs for [%s] remote",
                  source.fetchEvery(), source.getRemoteConfigName());
              SourceFetchPeriodically sourceFetchAll = fetchAllCreator.create(source);
              return new ScheduledSource(sourceFetchAll, sourceFetchAll.start(queue.get()));
            })
        .collect(toList());
  }

  void stop() {
    scheduled.forEach(source -> source.schedule.cancel(true));
  }

  private static class ScheduledSource {
    private final SourceFetchPeriodically sourceFetch;
    private final ScheduledFuture<?> schedule;

    private ScheduledSource(SourceFetchPeriodically sourceFetch, ScheduledFuture<?> schedule) {
      this.sourceFetch = sourceFetch;
      this.schedule = schedule;
    }
  }

  /**
   * The ReplicationFilter implementation that matches all projects that are not part of the
   * internal set.
   */
  private static class SkipProjectsFilter extends ReplicationFilter {
    private final Set<Project.NameKey> projectsToSkip;

    private SkipProjectsFilter(Set<Project.NameKey> projectsToSkip) {
      super(Collections.emptyList());
      this.projectsToSkip = projectsToSkip;
    }

    @Override
    public boolean matches(Project.NameKey name) {
      return !projectsToSkip.contains(name);
    }
  }
}
