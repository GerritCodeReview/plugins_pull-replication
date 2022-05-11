// Copyright (C) 2023 The Android Open Source Project
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

import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class EventsBrokerMessageConsumerTest {

  private static final String TEST_PROJECT = "test-project";
  private static final String INSTANCE_ID = "node_instance_id";

  @Mock private ProjectInitializationAction projectInitializationAction;
  @Mock private WorkQueue workQueue;
  @Mock private FetchJob.Factory fetchJobFactory;
  @Mock private StreamEventListener eventListener;
  @Mock DynamicItem<BrokerApi> eventsBroker;
  @Mock private PullReplicationApiRequestMetrics metrics;

  EventsBrokerMessageConsumer objectUnderTest;

  @Before
  public void setup() {
    objectUnderTest =
        new EventsBrokerMessageConsumer(
            INSTANCE_ID,
            projectInitializationAction,
            workQueue,
            fetchJobFactory,
            () -> metrics,
            eventsBroker,
            eventListener,
            "topicName");
  }

  @Test
  public void shouldRethrowExceptionWhenFetchThrowsAuthException()
      throws AuthException, PermissionBackendException {
    doThrow(PermissionBackendException.class).when(eventListener).fetchRefsForEvent(any());
    assertThrows(EventRejectedException.class, () -> objectUnderTest.accept(new RefUpdatedEvent()));
  }

  @Test
  public void shouldRethrowExceptionWhenFetchThrowsPermissionBackendException()
      throws AuthException, PermissionBackendException {
    doThrow(PermissionBackendException.class).when(eventListener).fetchRefsForEvent(any());
    assertThrows(EventRejectedException.class, () -> objectUnderTest.accept(new RefUpdatedEvent()));
  }

  @Test
  public void shouldNotThrowExceptionWhenFetchSucceed()
      throws AuthException, PermissionBackendException {
    doNothing().when(eventListener).fetchRefsForEvent(any());
    objectUnderTest.accept(new RefUpdatedEvent());
  }
}
