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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.common.base.Strings;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class ApplyObjectAction implements RestModifyView<ProjectResource, RevisionInput> {

  private final ApplyObjectCommand command;
  private final DeleteRefCommand deleteRefCommand;
  private final FetchPreconditions preConditions;
  private final FetchCommand fetchCommand;

  @Inject
  public ApplyObjectAction(
      ApplyObjectCommand command,
      DeleteRefCommand deleteRefCommand,
      FetchCommand fetchCommand,
      FetchPreconditions preConditions) {
    this.command = command;
    this.deleteRefCommand = deleteRefCommand;
    this.fetchCommand = fetchCommand;
    this.preConditions = preConditions;
  }

  @Override
  public Response<?> apply(ProjectResource resource, RevisionInput input) throws RestApiException {

    if (!preConditions.canCallFetchApi()) {
      throw new AuthException("not allowed to call fetch command");
    }
    try {
      if (Strings.isNullOrEmpty(input.getLabel())) {
        throw new BadRequestException("Source label cannot be null or empty");
      }
      if (Strings.isNullOrEmpty(input.getRefName())) {
        throw new BadRequestException("Ref-update refname cannot be null or empty");
      }

      if (Objects.isNull(input.getRevisionData())) {
        deleteRefCommand.deleteRef(resource.getNameKey(), input.getRefName(), input.getLabel());
        return Response.created(input);
      }

      if (Objects.isNull(input.getRevisionData().getCommitObject())
          || Objects.isNull(input.getRevisionData().getCommitObject().getContent())
          || input.getRevisionData().getCommitObject().getContent().length == 0
          || Objects.isNull(input.getRevisionData().getCommitObject().getType())) {
        throw new BadRequestException("Ref-update commit object cannot be null or empty");
      }

      if (Objects.isNull(input.getRevisionData().getTreeObject())
          || Objects.isNull(input.getRevisionData().getTreeObject().getContent())
          || Objects.isNull(input.getRevisionData().getTreeObject().getType())) {
        throw new BadRequestException("Ref-update tree object cannot be null");
      }
      try {
        command.applyObject(
            resource.getNameKey(), input.getRefName(), input.getRevisionData(), input.getLabel());
        return Response.created(input);
      } catch (MissingParentObjectException e) {
        repLog.warn(
            "Missing parent object. Cannot apply object from {} for project {}, ref name {}. Calling fetch command",
            input.getLabel(),
            resource.getNameKey().get(),
            input.getRefName());
        fetchCommand.fetchSync(resource.getNameKey(), input.getLabel(), input.getRefName());
        return Response.created(input);
      }
    } catch (NumberFormatException
        | IOException
        | InterruptedException
        | ExecutionException
        | RemoteConfigurationMissingException
        | TimeoutException e) {
      throw RestApiException.wrap(e.getMessage(), e);
    } catch (RefUpdateException e) {
      throw new UnprocessableEntityException(e.getMessage());
    }
  }
}
