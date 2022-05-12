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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.restapi.project.ProjectsCollection;
import com.googlesource.gerrit.plugins.deleteproject.ProjectDeletedEvent;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchCommand;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectDeletionAction;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StreamEventListenerTest {

  private static final String TEST_PROJECT = "test-project";
  private static final String INSTANCE_ID = "node_instance_id";
  private static final String REMOTE_INSTANCE_ID = "remote_node_instance_id";

  @Mock private FetchCommand fetchCommand;
  @Mock private ProjectInitializationAction projectInitializationAction;
  @Mock private WorkQueue workQueue;
  @Mock private ProjectDeletionAction projectDeletionAction;
  @Mock private ProjectsCollection projectsCollection;
  @Mock private ScheduledExecutorService executor;

  private StreamEventListener objectUnderTest;

  @Before
  public void setup() {
    when(workQueue.getDefaultQueue()).thenReturn(executor);
    objectUnderTest =
        new StreamEventListener(
            INSTANCE_ID,
            fetchCommand,
            projectInitializationAction,
            workQueue,
            projectDeletionAction,
            projectsCollection);
  }

  @Test
  public void shouldSkipEventsGeneratedByTheSameInstance() {
    Event event = new RefUpdatedEvent();
    event.instanceId = INSTANCE_ID;
    objectUnderTest.onEvent(event);

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void shouldSkipFetchForProjectDeleteEvent() {
    ProjectDeletedEvent event = new ProjectDeletedEvent();
    event.projectName = TEST_PROJECT;
    event.instanceId = REMOTE_INSTANCE_ID;

    objectUnderTest.onEvent(event);

    verify(executor, never()).submit(any(Runnable.class));
  }

  @Test
  public void shouldScheduleFetchJobForRefUpdateEvent() {
    RefUpdatedEvent event = new RefUpdatedEvent();
    RefUpdateAttribute refUpdate = new RefUpdateAttribute();
    refUpdate.refName = "refs/changes/01/1/1";
    refUpdate.project = TEST_PROJECT;

    event.instanceId = REMOTE_INSTANCE_ID;
    event.refUpdate = () -> refUpdate;

    objectUnderTest.onEvent(event);

    verify(executor).submit(any(FetchJob.class));
  }

  @Test
  public void shouldCreateProjectForProjectCreatedEvent()
      throws AuthException, PermissionBackendException {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.instanceId = REMOTE_INSTANCE_ID;
    event.projectName = TEST_PROJECT;

    objectUnderTest.onEvent(event);

    verify(projectInitializationAction, times(1))
        .initProject(String.format("%s.git", TEST_PROJECT));
  }

  @Test
  public void shouldScheduleAllRefsFetchForProjectCreatedEvent() {
    ProjectCreatedEvent event = new ProjectCreatedEvent();
    event.instanceId = REMOTE_INSTANCE_ID;
    event.projectName = TEST_PROJECT;

    objectUnderTest.onEvent(event);

    verify(executor).submit(any(FetchJob.class));
  }
}
