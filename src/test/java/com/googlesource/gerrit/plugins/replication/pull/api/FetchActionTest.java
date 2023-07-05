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

import static com.google.common.truth.Truth.assertThat;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
public class FetchActionTest {
  FetchAction fetchAction;
  String label = "instance-2-label";
  String url = "file:///gerrit-host/instance-1/git/${name}.git";
  String refName = "refs/heads/master";
  String location = "http://gerrit-host/a/config/server/tasks/08d173e9";
  int taskId = 1234;

  @Mock FetchCommand fetchCommand;
  @Mock FetchJob fetchJob;
  @Mock FetchJob.Factory fetchJobFactory;
  @Mock ProjectResource projectResource;
  @Mock WorkQueue workQueue;
  @Mock ScheduledExecutorService exceutorService;
  @Mock DynamicItem<UrlFormatter> urlFormatterDynamicItem;
  @Mock UrlFormatter urlFormatter;
  @Mock WorkQueue.Task<Void> task;
  @Mock FetchPreconditions preConditions;

  @Before
  public void setup() {
    when(fetchJobFactory.create(any(), any(), any())).thenReturn(fetchJob);
    when(workQueue.getDefaultQueue()).thenReturn(exceutorService);
    when(urlFormatter.getRestUrl(anyString())).thenReturn(Optional.of(location));
    when(exceutorService.submit(any(Runnable.class)))
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

    fetchAction =
        new FetchAction(
            fetchCommand, workQueue, urlFormatterDynamicItem, preConditions, fetchJobFactory);
  }

  @Test
  public void shouldReturnCreatedResponseCode() throws RestApiException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    Response<?> response = fetchAction.apply(projectResource, inputParams);

    assertThat(response.statusCode()).isEqualTo(SC_CREATED);
  }

  @SuppressWarnings("cast")
  @Test
  public void shouldReturnSourceUrlAndrefNameAsAResponseBody() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    Response<?> response = fetchAction.apply(projectResource, inputParams);

    assertThat((FetchAction.Input) response.value()).isEqualTo(inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingLabel() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.refName = refName;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyLabel() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = "";
    inputParams.refName = refName;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingrefName() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyrefName() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = "";

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenPocessingInterrupted()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    doThrow(new InterruptedException()).when(fetchCommand).fetchSync(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = UnprocessableEntityException.class)
  public void shouldThrowRestApiExceptionWhenNoSurceForGivenLabel()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = "non-existing-label";
    inputParams.refName = refName;

    doThrow(new RemoteConfigurationMissingException(""))
        .when(fetchCommand)
        .fetchSync(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenIssueDuringPocessing()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    doThrow(new ExecutionException(new RuntimeException()))
        .when(fetchCommand)
        .fetchSync(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenIssueWithUrlParam()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    doThrow(new IllegalStateException()).when(fetchCommand).fetchSync(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenTimeout()
      throws RestApiException, InterruptedException, ExecutionException,
          RemoteConfigurationMissingException, TimeoutException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    doThrow(new TimeoutException()).when(fetchCommand).fetchSync(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = AuthException.class)
  public void shouldThrowAuthExceptionWhenCallFetchActionCapabilityNotAssigned()
      throws RestApiException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;

    when(preConditions.canCallFetchApi()).thenReturn(false);

    fetchAction.apply(projectResource, inputParams);
  }

  @Test
  public void shouldReturnScheduledTaskForAsyncCall() throws RestApiException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;
    inputParams.async = true;

    Response<?> response = fetchAction.apply(projectResource, inputParams);
    assertThat(response.statusCode()).isEqualTo(SC_ACCEPTED);
  }

  @Test
  public void shouldLocationHeaderForAsyncCall() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;
    inputParams.async = true;

    Response<?> response = fetchAction.apply(projectResource, inputParams);
    assertThat(response).isInstanceOf(Response.Accepted.class);
    Response.Accepted acceptResponse = (Response.Accepted) response;
    assertThat(acceptResponse.location()).isEqualTo(location);
  }

  @Test
  public void shouldCreateFetchJobUsingBatchRefActionInputsForAsyncCall() throws RestApiException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.refName = refName;
    inputParams.async = true;

    fetchAction.apply(projectResource, inputParams);

    verify(fetchJobFactory).create(any(), eq(inputParams.asBatchFetchActionInputs()), any());
  }
}
