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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

public class JGitFetch implements Fetch {
  private final RemoteConfig config;
  private final CredentialsProvider credentialsProvider;
  URIish uri;
  Repository git;

  @Inject
  public JGitFetch(
      RemoteConfig config,
      CredentialsFactory cpFactory,
      @Assisted URIish uri,
      @Assisted Repository git) {
    this.config = config;
    this.credentialsProvider = cpFactory.create(config.getName());
    this.uri = uri;
    this.git = git;
  }

  @Override
  public List<RefUpdateState> fetch(List<RefSpec> refs) throws IOException {
    FetchResult res;
    try (Transport tn = Transport.open(git, uri)) {
      res = fetchVia(tn, refs);
    }
    return res.getTrackingRefUpdates().stream()
        .map(value -> new RefUpdateState(value.getRemoteName(), value.getResult()))
        .collect(Collectors.toList());
  }

  private FetchResult fetchVia(Transport tn, List<RefSpec> fetchRefSpecs) throws IOException {
    tn.applyConfig(config);
    tn.setCredentialsProvider(credentialsProvider);

    repLog.info("Fetch references {} from {}", fetchRefSpecs, uri);
    return tn.fetch(NullProgressMonitor.INSTANCE, fetchRefSpecs);
  }
}
