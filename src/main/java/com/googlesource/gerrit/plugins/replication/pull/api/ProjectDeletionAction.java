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
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.deleteproject.cache.CacheDeleteHandler;
import com.googlesource.gerrit.plugins.deleteproject.fs.RepositoryDelete;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.transport.URIish;

@Singleton
class ProjectDeletionAction
    implements RestModifyView<ProjectResource, ProjectDeletionAction.DeleteInput> {
  private static final PluginPermission DELETE_PROJECT =
      new PluginPermission("delete-project", "deleteProject");

  static class DeleteInput {}

  private final Provider<CurrentUser> userProvider;
  private final GerritConfigOps gerritConfigOps;
  private final PermissionBackend permissionBackend;
  private final RepositoryDelete repositoryDelete;
  private final CacheDeleteHandler cacheDeleteHandler;

  @Inject
  ProjectDeletionAction(
      GerritConfigOps gerritConfigOps,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> userProvider,
      RepositoryDelete repositoryDelete,
      CacheDeleteHandler cacheDeleteHandler) {
    this.gerritConfigOps = gerritConfigOps;
    this.permissionBackend = permissionBackend;
    this.userProvider = userProvider;
    this.repositoryDelete = repositoryDelete;
    this.cacheDeleteHandler = cacheDeleteHandler;
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
        repositoryDelete.execute(projectResource.getNameKey());
        // delete the project from the local project cache, otherwise future ops
        // will fail as the replica will think that the project still exists locally.
        cacheDeleteHandler.delete(projectResource.getProjectState().getProject());
        repLog.info(
            "Deleted local repository {} and removed it from the local project cache",
            projectResource.getName());
        return Response.ok();
      } catch (RepositoryNotFoundException e) {
        throw new ResourceNotFoundException(
            String.format("Repository %s not found", projectResource.getName()), e);
      } catch (IOException e) {
        throw new UnprocessableEntityException(
            String.format("Could not delete project %s", projectResource.getName()));
      }
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URI for repo: %s", projectResource.getName()));
  }
}
