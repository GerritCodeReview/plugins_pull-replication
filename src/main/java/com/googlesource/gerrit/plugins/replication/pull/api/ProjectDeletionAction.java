package com.googlesource.gerrit.plugins.replication.pull.api;

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
  static class DeleteInput {}

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

    // TODO Need to check for permissions. The problem is that the DELETE capabilities are not
    // exposed by the delete-project plugin
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
