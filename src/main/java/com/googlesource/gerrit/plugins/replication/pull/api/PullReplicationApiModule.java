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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.gerrit.server.project.ProjectResource.PROJECT_KIND;
import static com.googlesource.gerrit.plugins.replication.pull.api.FetchApiCapability.CALL_FETCH_ACTION;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.inject.Scopes;

public class PullReplicationApiModule extends RestApiModule {
  @Override
  protected void configure() {
    bind(FetchAction.class).in(Scopes.SINGLETON);
    bind(ApplyObjectAction.class).in(Scopes.SINGLETON);
    bind(ProjectDeletionAction.class).in(Scopes.SINGLETON);
    bind(UpdateHeadAction.class).in(Scopes.SINGLETON);
    bind(BatchApplyObjectAction.class).in(Scopes.SINGLETON);
    bind(BatchFetchAction.class).in(Scopes.SINGLETON);

    post(PROJECT_KIND, "fetch").to(FetchAction.class);
    post(PROJECT_KIND, "batch-fetch").to(BatchFetchAction.class);
    post(PROJECT_KIND, "apply-object").to(ApplyObjectAction.class);
    post(PROJECT_KIND, "batch-apply-object").to(BatchApplyObjectAction.class);
    post(PROJECT_KIND, "apply-objects").to(ApplyObjectsAction.class);
    delete(PROJECT_KIND, "delete-project").to(ProjectDeletionAction.class);
    put(PROJECT_KIND, "HEAD").to(UpdateHeadAction.class);

    bind(FetchPreconditions.class).in(Scopes.SINGLETON);
    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(CALL_FETCH_ACTION))
        .to(FetchApiCapability.class);
  }
}
