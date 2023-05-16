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

import static com.googlesource.gerrit.plugins.replication.pull.ReplicationType.ASYNC;

import com.google.common.util.concurrent.Atomics;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.systemstatus.ServerInformation;
import com.google.gerrit.server.config.GerritIsReplica;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFilter;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class OnStartStop implements LifecycleListener {
  private final AtomicReference<Future<?>> fetchAllFuture;
  private final ServerInformation srvInfo;
  private final FetchAll.Factory fetchAll;
  private final ReplicationConfig config;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final ReplicationState.Factory replicationStateFactory;
  private final SourcesCollection sourcesCollection;
  private final WorkQueue workQueue;
  private boolean isReplica;

  @Inject
  protected OnStartStop(
      ServerInformation srvInfo,
      FetchAll.Factory fetchAll,
      ReplicationConfig config,
      DynamicItem<EventDispatcher> eventDispatcher,
      ReplicationState.Factory replicationStateFactory,
      SourcesCollection sourcesCollection,
      WorkQueue workQueue,
      @GerritIsReplica Boolean isReplica) {
    this.srvInfo = srvInfo;
    this.fetchAll = fetchAll;
    this.config = config;
    this.eventDispatcher = eventDispatcher;
    this.replicationStateFactory = replicationStateFactory;
    this.fetchAllFuture = Atomics.newReference();
    this.sourcesCollection = sourcesCollection;
    this.workQueue = workQueue;
    this.isReplica = isReplica;
  }

  @Override
  public void start() {
    if (isReplica
        && srvInfo.getState() == ServerInformation.State.STARTUP
        && config.isReplicateAllOnPluginStart()) {
      ReplicationState state =
          replicationStateFactory.create(
              new FetchResultProcessing.GitUpdateProcessing(eventDispatcher.get()));
      fetchAllFuture.set(
          fetchAll
              .create(null, ReplicationFilter.all(), state, ASYNC)
              .schedule(30, TimeUnit.SECONDS));
    }

    sourcesCollection.startup(workQueue);
  }

  @Override
  public void stop() {
    Future<?> f = fetchAllFuture.getAndSet(null);
    if (f != null) {
      f.cancel(true);
    }
  }
}
