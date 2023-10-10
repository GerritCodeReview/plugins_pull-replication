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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FetchCommandTest {
  private static final String REF_NAME_TO_FETCH = "refs/heads/master";
  private static final Set<String> REFS_NAMES_TO_FETCH = Set.of(REF_NAME_TO_FETCH);
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
  public void setup() throws Exception {
    projectName = Project.nameKey("sample_project");
    uri = new URIish("file://sample_host/repository_path/repo.git");
    label = "instance-1-label";

    when(fetchReplicationStateFactory.create(any())).thenReturn(state);
    when(sources.getByRemoteName(eq(label))).thenReturn(Optional.of(source));
    when(source.schedule(eq(projectName), eq(REF_NAME_TO_FETCH), eq(state), any()))
        .thenReturn(CompletableFuture.completedFuture(null));
    objectUnderTest =
        new FetchCommand(fetchReplicationStateFactory, fetchStateLog, sources, eventDispatcher);
  }

  @Test
<<<<<<< PATCH SET (d970b7 Run synchronous fetch as a single FetchOp for all refs in a )
  public void shouldScheduleRefFetch()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetchSync(projectName, label, REFS_NAMES_TO_FETCH);
=======
  public void shouldScheduleRefFetchWithDelay() throws Exception {
    objectUnderTest.fetchAsync(projectName, label, REF_NAME_TO_FETCH, apiRequestMetrics);

    verify(source, times(1))
        .schedule(
            eq(projectName), eq(REF_NAME_TO_FETCH), eq(state), eq(Optional.of(apiRequestMetrics)));
  }

  @Test
  public void shouldNotScheduleAsyncTaskWhenFetchSync() throws Exception {
    objectUnderTest.fetchSync(projectName, label, REF_NAME_TO_FETCH);
>>>>>>> BASE      (0c6865 Merge "Accept remotes without `fetch` option on primary")

<<<<<<< PATCH SET (d970b7 Run synchronous fetch as a single FetchOp for all refs in a )
    verify(source, times(1)).fetchSync(projectName, REFS_NAMES_TO_FETCH, Optional.empty());
  }

  @Test
  public void shouldScheduleRefFetchWithDelay()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetchAsync(projectName, label, REF_NAME_TO_FETCH, apiRequestMetrics);

    verify(source, times(1))
        .schedule(projectName, REF_NAME_TO_FETCH, state, ASYNC, Optional.of(apiRequestMetrics));
  }

  @Test
  public void shouldMarkAllFetchTasksScheduled()
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    objectUnderTest.fetchAsync(projectName, label, REF_NAME_TO_FETCH, apiRequestMetrics);

    verify(source, times(1))
        .schedule(projectName, REF_NAME_TO_FETCH, state, ASYNC, Optional.of(apiRequestMetrics));
    verify(state, times(1)).markAllFetchTasksScheduled();
=======
    verify(source, never())
        .schedule(
            eq(projectName), eq(REF_NAME_TO_FETCH), eq(state), eq(Optional.of(apiRequestMetrics)));
>>>>>>> BASE      (0c6865 Merge "Accept remotes without `fetch` option on primary")
  }

  @Test
  public void shouldUpdateStateWhenRemoteConfigNameIsMissing() {
    assertThrows(
        RemoteConfigurationMissingException.class,
        () -> objectUnderTest.fetchSync(projectName, "unknownLabel", REF_NAME_TO_FETCH));
    verify(fetchStateLog, times(1)).error(anyString(), eq(state));
  }
}
