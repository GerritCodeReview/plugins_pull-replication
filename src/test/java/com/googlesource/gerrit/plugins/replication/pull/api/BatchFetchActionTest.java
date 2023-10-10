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
import static org.apache.http.HttpStatus.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.project.ProjectResource;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class BatchFetchActionTest {

  BatchFetchAction batchFetchAction;
  String label = "instance-2-label";
  String master = "refs/heads/master";
  String test = "refs/heads/test";

  @Mock ProjectResource projectResource;
  @Mock FetchAction fetchAction;

  @Before
  public void setup() {
    batchFetchAction = new BatchFetchAction(fetchAction);
  }

  @Test
  public void shouldDelegateToFetchActionWithBatchInputForListOfFetchInput()
      throws RestApiException {
    FetchAction.Input first = createInput(master);
    FetchAction.Input second = createInput(test);

    batchFetchAction.apply(projectResource, List.of(first, second));

    verify(fetchAction).apply(eq(projectResource), any(FetchAction.BatchInput.class));
  }

  @Test
  public void shouldReturnOkResponseCodeWhenAllInputsAreProcessedSuccessfully()
      throws RestApiException {
    FetchAction.Input first = createInput(master);
    FetchAction.Input second = createInput(test);

    when(fetchAction.apply(any(ProjectResource.class), any(FetchAction.BatchInput.class)))
        .thenAnswer((Answer<Response<?>>) invocation -> Response.accepted("some-url"));
    Response<?> response = batchFetchAction.apply(projectResource, List.of(first, second));

    assertThat(response.statusCode()).isEqualTo(SC_OK);
  }

  @Test
  public void shouldReturnAResponsesOnSuccess() throws RestApiException {
    FetchAction.Input first = createInput(master);
    FetchAction.Input second = createInput(test);
    String masterUrl = "master-url";
    String testUrl = "test-url";
    Response.Accepted batchResponse = Response.accepted(masterUrl);

    when(fetchAction.apply(eq(projectResource), any(FetchAction.BatchInput.class)))
        .thenAnswer((Answer<Response<?>>) invocation -> batchResponse);
    Response<?> response = batchFetchAction.apply(projectResource, List.of(first, second));

    assertThat(response.value()).isEqualTo(batchResponse);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenProcessingFailsForAnInput() throws RestApiException {
    FetchAction.Input first = createInput(master);
    FetchAction.Input second = createInput(test);
    String masterUrl = "master-url";

    when(fetchAction.apply(eq(projectResource), any(FetchAction.BatchInput.class)))
        .thenThrow(new MergeConflictException("BOOM"));

    batchFetchAction.apply(projectResource, List.of(first, second));
  }

  private FetchAction.Input createInput(String refName) {
    FetchAction.Input input = new FetchAction.Input();
    input.label = label;
    input.refName = refName;
    return input;
  }
}
