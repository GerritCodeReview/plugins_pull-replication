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

package com.googlesource.gerrit.plugins.replication.pull.transport;

import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.pull.BearerTokenProvider;
import com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration;
import java.util.Optional;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;

/**
 * This class is responsible for setting bearer token header for Bearer Token Authentication using
 * {@link org.eclipse.jgit.transport.TransportHttp#setAdditionalHeaders(java.util.Map)} method.
 */
@Singleton
public class TransportProvider {
  private final RemoteConfig remoteConfig;
  private final CredentialsProvider credentialsProvider;
  private final Optional<String> bearerToken;

  @Inject
  public TransportProvider(
      SourceConfiguration sourceConfig,
      CredentialsFactory cpFactory,
      BearerTokenProvider bearerTokenProvider) {
    this.remoteConfig = sourceConfig.getRemoteConfig();
    this.credentialsProvider = cpFactory.create(remoteConfig.getName());
    this.bearerToken = bearerTokenProvider.get();
  }

  public Transport open(Repository local, URIish uri)
      throws NotSupportedException, TransportException {
    Transport tn = Transport.open(local, uri);
    tn.applyConfig(remoteConfig);
    if (tn instanceof TransportHttp && bearerToken.isPresent()) {
      ((TransportHttp) tn)
          .setAdditionalHeaders(ImmutableMap.of(HDR_AUTHORIZATION, "Bearer " + bearerToken.get()));
    } else {
      tn.setCredentialsProvider(credentialsProvider);
    }
    return tn;
  }
}
