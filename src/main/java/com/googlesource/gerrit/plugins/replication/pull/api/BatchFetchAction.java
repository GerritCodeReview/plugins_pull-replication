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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.BatchFetchAction.Inputs;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class BatchFetchAction implements RestModifyView<ProjectResource, Inputs> {
  private final FetchCommand command;
  private final WorkQueue workQueue;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final FetchPreconditions preConditions;
  private final Factory fetchJobFactory;

  @Inject
  public BatchFetchAction(
      FetchCommand command,
      WorkQueue workQueue,
      DynamicItem<UrlFormatter> urlFormatter,
      FetchPreconditions preConditions,
      FetchJob.Factory fetchJobFactory) {
    this.command = command;
    this.workQueue = workQueue;
    this.urlFormatter = urlFormatter;
    this.preConditions = preConditions;
    this.fetchJobFactory = fetchJobFactory;
  }

  public static class Inputs {
    public String label;
    public List<String> refNames;
    public boolean async;
  }

  @Override
  public Response<?> apply(ProjectResource resource, Inputs inputs) throws RestApiException {

    if (!preConditions.canCallFetchApi()) {
      throw new AuthException("not allowed to call fetch command");
    }
    try {

      if (Strings.isNullOrEmpty(inputs.label)) {
        throw new BadRequestException("Source label cannot be null or empty");
      }

      if (inputs.refNames == null
          || inputs.refNames.isEmpty()
          || inputs.refNames.stream().anyMatch(Strings::isNullOrEmpty)) {
        throw new BadRequestException("Ref-update refname cannot be null or empty");
      }

      if (inputs.async) {
        return applyAsync(resource.getNameKey(), inputs);
      }
      return applySync(resource.getNameKey(), inputs);
    } catch (InterruptedException
        | ExecutionException
        | IllegalStateException
        | TimeoutException e) {
      throw RestApiException.wrap(e.getMessage(), e);
    } catch (RemoteConfigurationMissingException e) {
      throw new UnprocessableEntityException(e.getMessage());
    }
  }

  private Response<?> applySync(Project.NameKey project, Inputs inputs)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    command.fetchSync(project, inputs);
    return Response.created(inputs);
  }

  private Response.Accepted applyAsync(Project.NameKey project, Inputs inputs) {
    @SuppressWarnings("unchecked")
    WorkQueue.Task<Void> task =
        (WorkQueue.Task<Void>)
            workQueue
                .getDefaultQueue()
                .submit(
                    fetchJobFactory.create(
                        project, inputs, PullReplicationApiRequestMetrics.get()));
    Optional<String> url =
        urlFormatter
            .get()
            .getRestUrl("a/config/server/tasks/" + HexFormat.fromInt(task.getTaskId()));
    // We're in a HTTP handler, so must be present.
    checkState(url.isPresent());
    return Response.accepted(url.get());
  }
}
