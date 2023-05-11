// Copyright (C) 2020 The Android Open Source Project
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.GerritIsReplica;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ConfigParser;
import com.googlesource.gerrit.plugins.replication.RemoteConfiguration;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class SourceConfigParser implements ConfigParser {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private boolean isReplica;

  @Inject
  SourceConfigParser(@GerritIsReplica Boolean isReplica) {
    this.isReplica = isReplica;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.ConfigParser#parseRemotes(org.eclipse.jgit.lib.Config)
   */
  @Override
  public List<RemoteConfiguration> parseRemotes(Config config) throws ConfigInvalidException {

    if (config.getSections().isEmpty()) {
      logger.atWarning().log("Replication config does not exist or it's empty; not replicating");
      return Collections.emptyList();
    }

    ImmutableList.Builder<RemoteConfiguration> sourceConfigs = ImmutableList.builder();
    for (RemoteConfig c : allFetchRemotes(config)) {
      if (isReplica && c.getURIs().isEmpty()) {
        continue;
      }

      // fetch source has to be specified.
      if (c.getFetchRefSpecs().isEmpty()) {
        throw new ConfigInvalidException(
            String.format("You must specify a valid refSpec for this remote"));
      }

      SourceConfiguration sourceConfig = new SourceConfiguration(c, config);

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

  private static List<RemoteConfig> allFetchRemotes(Config cfg) throws ConfigInvalidException {

    Set<String> names = cfg.getSubsections("remote");
    List<RemoteConfig> result = Lists.newArrayListWithCapacity(names.size());
    for (String name : names) {
      try {
        final RemoteConfig remoteConfig = new RemoteConfig(cfg, name);
        if (!remoteConfig.getFetchRefSpecs().isEmpty()) {
          result.add(remoteConfig);
        } else {
          logger.atFine().log(
              "Skip loading of remote [remote \"%s\"], since it has no 'fetch' configuration",
              name);
        }
      } catch (URISyntaxException e) {
        throw new ConfigInvalidException(
            String.format("remote %s has invalid URL in %s", name, cfg));
      }
    }
    return result;
  }
}
