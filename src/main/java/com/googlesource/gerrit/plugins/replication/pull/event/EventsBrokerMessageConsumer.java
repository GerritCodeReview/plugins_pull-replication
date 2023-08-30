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

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import java.util.function.Consumer;

import static com.googlesource.gerrit.plugins.replication.pull.event.EventsBrokerConsumerModule.STREAM_EVENTS_TOPIC_NAME;

public class EventsBrokerMessageConsumer implements Consumer<Event>, LifecycleListener {

  private final DynamicItem<BrokerApi> eventsBroker;
  private final StreamEventListener eventListener;
  private final String eventsTopicName;

  @Inject
  public EventsBrokerMessageConsumer(
      DynamicItem<BrokerApi> eventsBroker,
      StreamEventListener eventListener,
      @Named(STREAM_EVENTS_TOPIC_NAME) String eventsTopicName) {

    this.eventsBroker = eventsBroker;
    this.eventListener = eventListener;
    this.eventsTopicName = eventsTopicName;
  }

  @Override
  public void accept(Event event) {
    try {
      eventListener.fetchRefsForEvent(event);
    } catch (AuthException | PermissionBackendException e) {
      throw new EventRejectedException(event, e);
    }
  }

  @Override
  public void start() {
    eventsBroker.get().receiveAsync(eventsTopicName, this);
  }

  @Override
  public void stop() {
    eventsBroker.get().disconnect();
  }
}
