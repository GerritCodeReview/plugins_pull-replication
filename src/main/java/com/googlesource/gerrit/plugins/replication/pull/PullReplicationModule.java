// Copyright (C) 2012 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull;

import static com.googlesource.gerrit.plugins.replication.StartReplicationCapability.START_REPLICATION;
import static com.googlesource.gerrit.plugins.replication.pull.api.FetchApiCapability.CALL_FETCH_ACTION;

import com.google.common.eventbus.EventBus;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.EventTypes;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.google.inject.internal.UniqueAnnotations;
import com.google.inject.name.Names;
import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;
import com.googlesource.gerrit.plugins.replication.AutoReloadSecureCredentialsFactoryDecorator;
import com.googlesource.gerrit.plugins.replication.ConfigParser;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigModule;
import com.googlesource.gerrit.plugins.replication.StartReplicationCapability;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchApiCapability;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupModule;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpClient;
import com.googlesource.gerrit.plugins.replication.pull.client.SourceHttpClient;
import com.googlesource.gerrit.plugins.replication.pull.event.EventsBrokerConsumerModule;
import com.googlesource.gerrit.plugins.replication.pull.event.StreamEventModule;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import org.eclipse.jgit.lib.Config;

class PullReplicationModule extends AbstractModule {

  private final MetricMaker pluginMetricMaker;
  private final ReplicationConfigModule configModule;

  @Inject
  public PullReplicationModule(
      ReplicationConfigModule configModule, MetricMaker pluginMetricMaker) {
    this.configModule = configModule;
    this.pluginMetricMaker = pluginMetricMaker;
  }

  @Override
  protected void configure() {
    bind(MetricMaker.class)
        .annotatedWith(Names.named(ReplicationQueueMetrics.REPLICATION_QUEUE_METRICS))
        .toInstance(pluginMetricMaker);

    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(CALL_FETCH_ACTION))
        .to(FetchApiCapability.class);

    install(configModule);
    install(new PullReplicationGroupModule());
    bind(BearerTokenProvider.class).in(Scopes.SINGLETON);
    bind(RevisionReader.class).in(Scopes.SINGLETON);
    bind(ApplyObject.class);
    install(new FactoryModuleBuilder().build(FetchJob.Factory.class));
    install(new ApplyObjectCacheModule());

    install(
        new FactoryModuleBuilder()
            .implement(HttpClient.class, SourceHttpClient.class)
            .build(SourceHttpClient.Factory.class));

    install(new FactoryModuleBuilder().build(Source.Factory.class));
    install(
        new FactoryModuleBuilder()
            .implement(FetchApiClient.class, FetchRestApiClient.class)
            .build(FetchApiClient.Factory.class));

    bind(FetchReplicationMetrics.class).in(Scopes.SINGLETON);

    bind(OnStartStop.class).in(Scopes.SINGLETON);
    bind(LifecycleListener.class).annotatedWith(UniqueAnnotations.create()).to(OnStartStop.class);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(PullReplicationLogFile.class);
    bind(CredentialsFactory.class)
        .to(AutoReloadSecureCredentialsFactoryDecorator.class)
        .in(Scopes.SINGLETON);
    bind(CapabilityDefinition.class)
        .annotatedWith(Exports.named(START_REPLICATION))
        .to(StartReplicationCapability.class);

    install(new FactoryModuleBuilder().build(FetchAll.Factory.class));
    install(new FactoryModuleBuilder().build(ReplicationState.Factory.class));

    bind(EventBus.class).in(Scopes.SINGLETON);
    bind(ReplicationSources.class).to(SourcesCollection.class);
    DynamicSet.bind(binder(), ProjectDeletedListener.class).to(ReplicationQueue.class);
    DynamicSet.bind(binder(), HeadUpdatedListener.class).to(ReplicationQueue.class);

    bind(ReplicationQueue.class).in(Scopes.SINGLETON);
    bind(ObservableQueue.class).to(ReplicationQueue.class);
    bind(LifecycleListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(ReplicationQueue.class);

    DynamicSet.bind(binder(), EventListener.class).to(ReplicationQueue.class);

    bind(ConfigParser.class).to(SourceConfigParser.class).in(Scopes.SINGLETON);

    Config replicationConfig = configModule.getReplicationConfig();
    String eventBrokerTopic = replicationConfig.getString("replication", null, "eventBrokerTopic");
    if (replicationConfig.getBoolean("replication", "consumeStreamEvents", false)) {
      install(new StreamEventModule());
    } else if (eventBrokerTopic != null) {
      install(new EventsBrokerConsumerModule(eventBrokerTopic, replicationConfig));
    }

    DynamicSet.setOf(binder(), ReplicationStateListener.class);
    DynamicSet.bind(binder(), ReplicationStateListener.class).to(PullReplicationStateLogger.class);
    EventTypes.register(FetchRefReplicatedEvent.TYPE, FetchRefReplicatedEvent.class);
    EventTypes.register(FetchRefReplicationDoneEvent.TYPE, FetchRefReplicationDoneEvent.class);
    EventTypes.register(FetchReplicationScheduledEvent.TYPE, FetchReplicationScheduledEvent.class);

    DynamicSet.bind(binder(), HealthCheck.class).to(CustomPullReplicationCheck.class);
  }
}
