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

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import java.net.HttpURLConnection;
import java.util.Optional;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class UpdateHeadAction implements RestModifyView<ProjectResource, HeadInput> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final GerritConfigOps gerritConfigOps;
  private final FetchPreconditions preconditions;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  @Inject
  UpdateHeadAction(
      GerritConfigOps gerritConfigOps,
      FetchPreconditions preconditions,
      DynamicItem<EventDispatcher> eventDispatcher) {
    this.gerritConfigOps = gerritConfigOps;
    this.preconditions = preconditions;
    this.eventDispatcher = eventDispatcher;
  }

  @Override
  public Response<?> apply(ProjectResource projectResource, HeadInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {
    Response<String> res = null;

    if (input == null || Strings.isNullOrEmpty(input.ref)) {
      throw new BadRequestException("ref required");
    }
    String ref = RefNames.fullName(input.ref);

    if (!preconditions.canCallUpdateHeadApi(projectResource.getNameKey(), ref)) {
      throw new AuthException("Update head not permitted");
    }

    // TODO: the .git suffix should not be added here, but rather it should be
    //  dealt with by the caller, honouring the naming style from the
    //  replication.config (Issue 15221)
    Optional<URIish> maybeRepo =
        gerritConfigOps.getGitRepositoryURI(String.format("%s.git", projectResource.getName()));

    try {
      if (maybeRepo.isPresent()) {
        if (new LocalFS(maybeRepo.get()).updateHead(projectResource.getNameKey(), ref)) {
          return res = Response.ok(ref);
        }
        throw new UnprocessableEntityException(
            String.format(
                "Could not update HEAD of repo %s to ref %s", projectResource.getName(), ref));
      }
    } finally {
      fireEvent(
          projectResource.getNameKey(),
          res != null && res.statusCode() == HttpURLConnection.HTTP_OK);
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URL for repo: %s", projectResource.getName()));
  }

  private void fireEvent(Project.NameKey projectName, boolean succeeded) {
    try {
      Context.setLocalEvent(true);
      eventDispatcher
          .get()
          .postEvent(
              new FetchRefReplicatedEvent(
                  projectName.get(),
                  RefNames.HEAD,
                  "", // TODO: the remote label is not passed as parameter, hence cannot be
                      // propagated to the event
                  succeeded
                      ? ReplicationState.RefFetchResult.SUCCEEDED
                      : ReplicationState.RefFetchResult.FAILED,
                  RefUpdate.Result.FORCED));
    } catch (PermissionBackendException e) {
      logger.atSevere().withCause(e).log(
          "Cannot post event for refs/meta/config on project %s", projectName);
    } finally {
      Context.unsetLocalEvent();
    }
  }
}
