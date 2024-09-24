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
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefSpec;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.TransportException;

@Singleton
public class FetchAction implements RestModifyView<ProjectResource, Input> {
  private final FetchCommand command;
  private final WorkQueue workQueue;
  private final DynamicItem<UrlFormatter> urlFormatter;
  private final FetchPreconditions preConditions;
  private final Factory fetchJobFactory;

  @Inject
  public FetchAction(
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

  public static class Input {
    public String label;
    public String refName;
    public boolean async;
    public boolean isDelete;
  }

  @AutoValue
  public abstract static class RefInput {
    public static final Predicate<RefInput> IS_DELETE = RefInput::isDelete;

    @Nullable
    @SerializedName("ref_name")
    public abstract String refName();

    @SerializedName("is_delete")
    public abstract boolean isDelete();

    public static RefInput create(@Nullable String refName, boolean isDelete) {
      return new AutoValue_FetchAction_RefInput(refName, isDelete);
    }

    public static RefInput create(@Nullable String refName) {
      return new AutoValue_FetchAction_RefInput(refName, false);
    }

    public static TypeAdapter<RefInput> typeAdapter(Gson gson) {
      return new AutoValue_FetchAction_RefInput.GsonTypeAdapter(gson);
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

<<<<<<< PATCH SET (3a3566 Delete refs as part of the replication queue execution)
    public Set<FetchRefSpec> getRefSpecs() {
      return refInputs.stream()
          .map(
              ri ->
                  ri.isDelete()
                      ? FetchRefSpec.ofRef(":" + ri.refName())
                      : FetchRefSpec.ofRef(ri.refName()))
          .collect(Collectors.toSet());
||||||| BASE
    private Stream<String> getFilteredRefNames(Predicate<RefInput> filterFunc) {
      return refInputs.stream().filter(filterFunc).map(RefInput::refName);
    }

    private Stream<FetchRefSpec> getFilteredRefSpecs(Predicate<RefInput> filterFunc) {
      return getFilteredRefNames(filterFunc).map(FetchRefSpec::ofRef);
    }

    public Set<FetchRefSpec> getNonDeletedRefSpecs() {
      return getFilteredRefSpecs(RefInput.IS_DELETE.negate()).collect(Collectors.toSet());
    }

    public boolean hasDeletedRefSpecs() {
      return refInputs.stream().anyMatch(RefInput.IS_DELETE);
    }

    public Set<String> getDeletedRefNames() {
      return getFilteredRefNames(RefInput.IS_DELETE).collect(Collectors.toSet());
=======
    private Stream<String> getFilteredRefNames(Predicate<RefInput> filterFunc) {
      return refInputs.stream().filter(filterFunc).map(RefInput::refName);
    }

    private Stream<FetchRefSpec> getFilteredRefSpecs(Predicate<RefInput> filterFunc) {
      return getFilteredRefNames(filterFunc).map(FetchRefSpec::fromRef);
    }

    public Set<FetchRefSpec> getNonDeletedRefSpecs() {
      return getFilteredRefSpecs(RefInput.IS_DELETE.negate()).collect(Collectors.toSet());
    }

    public boolean hasDeletedRefSpecs() {
      return refInputs.stream().anyMatch(RefInput.IS_DELETE);
    }

    public Set<String> getDeletedRefNames() {
      return getFilteredRefNames(RefInput.IS_DELETE).collect(Collectors.toSet());
>>>>>>> BASE      (a1d06d Introduce FetchRefSpec over the whole replication queue proc)
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
    command.fetchSync(project, input.label, input.getRefSpecs());
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
    // We're in a HTTP handler, so must be present.
    checkState(url.isPresent());
    return Response.accepted(url.get());
  }
}
