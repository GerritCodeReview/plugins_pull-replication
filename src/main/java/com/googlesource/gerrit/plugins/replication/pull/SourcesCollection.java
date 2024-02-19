// Copyright (C) 2019 The Android Open Source Project
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

import static java.util.stream.Collectors.toList;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ConfigParser;
import com.googlesource.gerrit.plugins.replication.RemoteConfiguration;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class SourcesCollection implements ReplicationSources {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Source.Factory sourceFactory;
  private volatile Map<String, Source> sources;
  private final ShutdownState shutdownState;
  private final Provider<ReplicationQueue> replicationQueue;

  @Inject
  public SourcesCollection(
      ReplicationConfig replicationConfig,
      ConfigParser configParser,
      Source.Factory sourceFactory,
      EventBus eventBus,
      Provider<ReplicationQueue> replicationQueue,
      ShutdownState shutdownState)
      throws ConfigParser.ReplicationConfigInvalidException {
    this.sourceFactory = sourceFactory;
    this.shutdownState = shutdownState;
    this.sources =
        allSources(sourceFactory, configParser.parseRemotes(replicationConfig.getConfig()));
    this.replicationQueue = replicationQueue;
    eventBus.register(this);
  }

  @Override
  public List<Source> getAll() {
    return sources.values().stream().filter(Objects::nonNull).collect(toList());
  }

  public Optional<Source> getByRemoteName(String remoteName) {
    return Optional.ofNullable(sources.get(remoteName));
  }

  private Map<String, Source> allSources(
      Source.Factory sourceFactory, List<RemoteConfiguration> sourceConfigurations) {
    return sourceConfigurations.stream()
        .filter((c) -> c instanceof SourceConfiguration)
        .map((c) -> (SourceConfiguration) c)
        .map(sourceFactory::create)
        .collect(Collectors.toMap(Source::getRemoteConfigName, Function.identity()));
  }

  @Override
  public void startup(WorkQueue workQueue) {
    shutdownState.setIsShuttingDown(false);
    for (Source cfg : sources.values()) {
      cfg.start(workQueue);
    }
  }

  /* shutdown() cannot be set as a synchronized method because
   * it may need to wait for pending events to complete;
   * e.g. when enabling the drain of replication events before
   * shutdown.
   *
   * As a rule of thumb for synchronized methods, because they
   * implicitly define a critical section and associated lock,
   * they should never hold waiting for another resource, otherwise
   * the risk of deadlock is very high.
   *
   * See more background about deadlocks, what they are and how to
   * prevent them at: https://en.wikipedia.org/wiki/Deadlock
   */
  @Override
  public int shutdown() {
    shutdownState.setIsShuttingDown(true);

    int discarded = 0;
    for (Source cfg : sources.values()) {
      discarded += cfg.shutdown();
    }
    return discarded;
  }

  @Override
  public boolean isEmpty() {
    return sources.isEmpty();
  }

  @Subscribe
  public synchronized void onReload(List<RemoteConfiguration> sourceConfigurations) {
    if (shutdownState.isShuttingDown()) {
      logger.atWarning().log("Shutting down: configuration reload ignored");
      return;
    }
    try {
      replicationQueue.get().stop();
      sources = allSources(sourceFactory, sourceConfigurations);
      logger.atInfo().log("Configuration reloaded: %d sources", getAll().size());
    } finally {
      replicationQueue.get().start();
    }
  }
}
