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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.ShutdownState;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class EventsBrokerMessageConsumerTest {

  @Mock private StreamEventListener eventListener;
  @Mock DynamicItem<BrokerApi> eventsBrokerDynamicItem;
  @Mock BrokerApi eventsBroker;

  EventsBrokerMessageConsumer objectUnderTest;
  ShutdownState shutdownState;

  @Before
  public void setup() {
    shutdownState = new ShutdownState();
    objectUnderTest =
        new EventsBrokerMessageConsumer(
            eventsBrokerDynamicItem, eventListener, shutdownState, "topicName", null);
  }

  @Test
  public void shouldRethrowExceptionWhenFetchThrowsAuthException() throws Exception {
    doThrow(PermissionBackendException.class).when(eventListener).fetchRefsForEvent(any());
    assertThrows(EventRejectedException.class, () -> objectUnderTest.accept(new RefUpdatedEvent()));
  }

  @Test
  public void shouldRethrowExceptionWhenFetchThrowsPermissionBackendException() throws Exception {
    doThrow(PermissionBackendException.class).when(eventListener).fetchRefsForEvent(any());
    assertThrows(EventRejectedException.class, () -> objectUnderTest.accept(new RefUpdatedEvent()));
  }

  @Test
  public void shouldNotThrowExceptionWhenFetchSucceed() throws Exception {
    doNothing().when(eventListener).fetchRefsForEvent(any());
    objectUnderTest.accept(new RefUpdatedEvent());
  }

  @Test
  public void shouldStillAcceptLastEventDuringShutdownAndThenDisconnect() throws Exception {
    doNothing().when(eventListener).fetchRefsForEvent(any());
    when(eventsBrokerDynamicItem.get()).thenReturn(eventsBroker);

    shutdownState.setIsShuttingDown(true);

    objectUnderTest.accept(new RefUpdatedEvent());
    verify(eventsBroker, times(1)).disconnect("topicName", null);
  }
}
