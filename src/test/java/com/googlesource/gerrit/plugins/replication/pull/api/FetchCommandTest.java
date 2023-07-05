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

import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.pull.ReplicationType.ASYNC;
import static com.googlesource.gerrit.plugins.replication.pull.ReplicationType.SYNC;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FetchCommandTest {
  private static final String REF_NAME_TO_FETCH = "refs/heads/master";
  private static final String TEST_REF = "refs/heads/test";
  @Mock ReplicationState state;
  @Mock ReplicationState.Factory fetchReplicationStateFactory;
  @Mock PullReplicationStateLogger fetchStateLog;
  @Mock Source source;
  @Mock SourcesCollection sources;
  @Mock DynamicItem<EventDispatcher> eventDispatcher;
  @Mock PullReplicationApiRequestMetrics apiRequestMetrics;

  @SuppressWarnings("rawtypes")
  @Mock
  Future future;

  Project.NameKey projectName;
  URIish uri;
  String label;

  FetchCommand objectUnderTest;

  @Before
  public void setup() throws URISyntaxException {
    projectName = Project.nameKey("sample_project");
    uri = new URIish("file://sample_host/repository_path/repo.git");
    label = "instance-1-label";

    when(fetchReplicationStateFactory.create(any())).thenReturn(state);
    when(sources.getByRemoteName(label)).thenReturn(Optional.of(source));
    when(source.schedule(eq(projectName), anyList(), eq(state), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    objectUnderTest =
        new FetchCommand(fetchReplicationStateFactory, fetchStateLog, sources, eventDispatcher);
  }

  @Test
  public void shouldScheduleMultipleRefsFetchInASingleCall()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    BatchFetchAction.Inputs inputs = new BatchFetchAction.Inputs();
    inputs.label = label;
    inputs.refNames = ImmutableList.of(REF_NAME_TO_FETCH, TEST_REF);
    inputs.async = false;
    objectUnderTest.fetchSync(projectName, inputs);

    verify(source, times(1))
        .schedule(
            projectName,
            ImmutableList.of(REF_NAME_TO_FETCH, TEST_REF),
            state,
            SYNC,
            Optional.empty());
  }

  @Test
  public void shouldScheduleMultipleRefsFetchInASingleCallWithDelay()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    BatchFetchAction.Inputs inputs = new BatchFetchAction.Inputs();
    inputs.label = label;
    inputs.refNames = ImmutableList.of(REF_NAME_TO_FETCH, TEST_REF);
    inputs.async = true;
    objectUnderTest.fetchAsync(projectName, inputs, apiRequestMetrics);

    verify(source, times(1))
        .schedule(
            projectName,
            ImmutableList.of(REF_NAME_TO_FETCH, TEST_REF),
            state,
            ASYNC,
            Optional.of(apiRequestMetrics));
  }

  @Test
  public void shouldMarkAllFetchTasksScheduled()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetchSync(
        projectName, BatchFetchAction.toInputsFromSingleRef(label, REF_NAME_TO_FETCH, false));

    verify(source, times(1))
        .schedule(projectName, ImmutableList.of(REF_NAME_TO_FETCH), state, SYNC, Optional.empty());
    verify(state, times(1)).markAllFetchTasksScheduled();
  }

  @Test
  public void shouldUpdateStateWhenRemoteConfigNameIsMissing() {
    assertThrows(
        RemoteConfigurationMissingException.class,
        () ->
            objectUnderTest.fetchSync(
                projectName,
                BatchFetchAction.toInputsFromSingleRef("unknownLabel", REF_NAME_TO_FETCH, false)));
    verify(fetchStateLog, times(1)).error(anyString(), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenInterruptedException()
      throws InterruptedException, ExecutionException, TimeoutException {
    when(future.get()).thenThrow(new InterruptedException());
    when(source.schedule(
            projectName, ImmutableList.of(REF_NAME_TO_FETCH), state, SYNC, Optional.empty()))
        .thenReturn(future);

    InterruptedException e =
        assertThrows(
            InterruptedException.class,
            () ->
                objectUnderTest.fetchSync(
                    projectName,
                    BatchFetchAction.toInputsFromSingleRef(label, REF_NAME_TO_FETCH, false)));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenExecutionException()
      throws InterruptedException, ExecutionException, TimeoutException {
    when(future.get()).thenThrow(new ExecutionException(new Exception()));
    when(source.schedule(
            projectName, ImmutableList.of(REF_NAME_TO_FETCH), state, SYNC, Optional.empty()))
        .thenReturn(future);

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () ->
                objectUnderTest.fetchSync(
                    projectName,
                    BatchFetchAction.toInputsFromSingleRef(label, REF_NAME_TO_FETCH, false)));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenTimeoutException()
      throws InterruptedException, ExecutionException, TimeoutException {
    when(future.get(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(new TimeoutException());
    when(source.schedule(
            projectName, ImmutableList.of(REF_NAME_TO_FETCH), state, SYNC, Optional.empty()))
        .thenReturn(future);
    when(source.getTimeout()).thenReturn(1);

    TimeoutException e =
        assertThrows(
            TimeoutException.class,
            () ->
                objectUnderTest.fetchSync(
                    projectName,
                    BatchFetchAction.toInputsFromSingleRef(label, REF_NAME_TO_FETCH, false)));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }
}
