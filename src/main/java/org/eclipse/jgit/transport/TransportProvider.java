// Copyright (C) 2022 The Android Open Source Project
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

package org.eclipse.jgit.transport;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.pull.BearerTokenProvider;
import com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration;
import java.util.Optional;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Repository;

@Singleton
public class TransportProvider {
  private final RemoteConfig config;
  private final CredentialsProvider credentialsProvider;
  private final BearerTokenProvider bearerTokenProvider;

  @Inject
  public TransportProvider(
      SourceConfiguration sourceConfig,
      CredentialsFactory cpFactory,
      BearerTokenProvider bearerTokenProvider) {
    this.config = sourceConfig.getRemoteConfig();
    this.credentialsProvider = cpFactory.create(config.getName());
    this.bearerTokenProvider = bearerTokenProvider;
  }

  public Transport open(Repository local, URIish uri)
      throws NotSupportedException, TransportException {
    Optional<String> bearerToken = bearerTokenProvider.get();

    if (bearerToken.isPresent() && TransportHttpWithBearerToken.canHandle(uri)) {
      Transport tn = new TransportHttpWithBearerToken(local, uri, bearerToken.get());
      tn.applyConfig(config);
      return tn;
    } else {
      Transport tn = Transport.open(local, uri);
      tn.applyConfig(config);
      tn.setCredentialsProvider(credentialsProvider);
      return tn;
    }
  }
}
