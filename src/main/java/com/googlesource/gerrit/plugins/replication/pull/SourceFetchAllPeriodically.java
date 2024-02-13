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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class SourceFetchAllPeriodically {
  interface Factory {
    SourceFetchAllPeriodically create(Source source);
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final ProjectCache projects;
  private final ReplicationState.Factory fetchReplicationFactory;
  private final Provider<PullReplicationApiRequestMetrics> metricsProvider;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final Source source;

  @Inject
  SourceFetchAllPeriodically(
      ProjectCache projects,
      ReplicationState.Factory fetchReplicationFactory,
      Provider<PullReplicationApiRequestMetrics> metricsProvider,
      DynamicItem<EventDispatcher> eventDispatcher,
      @Assisted Source source) {
    this.projects = projects;
    this.fetchReplicationFactory = fetchReplicationFactory;
    this.metricsProvider = metricsProvider;
    this.eventDispatcher = eventDispatcher;
    this.source = source;
  }

  ScheduledFuture<?> start(ScheduledExecutorService pool) {
    return pool.scheduleAtFixedRate(
        this::scheduleFetchAll, 0L, source.fetchAllEvery(), TimeUnit.SECONDS);
  }

  private void scheduleFetchAll() {
    Optional<PullReplicationApiRequestMetrics> metrics = Optional.of(metricsProvider.get());
    long repositoriesToBeFetched =
        projects.all().stream()
            .filter(source::wouldFetchProject)
            .map(
                projectToFetch ->
                    source.scheduleNow(
                        projectToFetch,
                        FetchOne.ALL_REFS,
                        fetchReplicationFactory.create(
                            new FetchResultProcessing.GitUpdateProcessing(eventDispatcher.get())),
                        metrics))
            .count();
    logger.atInfo().log(
        "The %d repositories were scheduled for %s remote to fetch %s",
        repositoriesToBeFetched, source.getRemoteConfigName(), FetchOne.ALL_REFS);
  }
}
