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
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import java.util.ArrayList;
import java.util.List;

@Singleton
class BatchApplyObjectAction implements RestModifyView<ProjectResource, List<RevisionInput>> {

  private final ApplyObjectAction applyObjectAction;

  @Inject
  BatchApplyObjectAction(ApplyObjectAction applyObjectAction) {
    this.applyObjectAction = applyObjectAction;
  }

  @Override
  public Response<?> apply(ProjectResource resource, List<RevisionInput> inputs)
      throws RestApiException {

    List<Response<?>> allResponses = new ArrayList<>();
    for (RevisionInput input : inputs) {
      Response<?> individualResponse = applyObjectAction.apply(resource, input);
      allResponses.add(individualResponse);
    }

    return Response.ok(allResponses);
  }
}
