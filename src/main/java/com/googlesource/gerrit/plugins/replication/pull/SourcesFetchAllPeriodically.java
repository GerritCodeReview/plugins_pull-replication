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

import static com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration.DEFAULT_FETCH_ALL_DISABLED;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

@Singleton
class SourcesFetchAllPeriodically {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Supplier<List<ScheduledSource>> scheduled;

  @Inject
  SourcesFetchAllPeriodically(
      WorkQueue workQueue,
      Provider<SourcesCollection> sources,
      Provider<SourceFetchAllPeriodically.Factory> fetchAllCreator) {
    this.scheduled =
        Suppliers.memoize(() -> scheduleFetchAll(workQueue, sources, fetchAllCreator.get()));
  }

  void start() {
    scheduled.get();
  }

  ReplicationFilter skipFromReplicateAllOnPluginStart() {
    Set<Project.NameKey> projectsToSkip =
        scheduled.get().stream()
            .flatMap(scheduledSource -> scheduledSource.sourceFetchAll.projectsToFetch())
            .collect(toSet());
    if (projectsToSkip.isEmpty()) {
      return ReplicationFilter.all();
    }

    return new SkipProjectsFilter(projectsToSkip);
  }

  private List<ScheduledSource> scheduleFetchAll(
      WorkQueue workQueue,
      Provider<SourcesCollection> sources,
      SourceFetchAllPeriodically.Factory fetchAllCreator) {
    Supplier<ScheduledExecutorService> queue =
        Suppliers.memoize(() -> workQueue.createQueue(1, "PeriodicallyFetchFromSources"));
    return sources.get().getAll().stream()
        .filter(source -> source.fetchAllEvery() > DEFAULT_FETCH_ALL_DISABLED)
        .map(
            source -> {
              logger.atInfo().log(
                  "Enabling periodic (every %ds) fetch of all refs for [%s] remote",
                  source.fetchAllEvery(), source.getRemoteConfigName());
              SourceFetchAllPeriodically sourceFetchAll = fetchAllCreator.create(source);
              return new ScheduledSource(sourceFetchAll, sourceFetchAll.start(queue.get()));
            })
        .collect(toList());
  }

  void stop() {
    scheduled.get().forEach(source -> source.schedule.cancel(true));
  }

  private static class ScheduledSource {
    private final SourceFetchAllPeriodically sourceFetchAll;
    private final ScheduledFuture<?> schedule;

    private ScheduledSource(
        SourceFetchAllPeriodically sourceFetchAll, ScheduledFuture<?> schedule) {
      this.sourceFetchAll = sourceFetchAll;
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
