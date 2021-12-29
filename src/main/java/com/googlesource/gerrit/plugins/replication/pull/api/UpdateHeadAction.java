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

import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.restapi.project.SetHead;
import com.google.inject.Inject;

public class UpdateHeadAction implements RestModifyView<ProjectResource, HeadInput> {

  private final SetHead setHead;

  @Inject
  public UpdateHeadAction(SetHead setHead) {
    this.setHead = setHead;
  }

  @Override
  public Response<?> apply(ProjectResource resource, HeadInput input)
      throws AuthException, BadRequestException, ResourceConflictException, Exception {
    return setHead.apply(resource, input);
  }
}
