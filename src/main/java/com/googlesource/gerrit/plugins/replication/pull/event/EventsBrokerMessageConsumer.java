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

import static com.googlesource.gerrit.plugins.replication.pull.event.EventsBrokerConsumerModule.STREAM_EVENTS_GROUP_ID;
import static com.googlesource.gerrit.plugins.replication.pull.event.EventsBrokerConsumerModule.STREAM_EVENTS_TOPIC_NAME;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.gerritforge.gerrit.eventbroker.ExtendedBrokerApi;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.googlesource.gerrit.plugins.replication.pull.ShutdownState;
import java.util.function.Consumer;

public class EventsBrokerMessageConsumer implements Consumer<Event>, LifecycleListener {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final DynamicItem<BrokerApi> eventsBroker;
  private final StreamEventListener eventListener;
  private final ShutdownState shutdownState;
  private final String eventsTopicName;
  private final String groupId;

  @Inject
  public EventsBrokerMessageConsumer(
      DynamicItem<BrokerApi> eventsBroker,
      StreamEventListener eventListener,
      ShutdownState shutdownState,
      @Named(STREAM_EVENTS_TOPIC_NAME) String eventsTopicName,
      @Nullable @Named(STREAM_EVENTS_GROUP_ID) String groupId) {

    this.eventsBroker = eventsBroker;
    this.eventListener = eventListener;
    this.shutdownState = shutdownState;
    this.eventsTopicName = eventsTopicName;
    this.groupId = groupId;
  }

  @Override
  public void accept(Event event) {
    try {
      eventListener.fetchRefsForEvent(event);
      if (shutdownState.isShuttingDown()) stop();
    } catch (AuthException | PermissionBackendException e) {
      throw new EventRejectedException(event, e);
    }
  }

  @Override
  public void start() {
    BrokerApi brokerApi = eventsBroker.get();
    boolean isGroupIdDefined = !Strings.isNullOrEmpty(groupId);
    if (brokerApi instanceof ExtendedBrokerApi) {
      if (brokerApi instanceof ExtendedBrokerApi && isGroupIdDefined) {
        ((ExtendedBrokerApi) brokerApi).receiveAsync(eventsTopicName, groupId, this);
        return;
      }

      if (isGroupIdDefined) {
        logger.atWarning().log(
            "Consumer has NOT been configured with groupId: %s because brokerApi is not bound to ExtendedBrokerApi concrete implementation",
            groupId);
      }
      brokerApi.receiveAsync(eventsTopicName, this);
    }
  }

  @Override
  public void stop() {
    shutdownState.setIsShuttingDown(true);
    eventsBroker.get().disconnect();
  }
}
