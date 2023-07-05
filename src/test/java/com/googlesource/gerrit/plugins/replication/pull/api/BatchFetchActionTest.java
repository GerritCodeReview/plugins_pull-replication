/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.replication.pull.api.BatchFetchAction.Inputs;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.git.WorkQueue.Task;
import com.google.gerrit.server.project.ProjectResource;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class BatchFetchActionTest {

  BatchFetchAction batchFetchAction;
  String label = "instance-2-label";
  String master = "refs/heads/master";
  String test = "refs/heads/test";
  List<String> inputRefs = ImmutableList.of(master, test);

  String location = "http://gerrit-host/a/config/server/tasks/08d173e9";
  int taskId = 1234;

  @Mock FetchCommand fetchCommand;
  @Mock FetchJob fetchJob;
  @Mock FetchJob.Factory fetchJobFactory;
  @Mock ProjectResource projectResource;
  @Mock WorkQueue workQueue;
  @Mock ScheduledExecutorService executorService;
  @Mock DynamicItem<UrlFormatter> urlFormatterDynamicItem;
  @Mock UrlFormatter urlFormatter;
  @Mock WorkQueue.Task<Void> task;
  @Mock FetchPreconditions preConditions;

  @Before
  public void setup() {
    when(fetchJobFactory.create(any(), any(), any())).thenReturn(fetchJob);
    when(workQueue.getDefaultQueue()).thenReturn(executorService);
    when(urlFormatter.getRestUrl(anyString())).thenReturn(Optional.of(location));
    when(executorService.submit(any(Runnable.class)))
        .thenAnswer(
            new Answer<WorkQueue.Task<Void>>() {
              @Override
              public Task<Void> answer(InvocationOnMock invocation) throws Throwable {
                return task;
              }
            });
    when(urlFormatterDynamicItem.get()).thenReturn(urlFormatter);
    when(task.getTaskId()).thenReturn(taskId);
    when(preConditions.canCallFetchApi()).thenReturn(true);

    batchFetchAction =
        new BatchFetchAction(
            fetchCommand, workQueue, urlFormatterDynamicItem, preConditions, fetchJobFactory);
  }

  @Test
  public void shouldReturnCreatedResponseCodeForSyncCall() throws RestApiException {
    Inputs inputParams = createSyncInputs();

    Response<?> response = batchFetchAction.apply(projectResource, inputParams);

    assertThat(response.statusCode()).isEqualTo(SC_CREATED);
  }

  @SuppressWarnings("cast")
  @Test
  public void shouldReturnSourceUrlAndRefNamesAsAResponseBodyForSyncCall() throws RestApiException {
    Inputs inputParams = createSyncInputs();

    Response<?> response = batchFetchAction.apply(projectResource, inputParams);

    assertThat(response.value()).isEqualTo(inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingLabel() throws RestApiException {
    Inputs inputParams = createInputs(null, inputRefs, false);

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyLabel() throws RestApiException {
    Inputs inputParams = createInputs("");

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenNoRefsProvided() throws RestApiException {
    Inputs inputParams = createInputs(label, null, false);

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenListOfRefsEmpty() throws RestApiException {
    Inputs inputParams = createInputs(ImmutableList.of());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenListOfRefsContainsEmptyRefName()
      throws RestApiException {
    Inputs inputParams = createInputs(ImmutableList.of(master, ""));

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenProcessingInterrupted()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    Inputs inputParams = createSyncInputs();

    doThrow(new InterruptedException()).when(fetchCommand).fetchSync(any(), any());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = UnprocessableEntityException.class)
  public void shouldThrowRestApiExceptionWhenNoSourceForGivenLabel()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    Inputs inputParams = createSyncInputs();

    doThrow(new RemoteConfigurationMissingException("")).when(fetchCommand).fetchSync(any(), any());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenIssueDuringProcessing()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    Inputs inputParams = createSyncInputs();

    doThrow(new ExecutionException(new RuntimeException()))
        .when(fetchCommand)
        .fetchSync(any(), any());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenIssueWithUrlParam()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    Inputs inputParams = createSyncInputs();

    doThrow(new IllegalStateException()).when(fetchCommand).fetchSync(any(), any());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenTimeout()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    Inputs inputParams = createSyncInputs();

    doThrow(new TimeoutException()).when(fetchCommand).fetchSync(any(), any());

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = AuthException.class)
  public void shouldThrowAuthExceptionWhenCallBatchFetchActionCapabilityNotAssigned()
      throws RestApiException {
    Inputs inputParams = createSyncInputs();

    when(preConditions.canCallFetchApi()).thenReturn(false);

    batchFetchAction.apply(projectResource, inputParams);
  }

  @Test
  public void shouldReturnScheduledTaskForAsyncCall() throws RestApiException {
    Inputs inputParams = createAsyncInputs();

    Response<?> response = batchFetchAction.apply(projectResource, inputParams);
    assertThat(response.statusCode()).isEqualTo(SC_ACCEPTED);
  }

  @Test
  public void shouldReturnLocationHeaderForAsyncCall() throws RestApiException {
    Inputs inputParams = createAsyncInputs();

    Response<?> response = batchFetchAction.apply(projectResource, inputParams);
    assertThat(response).isInstanceOf(Response.Accepted.class);
    Response.Accepted acceptResponse = (Response.Accepted) response;
    assertThat(acceptResponse.location()).isEqualTo(location);
  }

  @Test
  public void shouldCreateFetchJobForAsyncCall() throws RestApiException {
    Inputs inputParams = createAsyncInputs();

    batchFetchAction.apply(projectResource, inputParams);

    verify(fetchJobFactory).create(eq(projectResource.getNameKey()), eq(inputParams), any());
  }

  private Inputs createSyncInputs() {
    return createInputs(false);
  }

  private Inputs createAsyncInputs() {
    return createInputs(true);
  }

  private Inputs createInputs(String label) {
    return createInputs(label, inputRefs, false);
  }

  private Inputs createInputs(List<String> refNames) {
    return createInputs(label, refNames, false);
  }

  private Inputs createInputs(boolean async) {
    return createInputs(label, inputRefs, async);
  }

  private Inputs createInputs(String label, List<String> refNames, boolean async) {
    Inputs inputs = new Inputs();
    inputs.label = label;
    inputs.refNames = refNames;
    inputs.async = async;
    return inputs;
  }
}
