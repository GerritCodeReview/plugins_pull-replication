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

import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

public class ProjectDeletionAction
    implements RestModifyView<ProjectResource, ProjectDeletionAction.DeleteInput> {
  private static final PluginPermission DELETE_PROJECT =
      new PluginPermission("delete-project", "deleteProject");

  public static class DeleteInput {}

  private final GerritConfigOps gerritConfigOps;
  private final PermissionBackend permissionBackend;

  @Inject
  ProjectDeletionAction(GerritConfigOps gerritConfigOps, PermissionBackend permissionBackend) {
    this.gerritConfigOps = gerritConfigOps;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<?> apply(ProjectResource projectResource, DeleteInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {

    permissionBackend.user(projectResource.getUser()).check(DELETE_PROJECT);

    Optional<URIish> maybeRepoURI =
        gerritConfigOps.getGitRepositoryURI(String.format("%s.git", projectResource.getName()));

    if (maybeRepoURI.isPresent()) {
      if (new LocalFS(maybeRepoURI.get()).deleteProject(projectResource.getNameKey())) {
        return Response.ok();
      }
      throw new UnprocessableEntityException(
          String.format("Could not delete project %s", projectResource.getName()));
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URI for repo: %s", projectResource.getName()));
  }
}
