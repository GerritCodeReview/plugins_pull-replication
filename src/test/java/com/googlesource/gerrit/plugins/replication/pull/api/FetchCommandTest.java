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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.net.URISyntaxException;
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
  private static final String OBJECT_ID_TO_FETCH = "8f22c45da5ce298291b7329552568aae1bb62c10";
  @Mock ReplicationState state;
  @Mock ReplicationState.Factory fetchReplicationStateFactory;
  @Mock PullReplicationStateLogger fetchStateLog;
  @Mock Source source;
  @Mock SourcesCollection sources;

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
    when(source.getRemoteConfigName()).thenReturn(label);
    when(sources.getAll()).thenReturn(Lists.newArrayList(source));
    when(source.schedule(projectName, OBJECT_ID_TO_FETCH, state, true))
        .thenReturn(CompletableFuture.completedFuture(null));
    objectUnderTest = new FetchCommand(fetchReplicationStateFactory, fetchStateLog, sources);
  }

  @Test
  public void shouldFetchRefUpdate()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetch(projectName, label, OBJECT_ID_TO_FETCH);

    verify(source, times(1)).schedule(projectName, OBJECT_ID_TO_FETCH, state, true);
  }

  @Test
  public void shouldMarkAllFetchTasksScheduled()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetch(projectName, label, OBJECT_ID_TO_FETCH);

    verify(source, times(1)).schedule(projectName, OBJECT_ID_TO_FETCH, state, true);
    verify(state, times(1)).markAllFetchTasksScheduled();
  }

  @Test
  public void shouldUpdateStateWhenRemoteConfigNameIsMissing() {
      assertThrows(RemoteConfigurationMissingException.class, () -> objectUnderTest.fetch(projectName, "unknownLabel", OBJECT_ID_TO_FETCH));
      verify(fetchStateLog, times(1)).error(anyString(), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenInterruptedException()
      throws InterruptedException, ExecutionException,
          TimeoutException {
    when(future.get(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(new InterruptedException());
    when(source.schedule(projectName, OBJECT_ID_TO_FETCH, state, true)).thenReturn(future);

    InterruptedException e = assertThrows(InterruptedException.class,() -> objectUnderTest.fetch(projectName, label, OBJECT_ID_TO_FETCH));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenExecutionException()
      throws InterruptedException, ExecutionException,
          TimeoutException {
    when(future.get(anyLong(), eq(TimeUnit.SECONDS)))
        .thenThrow(new ExecutionException(new Exception()));
    when(source.schedule(projectName, OBJECT_ID_TO_FETCH, state, true)).thenReturn(future);

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> objectUnderTest.fetch(projectName, label, OBJECT_ID_TO_FETCH));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldUpdateStateWhenTimeoutException()
      throws InterruptedException, ExecutionException,
          TimeoutException {
    when(future.get(anyLong(), eq(TimeUnit.SECONDS))).thenThrow(new TimeoutException());
    when(source.schedule(projectName, OBJECT_ID_TO_FETCH, state, true)).thenReturn(future);

    TimeoutException e =
        assertThrows(
            TimeoutException.class,
            () -> objectUnderTest.fetch(projectName, label, OBJECT_ID_TO_FETCH));
    verify(fetchStateLog, times(1)).error(anyString(), eq(e), eq(state));
  }
}
