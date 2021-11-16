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
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import java.util.Optional;
import org.eclipse.jgit.transport.URIish;

public class UpdateHeadAction implements RestModifyView<ProjectResource, HeadInput> {
  private final GerritConfigOps gerritConfigOps;
  private final PermissionBackend permissionBackend;

  @Inject
  UpdateHeadAction(GerritConfigOps gerritConfigOps, PermissionBackend permissionBackend) {
    this.gerritConfigOps = gerritConfigOps;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<?> apply(ProjectResource projectResource, HeadInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {
    if (input == null || Strings.isNullOrEmpty(input.ref)) {
      throw new BadRequestException("ref required");
    }
    String ref = RefNames.fullName(input.ref);

    permissionBackend
        .user(projectResource.getUser())
        .project(projectResource.getNameKey())
        .ref(ref)
        .check(RefPermission.SET_HEAD);

    // TODO: the .git suffix should not be added here, but rather it should be
    //  dealt with by the caller, honouring the naming style from the
    //  replication.config (Issue 15221)
    Optional<URIish> maybeRepo =
        gerritConfigOps.getGitRepositoryURI(String.format("%s.git", projectResource.getName()));

    if (maybeRepo.isPresent()) {
      if (new LocalFS(maybeRepo.get()).updateHead(projectResource.getNameKey(), ref)) {
        return Response.ok(ref);
      }
      throw new UnprocessableEntityException(
          String.format(
              "Could not update HEAD of repo %s to ref %s", projectResource.getName(), ref));
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URL for repo: %s", projectResource.getName()));
  }
}
