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

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.deleteproject.DeletePreconditions;
import com.googlesource.gerrit.plugins.deleteproject.DeleteProject;

@Singleton
class ProjectDeletionAction
    implements RestModifyView<ProjectResource, ProjectDeletionAction.DeleteInput> {

  static class DeleteInput {}

  private final DeleteProject deleteProject;
  private final DeletePreconditions preConditions;

  @Inject
  ProjectDeletionAction(DeleteProject deleteProject, DeletePreconditions preConditions) {
    this.deleteProject = deleteProject;
    this.preConditions = preConditions;
  }

  @Override
  public Response<?> apply(ProjectResource projectResource, DeleteInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {
    DeleteProject.Input deleteProjectInput = new DeleteProject.Input();
    deleteProjectInput.force = true;
    deleteProjectInput.preserve = false;

    preConditions.assertDeletePermission(projectResource);
    preConditions.assertCanBeDeleted(projectResource, deleteProjectInput);

    deleteProject.doDelete(projectResource, deleteProjectInput);
    return Response.ok();
  }
}
