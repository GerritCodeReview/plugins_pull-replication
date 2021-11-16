package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.deleteproject.DeleteProjectCapability;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;

public class ProjectDeletionAction implements RestModifyView <ProjectResource, Input> {
    private final GerritConfigOps gerritConfigOps;
    private final PermissionBackend permissionBackend;

    @Inject
    ProjectDeletionAction(GerritConfigOps gerritConfigOps, PermissionBackend permissionBackend) {
        this.gerritConfigOps = gerritConfigOps;
        this.permissionBackend = permissionBackend;
    }

    @Override
    public Response <?> apply(ProjectResource resource, Input input) throws AuthException, BadRequestException, ResourceConflictException, Exception {

        permissionBackend
                .user(resource.getUser())
                .project(resource.getNameKey()).check(DELETE_PROJECT);

        return null;
    }
}
