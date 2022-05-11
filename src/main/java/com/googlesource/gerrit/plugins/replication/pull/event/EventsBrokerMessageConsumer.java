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

import static com.googlesource.gerrit.plugins.replication.pull.event.EventsBrokerConsumerModule.STREAM_EVENTS_TOPIC_NAME;
import static java.util.Objects.requireNonNull;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.ObjectId;

public class EventsBrokerMessageConsumer implements Consumer<Event>, LifecycleListener {

  private String instanceId;
  private WorkQueue workQueue;
  private ProjectInitializationAction projectInitializationAction;
  private DynamicItem<BrokerApi> eventsBroker;
  private StreamEventListener eventListener;
  private final String eventsTopicName;

  private Factory fetchJobFactory;
  private final Provider<PullReplicationApiRequestMetrics> metricsProvider;

  @Inject
  public EventsBrokerMessageConsumer(
      @Nullable @GerritInstanceId String instanceId,
      ProjectInitializationAction projectInitializationAction,
      WorkQueue workQueue,
      FetchJob.Factory fetchJobFactory,
      Provider<PullReplicationApiRequestMetrics> metricsProvider,
      DynamicItem<BrokerApi> eventsBroker,
      StreamEventListener eventListener,
      @Named(STREAM_EVENTS_TOPIC_NAME) String eventsTopicName) {
    this.instanceId = instanceId;
    this.projectInitializationAction = projectInitializationAction;
    this.workQueue = workQueue;
    this.fetchJobFactory = fetchJobFactory;
    this.metricsProvider = metricsProvider;
    this.eventsBroker = eventsBroker;
    this.eventListener = eventListener;
    this.eventsTopicName = eventsTopicName;

    requireNonNull(
        Strings.emptyToNull(this.instanceId), "gerrit.instanceId cannot be null or empty");
  }

  @Override
  public void accept(Event event) {
    eventListener.onEvent(event);
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
