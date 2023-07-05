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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.git.WorkQueue.Task;
import com.google.gerrit.server.project.ProjectResource;
import com.googlesource.gerrit.plugins.replication.pull.api.BatchFetchAction.Inputs;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
@Ignore
public class BatchFetchActionTest {

  BatchFetchAction batchFetchAction;
  String label = "instance-2-label";
  String master = "refs/heads/master";
  String test = "refs/heads/test";

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

    batchFetchAction =
        new BatchFetchAction(
            fetchCommand, workQueue, urlFormatterDynamicItem, preConditions, fetchJobFactory);
  }

  //  @Test
  //  public void shouldDelegateToFetchActionForEveryFetchInput() throws RestApiException {
  //    BatchFetchAction.Inputs inputs = createInputs(ImmutableList.of(master, test));
  //
  //    batchFetchAction.apply(projectResource, inputs);
  //
  //    verify(fetchAction).apply(projectResource, inputs);
  //    //    verify(fetchAction).apply(projectResource, List.of(second));
  //  }

  //  @Test
  //  public void shouldReturnOkResponseCodeWhenAllInputsAreProcessedSuccessfully()
  //      throws RestApiException {
  //
  //    BatchFetchAction.Inputs inputs = createInputs(ImmutableList.of(master, test));
  //
  //    when(fetchAction.apply(projectResource, inputs))
  //        .thenAnswer((Answer<Response<?>>) invocation -> Response.accepted("some-url"));
  //    Response<?> response = batchFetchAction.apply(projectResource, inputs);
  //
  //    assertThat(response.statusCode()).isEqualTo(SC_OK);
  //  }

  //  @Test
  //  public void shouldReturnAListWithAllResponsesOnSuccess() throws RestApiException {
  //    FetchAction.Input first = createInput(master);
  //    FetchAction.Input second = createInput(test);
  //    String masterUrl = "master-url";
  //    String testUrl = "test-url";
  //    Response.Accepted firstResponse = Response.accepted(masterUrl);
  //    Response.Accepted secondResponse = Response.accepted(testUrl);
  //
  //    when(fetchAction.apply(projectResource, first))
  //        .thenAnswer((Answer<Response<?>>) invocation -> firstResponse);
  //    when(fetchAction.apply(projectResource, second))
  //        .thenAnswer((Answer<Response<?>>) invocation -> secondResponse);
  //    Response<?> response = batchFetchAction.apply(projectResource, List.of(first, second));
  //
  //    assertThat((List<Response<?>>) response.value())
  //        .isEqualTo(List.of(firstResponse, secondResponse));
  //  }
  //
  //  @Test
  //  public void shouldReturnAMixOfSyncAndAsyncResponses() throws RestApiException {
  //    FetchAction.Input async = createInput(master);
  //    FetchAction.Input sync = createInput(test);
  //    String masterUrl = "master-url";
  //    Response.Accepted asyncResponse = Response.accepted(masterUrl);
  //    Response<?> syncResponse = Response.created(sync);
  //
  //    when(fetchAction.apply(projectResource, async))
  //        .thenAnswer((Answer<Response<?>>) invocation -> asyncResponse);
  //    when(fetchAction.apply(projectResource, sync))
  //        .thenAnswer((Answer<Response<?>>) invocation -> syncResponse);
  //    Response<?> response = batchFetchAction.apply(projectResource, List.of(async, sync));
  //
  //    assertThat((List<Response<?>>) response.value())
  //        .isEqualTo(List.of(asyncResponse, syncResponse));
  //  }

  //  @Test(expected = RestApiException.class)
  //  public void shouldThrowRestApiExceptionWhenProcessingFailsForAnInput() throws RestApiException
  // {
  //    BatchFetchAction.Inputs inputs = createInputs(ImmutableList.of(master, test));
  //
  //    when(fetchAction.apply(projectResource, inputs)).thenThrow(new
  // MergeConflictException("BOOM"));
  //
  //    batchFetchAction.apply(projectResource, inputs);
  //  }

  //  private BatchFetchAction.Input createInput(String refName) {
  //    BatchFetchAction.Input input = new BatchFetchAction.Input();
  //    input.label = label;
  //    input.refName = refName;
  //    return input;
  //  }

  private Inputs createInputs(List<String> refNames) {
    BatchFetchAction.Inputs inputs = new BatchFetchAction.Inputs();
    inputs.label = label;
    inputs.refNames = refNames;
    return inputs;
  }
}
