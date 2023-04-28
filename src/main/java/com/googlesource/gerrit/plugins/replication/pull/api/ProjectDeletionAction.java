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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.deleteproject.fs.RepositoryDelete;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

class ProjectDeletionAction
    implements RestModifyView<ProjectResource, ProjectDeletionAction.DeleteInput> {
  private static final PluginPermission DELETE_PROJECT =
      new PluginPermission("delete-project", "deleteProject");
  private static final boolean NO_PRESERVE_GIT_REPO = false;
  private static final boolean NO_ARCHIVE = false;

  static class DeleteInput {}

  private final Provider<CurrentUser> userProvider;
  private final GerritConfigOps gerritConfigOps;
  private final PermissionBackend permissionBackend;
  private final RepositoryDelete repositoryDelete;

  @Inject
  ProjectDeletionAction(
      GerritConfigOps gerritConfigOps,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> userProvider,
      RepositoryDelete repositoryDelete) {
    this.gerritConfigOps = gerritConfigOps;
    this.permissionBackend = permissionBackend;
    this.userProvider = userProvider;
    this.repositoryDelete = repositoryDelete;
  }

  @Override
  public Response<?> apply(ProjectResource projectResource, DeleteInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {

    // When triggered internally(for example by consuming stream events) user is not provided
    // and internal user is returned. Project deletion should be always allowed for internal user.
    if (!userProvider.get().isInternalUser()) {
      permissionBackend.user(projectResource.getUser()).check(DELETE_PROJECT);
    }

    Optional<URIish> maybeRepoURI =
        gerritConfigOps.getGitRepositoryURI(String.format("%s.git", projectResource.getName()));

    if (maybeRepoURI.isPresent()) {
      try {
        // reuse repo deletion logic from delete-project plugin, as it can successfully delete
        // the git directories hosted on nfs.
        repositoryDelete.execute(
            projectResource.getNameKey(),
            NO_PRESERVE_GIT_REPO,
            NO_ARCHIVE,
            Optional.empty(),
            DynamicSet.emptySet());
        repLog.info("Deleted local repository {}", projectResource.getName());
        return Response.ok();
      } catch (IOException e) {
        throw new UnprocessableEntityException(
            String.format("Could not delete project %s", projectResource.getName()));
      }
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URI for repo: %s", projectResource.getName()));
  }
}
