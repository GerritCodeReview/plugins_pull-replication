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
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.servlet.ServletModule;

public class HttpModule extends ServletModule {
  private boolean isReplica;

  @Inject
  public HttpModule(@GerritIsReplica Boolean isReplica) {
    this.isReplica = isReplica;
  }

  @Override
  protected void configureServlets() {
    if (isReplica) {
      DynamicSet.bind(binder(), AllRequestFilter.class)
          .to(PullReplicationFilter.class)
          .in(Scopes.SINGLETON);
    } else {
      serveRegex("/init-project/.*$").with(ProjectInitializationAction.class);
    }

    DynamicSet.bind(binder(), AllRequestFilter.class)
        .to(PullReplicationApiMetricsFilter.class)
        .in(Scopes.SINGLETON);
  }
}
