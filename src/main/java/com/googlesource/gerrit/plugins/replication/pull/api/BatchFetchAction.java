// Copyright (C) 2023 The Android Open Source Project
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

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.BatchInput;

@Singleton
public class BatchFetchAction implements RestModifyView<ProjectResource, BatchInput> {
  private final FetchAction fetchAction;

  @Inject
  public BatchFetchAction(FetchAction fetchAction) {
    this.fetchAction = fetchAction;
  }

  @Override
  public Response<?> apply(ProjectResource resource, BatchInput batchInput)
      throws RestApiException {
    return Response.ok(fetchAction.apply(resource, batchInput));
  }
}
