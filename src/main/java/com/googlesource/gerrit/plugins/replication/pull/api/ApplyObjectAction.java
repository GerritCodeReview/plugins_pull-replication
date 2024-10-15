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
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.PreconditionFailedException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingLatestPatchSetException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import java.util.Objects;
import org.eclipse.jgit.lib.RefUpdate;

@Singleton
public class ApplyObjectAction implements RestModifyView<ProjectResource, RevisionInput> {

  private final ApplyObjectCommand applyObjectCommand;
  private final FetchPreconditions preConditions;

  @Inject
  public ApplyObjectAction(
      ApplyObjectCommand applyObjectCommand, FetchPreconditions preConditions) {
    this.applyObjectCommand = applyObjectCommand;
    this.preConditions = preConditions;
  }

  @Override
  public Response<?> apply(ProjectResource resource, RevisionInput input) throws RestApiException {

    if (!preConditions.canCallFetchApi()) {
      throw new AuthException("Not allowed to call fetch command");
    }
    if (Strings.isNullOrEmpty(input.getLabel())) {
      throw new BadRequestException("Source label cannot be null or empty");
    }
    if (Strings.isNullOrEmpty(input.getRefName())) {
      throw new BadRequestException("Ref-update refname cannot be null or empty");
    }
    if (Objects.isNull(input.getRevisionData())) {
      throw new BadRequestException("Revision data cannot be null");
    }

    try {
      repLog.info(
          "Apply object API from {} for {}:{} - {}",
          input.getLabel(),
          resource.getNameKey(),
          input.getRefName(),
          input.getRevisionData());

      try {
        input.validate();
      } catch (IllegalArgumentException e) {
        BadRequestException bre =
            new BadRequestException("Ref-update with invalid input: " + e.getMessage(), e);
        repLog.error(
            "Apply object API *FAILED* from {} for {}:{} - {}",
            input.getLabel(),
            resource.getNameKey(),
            input.getRefName(),
            input.getRevisionData(),
            bre);
        throw bre;
      }

      applyObjectCommand.applyObject(
          resource.getNameKey(),
          input.getRefName(),
          input.getRevisionData(),
          input.getLabel(),
          input.getEventCreatedOn());
      return Response.created();
    } catch (MissingParentObjectException e) {
      repLog.error(
          "Apply object API *FAILED* from {} for {}:{} - {}",
          input.getLabel(),
          resource.getNameKey(),
          input.getRefName(),
          input.getRevisionData(),
          e);
      throw new ResourceConflictException(e.getMessage(), e);
    } catch (NumberFormatException | IOException e) {
      repLog.error(
          "Apply object API *FAILED* from {} for {}:{} - {}",
          input.getLabel(),
          resource.getNameKey(),
          input.getRefName(),
          input.getRevisionData(),
          e);
      throw RestApiException.wrap(e.getMessage(), e);
    } catch (RefUpdateException e) {
      if (RefNames.isRefsDraftsComments(input.getRefName())
          && e.getResult().equals(RefUpdate.Result.REJECTED)) {
        repLog.info(
            "Apply object API *REJECTED* from {} for {}:{} - {}",
            input.getLabel(),
            resource.getNameKey(),
            input.getRefName(),
            input.getRevisionData());
      } else {
        repLog.error(
            "Apply object API *FAILED* from {} for {}:{} - {}",
            input.getLabel(),
            resource.getNameKey(),
            input.getRefName(),
            input.getRevisionData(),
            e);
      }
      throw new UnprocessableEntityException(e.getMessage());
    } catch (MissingLatestPatchSetException e) {
      repLog.error(
          "Apply object API *FAILED* from {} for {}:{} - {}",
          input.getLabel(),
          resource.getNameKey(),
          input.getRefName(),
          input.getRevisionData(),
          e);
      throw new PreconditionFailedException(e.getMessage());
    }
  }
}
