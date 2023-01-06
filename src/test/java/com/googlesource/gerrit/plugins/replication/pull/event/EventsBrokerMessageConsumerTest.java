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

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
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
public class EventsBrokerMessageConsumerTest {

  private static final String TEST_REF_NAME = "refs/changes/01/1/1";
  private static final String TEST_PROJECT = "test-project";
  private static final String INSTANCE_ID = "node_instance_id";
  private static final String REMOTE_INSTANCE_ID = "remote_node_instance_id";

  @Mock private ProjectInitializationAction projectInitializationAction;
  @Mock private WorkQueue workQueue;
  @Mock private ScheduledExecutorService executor;
  @Mock private FetchJob fetchJob;
  @Mock private FetchJob.Factory fetchJobFactory;
  @Captor ArgumentCaptor<Input> inputCaptor;
  @Mock private PullReplicationApiRequestMetrics metrics;
  @Mock private DynamicItem<BrokerApi> eventsBroker;

  private EventsBrokerMessageConsumer objectUnderTest;

  @Before
  public void setup() {
    when(workQueue.getDefaultQueue()).thenReturn(executor);
    when(fetchJobFactory.create(eq(Project.nameKey(TEST_PROJECT)), any(), any()))
        .thenReturn(fetchJob);
    objectUnderTest =
        new EventsBrokerMessageConsumer(
            INSTANCE_ID,
            projectInitializationAction,
            workQueue,
            fetchJobFactory,
            () -> metrics,
            eventsBroker,
            "events");
  }

  @Test
  public void shouldSkipEventsGeneratedByTheSameInstance() {
    Event event = new RefUpdatedEvent();
    event.instanceId = INSTANCE_ID;
    objectUnderTest.accept(event);

    verify(executor, never()).submit(any(Runnable.class));
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

    objectUnderTest.accept(event);

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void shouldScheduleFetchJobForRefUpdateEvent() {
    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = TEST_REF_NAME;
    refUpdate.project = TEST_PROJECT;

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.accept(event);

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

    objectUnderTest.accept(event);

    verify(projectInitializationAction).initProject(String.format("%s.git", TEST_PROJECT));
  }

  @Test
  public void shouldScheduleAllRefsFetchForProjectCreatedEvent() {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.instanceId = REMOTE_INSTANCE_ID;
    event.projectName = TEST_PROJECT;

    objectUnderTest.accept(event);

    verify(fetchJobFactory).create(eq(Project.nameKey(TEST_PROJECT)), inputCaptor.capture(), any());

    Input input = inputCaptor.getValue();
    assertThat(input.label).isEqualTo(REMOTE_INSTANCE_ID);
    assertThat(input.refName).isEqualTo(FetchOne.ALL_REFS);

    verify(executor).submit(any(FetchJob.class));
  }
}
