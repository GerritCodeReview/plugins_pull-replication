// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.event;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.DeleteRefCommand;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StreamEventListenerTest {

  private static final String TEST_REF_NAME = "refs/changes/01/1/1";
  private static final String TEST_PROJECT = "test-project";
  private static final String INSTANCE_ID = "node_instance_id";
  private static final String REMOTE_INSTANCE_ID = "remote_node_instance_id";

  @Mock private ProjectInitializationAction projectInitializationAction;
  @Mock private WorkQueue workQueue;
  @Mock private ScheduledExecutorService executor;
  @Mock private FetchJob fetchJob;
  @Mock private FetchJob.Factory fetchJobFactory;
  @Mock private DeleteRefCommand deleteRefCommand;
  @Captor ArgumentCaptor<Input> inputCaptor;
  @Mock private PullReplicationApiRequestMetrics metrics;
  @Mock private SourcesCollection sources;
  @Mock private Source source;

  private StreamEventListener objectUnderTest;

  @Before
  public void setup() {
    when(workQueue.getDefaultQueue()).thenReturn(executor);
    when(fetchJobFactory.create(eq(Project.nameKey(TEST_PROJECT)), any(), any()))
        .thenReturn(fetchJob);
    when(sources.getAll()).thenReturn(Lists.newArrayList(source));
    when(source.wouldFetchProject(any())).thenReturn(true);
    when(source.getRemoteConfigName()).thenReturn(REMOTE_INSTANCE_ID);
    objectUnderTest =
        new StreamEventListener(
            INSTANCE_ID,
            deleteRefCommand,
            projectInitializationAction,
            workQueue,
            fetchJobFactory,
            () -> metrics,
            sources);
  }

  @Test
  public void shouldSkipEventsGeneratedByTheSameInstance() {
    Event event = new RefUpdatedEvent();
    event.instanceId = INSTANCE_ID;
    objectUnderTest.onEvent(event);

    verify(executor, never()).submit(any(Runnable.class));
    verify(sources, never()).getAll();
  }

  @Test
  public void shouldSkipFetchForProjectDeleteEvent() {
    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = RefNames.REFS_CONFIG;
    refUpdate.newRev = ObjectId.zeroId().getName();
    refUpdate.project = TEST_PROJECT;

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.onEvent(event);

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void shouldSkipEventWhenNotOnAllowedProjectsList() {
    when(source.wouldFetchProject(any())).thenReturn(false);

    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = TEST_REF_NAME;
    refUpdate.project = TEST_PROJECT;
    refUpdate.oldRev = ObjectId.zeroId().getName();
    refUpdate.newRev = "0000000000000000000000000000000000000001";

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.onEvent(event);

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void shouldDeleteRefForRefDeleteEvent() throws IOException, RestApiException {
    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = "refs/changes/01/1/1";
    refUpdate.newRev = ObjectId.zeroId().getName();
    refUpdate.project = TEST_PROJECT;

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.onEvent(event);

    verify(deleteRefCommand)
        .deleteRef(Project.nameKey(TEST_PROJECT), refUpdate.refName, REMOTE_INSTANCE_ID);
  }

  @Test
  public void shouldScheduleFetchJobForRefUpdateEvent() {
    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = TEST_REF_NAME;
    refUpdate.project = TEST_PROJECT;
    refUpdate.oldRev = ObjectId.zeroId().getName();
    refUpdate.newRev = "0000000000000000000000000000000000000001";

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.onEvent(event);

    verify(fetchJobFactory).create(eq(Project.nameKey(TEST_PROJECT)), inputCaptor.capture(), any());

    Input input = inputCaptor.getValue();
    assertThat(input.label).isEqualTo(REMOTE_INSTANCE_ID);
    assertThat(input.refName).isEqualTo(TEST_REF_NAME);

    verify(executor).submit(any(FetchJob.class));
  }

  @Test
  public void shouldCreateProjectForProjectCreatedEvent()
      throws AuthException, PermissionBackendException {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.instanceId = REMOTE_INSTANCE_ID;
    event.projectName = TEST_PROJECT;

    objectUnderTest.onEvent(event);

    verify(projectInitializationAction).initProject(String.format("%s.git", TEST_PROJECT));
  }

  @Test
  public void shouldScheduleAllRefsFetchForProjectCreatedEvent() {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.instanceId = REMOTE_INSTANCE_ID;
    event.projectName = TEST_PROJECT;

    objectUnderTest.onEvent(event);

    verify(fetchJobFactory).create(eq(Project.nameKey(TEST_PROJECT)), inputCaptor.capture(), any());

    Input input = inputCaptor.getValue();
    assertThat(input.label).isEqualTo(REMOTE_INSTANCE_ID);
    assertThat(input.refName).isEqualTo(FetchOne.ALL_REFS);

    verify(executor).submit(any(FetchJob.class));
  }
}
