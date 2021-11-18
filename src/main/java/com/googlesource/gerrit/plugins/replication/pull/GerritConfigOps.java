// Copyright (C) 2021 The Android Open Source Project
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
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Singleton
public class GerritConfigOps {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Path basePath;

  @Inject
  public GerritConfigOps(@GerritServerConfig Config cfg, SitePaths sitePath) {
    this.basePath = sitePath.resolve(cfg.getString("gerrit", null, "basePath"));
  }

  public Optional<URIish> getGitRepositoryURI(String projectName) {
    URIish uri;

    try {
      uri = new URIish("file://" + basePath + "/" + projectName);
      return Optional.of(uri);
    } catch (URISyntaxException e) {
      logger.atSevere().withCause(e).log("Unsupported URI for project " + projectName);
    }

    return Optional.empty();
  }

  public Optional<URIish> getGitRepositoryURIIfExists(String projectName) {
    if (Files.exists(basePath.resolve(projectName))) {
      return getGitRepositoryURI(projectName);
    }
    return Optional.empty();
  }
}
