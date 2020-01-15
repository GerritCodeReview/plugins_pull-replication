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

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class FetchAction implements RestModifyView<ProjectResource, Input> {
  FetchService service;

  @Inject
  public FetchAction(FetchService service) {
    this.service = service;
  }

  public static class Input {
    public String url;
    public String sha1;
  }

  @Override
  public Response<?> apply(ProjectResource resource, Input input)
      throws AuthException, BadRequestException, ResourceConflictException, InterruptedException,
          ExecutionException, URISyntaxException {
    service.fetch(
        resource.getNameKey(),
        new URIish(ReplicationFileBasedConfig.replaceName(input.url, resource.getName(), false)),
        input.sha1);
    return Response.created(input);
  }
}
