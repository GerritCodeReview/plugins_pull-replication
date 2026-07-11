// Copyright (C) 2026 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Batches ref-updates that each carry more than one object (e.g. a whole meta-ref objects' ancestry),
 * applying every ref's revisions as a single ref-update, via {@link ApplyObjectsAction}.
 */
@Singleton
class BatchApplyObjectsAction implements RestModifyView<ProjectResource, List<RevisionsInput>> {

  private final ApplyObjectsAction applyObjectsAction;

  @Inject
  BatchApplyObjectsAction(ApplyObjectsAction applyObjectsAction) {
    this.applyObjectsAction = applyObjectsAction;
  }

  @Override
  public Response<?> apply(ProjectResource resource, List<RevisionsInput> inputs)
      throws RestApiException {

    repLog.info(
        "Batch apply objects API from {} for refs {}",
        resource.getNameKey(),
        inputs.stream().map(RevisionsInput::getRefName).collect(Collectors.joining(",")));

    List<Response<?>> allResponses = new ArrayList<>();
    for (RevisionsInput refRevisionsInput : inputs) {
      Response<?> individualResponse = applyObjectsAction.apply(resource, refRevisionsInput);
      allResponses.add(individualResponse);
    }

    return Response.ok(allResponses);
  }
}
