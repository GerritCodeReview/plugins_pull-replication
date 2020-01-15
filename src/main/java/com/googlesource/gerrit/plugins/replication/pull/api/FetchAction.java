// Copyright (C) 2020 The Android Open Source Project
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
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import java.util.concurrent.ExecutionException;

public class FetchAction implements RestModifyView<ProjectResource, Input> {
  FetchService service;

  @Inject
  public FetchAction(FetchService service) {
    this.service = service;
  }

  public static class Input {
    public String label;
    public String object_id;
  }

  @Override
  public Response<?> apply(ProjectResource resource, Input input) throws RestApiException {
    try {
      if (Strings.isNullOrEmpty(input.label)) {
        throw new BadRequestException("Source label cannot be null or empty");
      }

      if (Strings.isNullOrEmpty(input.object_id)) {
        throw new BadRequestException("Ref-update objectId cannot be null or empty");
      }

      service.fetch(resource.getNameKey(), input.label, input.object_id);

      return Response.created(input);
    } catch (InterruptedException | ExecutionException | IllegalStateException e) {
      throw new RestApiException(e.getMessage(), e);
    }
  }
}
