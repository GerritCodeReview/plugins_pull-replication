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

package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.server.config.GerritIsReplica;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import com.google.inject.servlet.ServletModule;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;

public class HttpModule extends ServletModule {
  private boolean isReplica;
  private final Optional<String> bearerToken;

  @Inject
  public HttpModule(@GerritIsReplica Boolean isReplica, @GerritServerConfig Config gerritConfig) {
    this.isReplica = isReplica;
    this.bearerToken = Optional.ofNullable(gerritConfig.getString("auth", null, "bearerToken"));
  }

  @Override
  protected void configureServlets() {
    DynamicSet.bind(binder(), AllRequestFilter.class)
        .to(PullReplicationApiMetricsFilter.class)
        .in(Scopes.SINGLETON);

    bearerToken.ifPresent(
        bt -> {
          bind(String.class).annotatedWith(Names.named("BearerToken")).toInstance(bt);
          DynamicSet.bind(binder(), AllRequestFilter.class)
              .to(BearerAuthenticationFilter.class)
              .in(Scopes.SINGLETON);
        });

    if (isReplica) {
      DynamicSet.bind(binder(), AllRequestFilter.class)
          .to(PullReplicationFilter.class)
          .in(Scopes.SINGLETON);
    } else {
      serveRegex("/init-project/.*$").with(ProjectInitializationAction.class);
    }
  }
}
