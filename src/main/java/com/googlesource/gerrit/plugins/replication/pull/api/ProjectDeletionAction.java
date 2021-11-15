package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;

public class ProjectDeletionAction implements RestModifyView <ProjectResource, Input> {

    ProjectDeletionAction() {

    }

    @Override
    public Response <?> apply(ProjectResource resource, Input input) throws AuthException, BadRequestException, ResourceConflictException, Exception {
        return null;
    }
}
