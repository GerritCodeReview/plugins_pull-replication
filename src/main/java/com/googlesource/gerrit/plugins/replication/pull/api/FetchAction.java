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

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.gerrit.common.Nullable;
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
import com.google.gerrit.server.git.WorkQueue.Task;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.TransportException;

@Singleton
public class FetchAction implements RestModifyView<ProjectResource, Input> {
  private final FetchCommand command;
  private final DeleteRefCommand deleteRefCommand;
  private final WorkQueue workQueue;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final FetchPreconditions preConditions;
  private final Factory fetchJobFactory;
  private final DeleteRefJob.Factory deleteJobFactory;

  @Inject
  public FetchAction(
      FetchCommand command,
      DeleteRefCommand deleteRefCommand,
      WorkQueue workQueue,
      DynamicItem<UrlFormatter> urlFormatter,
      FetchPreconditions preConditions,
      FetchJob.Factory fetchJobFactory,
      DeleteRefJob.Factory deleteJobFactory) {
    this.command = command;
    this.deleteRefCommand = deleteRefCommand;
    this.workQueue = workQueue;
    this.urlFormatter = urlFormatter;
    this.preConditions = preConditions;
    this.fetchJobFactory = fetchJobFactory;
    this.deleteJobFactory = deleteJobFactory;
  }

  public static class Input {
    public String label;
    public String refName;
    public boolean async;
    public boolean isDelete;
  }

  @AutoValue
  public abstract static class RefInput {
    @Nullable
    public abstract String refName();

    public abstract boolean isDelete();

    public static RefInput create(@Nullable String refName, boolean isDelete) {
      return new AutoValue_FetchAction_RefInput(refName, isDelete);
    }

    public static RefInput create(@Nullable String refName) {
      return new AutoValue_FetchAction_RefInput(refName, false);
    }
  }

  public static class RefInputTypeAdapter extends TypeAdapter<RefInput> {
    @Override
    public void write(JsonWriter out, RefInput value) throws IOException {
      out.beginObject();
      out.name("ref_name").value(value.refName());
      out.name("is_delete").value(value.isDelete());
      out.endObject();
    }

    @Override
    public RefInput read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      in.beginObject();
      String refName = null;
      boolean isDelete = false;

      while (in.hasNext()) {
        String name = in.nextName();
        switch (name) {
          case "ref_name":
            refName = in.nextString();
            break;
          case "is_delete":
            isDelete = in.nextBoolean();
            break;
          default:
            in.skipValue(); // Ignore unknown properties
            break;
        }
      }

      in.endObject();
      return RefInput.create(refName, isDelete);
    }
  }

  public static class BatchInput {
    public String label;
    public Set<RefInput> refInputs;
    public boolean async;

    public static BatchInput fromInput(Input... input) {
      BatchInput batchInput = new BatchInput();
      batchInput.async = input[0].async;
      batchInput.label = input[0].label;
      batchInput.refInputs =
          Stream.of(input)
              .map(i -> RefInput.create(i.refName, i.isDelete))
              .collect(Collectors.toSet());
      return batchInput;
    }

    public Set<String> getNonDeletedRefNames() {
      return refInputs.stream()
          .filter(r -> !r.isDelete())
          .map(RefInput::refName)
          .collect(Collectors.toSet());
    }

    public Set<String> getDeletedRefNames() {
      return refInputs.stream()
          .filter(RefInput::isDelete)
          .map(RefInput::refName)
          .collect(Collectors.toSet());
    }
  }

  @Override
  public Response<?> apply(ProjectResource resource, Input input) throws RestApiException {
    return apply(resource, BatchInput.fromInput(input));
  }

  public Response<?> apply(ProjectResource resource, BatchInput batchInput)
      throws RestApiException {

    if (!preConditions.canCallFetchApi()) {
      throw new AuthException("not allowed to call fetch command");
    }
    try {
      if (Strings.isNullOrEmpty(batchInput.label)) {
        throw new BadRequestException("Source label cannot be null or empty");
      }

      if (batchInput.refInputs.isEmpty()) {
        throw new BadRequestException("Ref-update refname cannot be null or empty");
      }

      for (RefInput input : batchInput.refInputs) {
        if (Strings.isNullOrEmpty(input.refName())) {
          throw new BadRequestException("Ref-update refname cannot be null or empty");
        }
      }

      if (batchInput.async) {
        return applyAsync(resource.getNameKey(), batchInput);
      }
      return applySync(resource.getNameKey(), batchInput);
    } catch (InterruptedException
        | ExecutionException
        | IllegalStateException
        | TimeoutException
        | TransportException e) {
      throw RestApiException.wrap(e.getMessage(), e);
    } catch (RemoteConfigurationMissingException e) {
      throw new UnprocessableEntityException(e.getMessage());
    }
  }

  private Response<?> applySync(Project.NameKey project, BatchInput input)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException, TransportException {
    command.fetchSync(project, input.label, input.getNonDeletedRefNames());

    if (!input.getDeletedRefNames().isEmpty()) {
      deleteRefCommand.deleteRefsSync(project, input.getDeletedRefNames(), input.label);
    }
    return Response.created(input);
  }

  @SuppressWarnings("unchecked")
  private Response.Accepted applyAsync(Project.NameKey project, BatchInput batchInput) {
    WorkQueue.Task<Void> task =
        (Task<Void>)
            workQueue
                .getDefaultQueue()
                .submit(
                    fetchJobFactory.create(
                        project, batchInput, PullReplicationApiRequestMetrics.get()));
    Optional<String> url =
        urlFormatter
            .get()
            .getRestUrl("a/config/server/tasks/" + HexFormat.fromInt(task.getTaskId()));

    if (!batchInput.getDeletedRefNames().isEmpty()) {
      workQueue.getDefaultQueue().submit(deleteJobFactory.create(project, batchInput));
    }
    // We're in a HTTP handler, so must be present.
    checkState(url.isPresent());
    return Response.accepted(url.get());
  }
}
