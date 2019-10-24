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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.RemoteConfiguration;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigValidator;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class SourcesCollection implements ReplicationSources, ReplicationConfigValidator {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Source.Factory sourceFactory;
  private volatile List<Source> sources;
  private boolean shuttingDown;

  @Inject
  public SourcesCollection(
      ReplicationFileBasedConfig replicationConfig, Source.Factory sourceFactory, EventBus eventBus)
      throws ConfigInvalidException {
    this.sourceFactory = sourceFactory;
    this.sources = allSources(sourceFactory, validateConfig(replicationConfig));
    eventBus.register(this);
  }

  @Override
  public List<Source> getAll() {
    return sources.stream().filter(Objects::nonNull).collect(toList());
  }

  private List<Source> allSources(
      Source.Factory sourceFactory, List<RemoteConfiguration> sourceConfigurations) {
    return sourceConfigurations.stream()
        .filter((c) -> c instanceof SourceConfiguration)
        .map((c) -> (SourceConfiguration) c)
        .map(sourceFactory::create)
        .collect(toList());
  }

  @Override
  public void startup(WorkQueue workQueue) {
    shuttingDown = false;
    for (Source cfg : sources) {
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
    synchronized (this) {
      shuttingDown = true;
    }

    int discarded = 0;
    for (Source cfg : sources) {
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
    if (shuttingDown) {
      logger.atWarning().log("Shutting down: configuration reload ignored");
      return;
    }

    sources = allSources(sourceFactory, sourceConfigurations);
    logger.atInfo().log("Configuration reloaded: %d sources", getAll().size());
  }

  @Override
  public List<RemoteConfiguration> validateConfig(ReplicationFileBasedConfig newConfig)
      throws ConfigInvalidException {

    try {
      newConfig.getConfig().load();
    } catch (IOException e) {
      throw new ConfigInvalidException(
          String.format("Cannot read %s: %s", newConfig.getConfig().getFile(), e.getMessage()), e);
    }

    ImmutableList.Builder<RemoteConfiguration> sourceConfigs = ImmutableList.builder();
    for (RemoteConfig c : allFetchRemotes(newConfig.getConfig())) {
      if (c.getURIs().isEmpty()) {
        continue;
      }

      // fetch source has to be specified.
      if (c.getFetchRefSpecs().isEmpty()) {
        throw new ConfigInvalidException(
            String.format("You must specify a valid refSpec for this remote"));
      }

      SourceConfiguration sourceConfig = new SourceConfiguration(c, newConfig.getConfig());

      if (!sourceConfig.isSingleProjectMatch()) {
        for (URIish u : c.getURIs()) {
          if (u.getPath() == null || !u.getPath().contains("${name}")) {
            throw new ConfigInvalidException(
                String.format("remote.%s.url \"%s\" lacks ${name} placeholder", c.getName(), u));
          }
        }
      }
      sourceConfigs.add(sourceConfig);
    }
    return sourceConfigs.build();
  }

  private static List<RemoteConfig> allFetchRemotes(FileBasedConfig cfg)
      throws ConfigInvalidException {

    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        final RemoteConfig remoteConfig = new RemoteConfig(cfg, name);
        if (!remoteConfig.getFetchRefSpecs().isEmpty()) {
          result.add(remoteConfig);
        } else {
          logger.atWarning().log(
              "Skip loading of remote [remote \"%s\"], since it has no 'fetch' configuration",
              name);
        }
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(
            String.format("remote %s has invalid URL in %s", name, cfg.getFile()));
      }
    }
    return result;
  }
}
