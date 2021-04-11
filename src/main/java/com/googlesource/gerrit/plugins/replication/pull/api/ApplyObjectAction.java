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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class ApplyObjectAction implements RestModifyView<ProjectResource, RevisionInput> {

  private final ApplyObjectCommand command;
  private final WorkQueue workQueue;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final FetchPreconditions preConditions;

  @Inject
  public ApplyObjectAction(
      ApplyObjectCommand command,
      WorkQueue workQueue,
      DynamicItem<UrlFormatter> urlFormatter,
      FetchPreconditions preConditions) {
    this.command = command;
    this.workQueue = workQueue;
    this.urlFormatter = urlFormatter;
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
        throw new BadRequestException("Ref-update revision data cannot be null or empty");
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

      if (input.isAsync()) {
        return applyAsync(resource.getNameKey(), input);
      }
      return applySync(resource.getNameKey(), input);
    } catch (MissingParentObjectException e) {
      throw new ResourceConflictException(e.getMessage(), e);
    } catch (NumberFormatException | IOException e) {
      throw RestApiException.wrap(e.getMessage(), e);
    } catch (RefUpdateException e) {
      throw new UnprocessableEntityException(e.getMessage());
    }
  }

  private Response<?> applySync(Project.NameKey project, RevisionInput input)
      throws NumberFormatException, IOException, RefUpdateException, MissingParentObjectException {
    command.applyObject(project, input.getRefName(), input.getRevisionData(), input.getLabel());
    return Response.created(input);
  }

  private Response.Accepted applyAsync(Project.NameKey project, RevisionInput input) {
    @SuppressWarnings("unchecked")
    WorkQueue.Task<Void> task =
        (WorkQueue.Task<Void>)
            workQueue.getDefaultQueue().submit(new ApplyObjectJob(command, project, input));
    Optional<String> url =
        urlFormatter
            .get()
            .getRestUrl("a/config/server/tasks/" + HexFormat.fromInt(task.getTaskId()));
    // We're in a HTTP handler, so must be present.
    checkState(url.isPresent());
    return Response.accepted(url.get());
  }

  private static class ApplyObjectJob implements Runnable {
    private static final FluentLogger log = FluentLogger.forEnclosingClass();

    private ApplyObjectCommand command;
    private Project.NameKey project;
    private RevisionInput input;

    public ApplyObjectJob(
        ApplyObjectCommand command, Project.NameKey project, RevisionInput input) {
      this.command = command;
      this.project = project;
      this.input = input;
    }

    @Override
    public void run() {
      try {
        command.applyObject(project, input.getRefName(), input.getRevisionData(), input.getLabel());
      } catch (IOException | RefUpdateException | MissingParentObjectException e) {
        log.atSevere().withCause(e).log(
            "Exception during the applyObject call for project %s and ref name %s",
            project.get(),
            input.getRefName());
      }
    }
  }
}
