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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;

import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.project.ProjectResource;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FetchActionTest {
  FetchAction fetchAction;
  String label = "instance-2-label";
  String url = "file:///gerrit-host/instance-1/git/${name}.git";
  String objectId = "c90989ed7a8ab01f1bdd022872428f020b866358";

  @Mock FetchService fetchService;
  @Mock ProjectResource projectResource;

  @Before
  public void setup() {
    fetchAction = new FetchAction(fetchService);
  }

  @Test
  public void shouldReturnCreatedResponseCode() throws RestApiException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.object_id = objectId;

    Response<?> response = fetchAction.apply(projectResource, inputParams);

    assertThat(response.statusCode()).isEqualTo(201);
  }

  @SuppressWarnings("cast")
  @Test
  public void shouldReturnSourceUrlAndObjectIdAsAResponseBody() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.object_id = objectId;

    Response<?> response = fetchAction.apply(projectResource, inputParams);

    assertThat((FetchAction.Input) response.value()).isEqualTo(inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingLabel() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.object_id = objectId;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyLabel() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = "";
    inputParams.object_id = objectId;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenMissingObjectId() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = BadRequestException.class)
  public void shouldThrowBadRequestExceptionWhenEmptyObjectId() throws Exception {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.object_id = "";

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenPocessingInterrupted()
      throws RestApiException, InterruptedException, ExecutionException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.object_id = objectId;

    doThrow(new InterruptedException()).when(fetchService).fetch(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenNoSurceForGivenLabel()
      throws RestApiException, InterruptedException, ExecutionException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = "non-existing-label";
    inputParams.object_id = objectId;

    doThrow(new IllegalStateException()).when(fetchService).fetch(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }

  @Test(expected = RestApiException.class)
  public void shouldThrowRestApiExceptionWhenIssueDuringPocessing()
      throws RestApiException, InterruptedException, ExecutionException {
    FetchAction.Input inputParams = new FetchAction.Input();
    inputParams.label = label;
    inputParams.object_id = objectId;

    doThrow(new ExecutionException(new RuntimeException()))
        .when(fetchService)
        .fetch(any(), any(), any());

    fetchAction.apply(projectResource, inputParams);
  }
}
