// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.ReplicationType.ASYNC;
import static com.googlesource.gerrit.plugins.replication.pull.ReplicationType.SYNC;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.Command;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationType;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FetchCommand implements Command {

  private ReplicationState.Factory fetchReplicationStateFactory;
  private PullReplicationStateLogger fetchStateLog;
  private SourcesCollection sources;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  @Inject
  public FetchCommand(
      ReplicationState.Factory fetchReplicationStateFactory,
      PullReplicationStateLogger fetchStateLog,
      SourcesCollection sources,
      DynamicItem<EventDispatcher> eventDispatcher) {
    this.fetchReplicationStateFactory = fetchReplicationStateFactory;
    this.fetchStateLog = fetchStateLog;
    this.sources = sources;
    this.eventDispatcher = eventDispatcher;
  }

  public void fetchAsync(
      Project.NameKey name,
      String label,
      String refName,
      PullReplicationApiRequestMetrics apiRequestMetrics)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    fetch(name, label, Set.of(refName), ASYNC, Optional.of(apiRequestMetrics));
  }

  public void fetchSync(Project.NameKey name, String label, String refName)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    fetch(name, label, Set.of(refName), SYNC, Optional.empty());
  }

  private void fetch(
      Project.NameKey name,
      String label,
      Set<String> refsNames,
      ReplicationType fetchType,
      Optional<PullReplicationApiRequestMetrics> apiRequestMetrics)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    ReplicationState state =
        fetchType == ReplicationType.ASYNC
            ? fetchReplicationStateFactory.create(
                new FetchResultProcessing.CommandProcessing(this, eventDispatcher.get()))
            : null;
    Optional<Source> source = sources.getByRemoteName(label);
    if (!source.isPresent()) {
      String msg = String.format("Remote configuration section %s not found", label);
      fetchStateLog.error(msg, state);
      throw new RemoteConfigurationMissingException(msg);
    }

    try {
      if (state != null) {
        state.markAllFetchTasksScheduled();
        Future<?> future = null;
        for (String refName : refsNames) {
        	future = source.get().schedule(name, refName, state, fetchType, apiRequestMetrics);			
		}
        int timeout = source.get().getTimeout();
        if (timeout == 0) {
          if (future != null) { 
        	  future.get();
          }
        } else {
        	if(future != null) {
          future.get(timeout, TimeUnit.SECONDS);
        	}
        }
      } else {
        source.get().fetchSync(name, refsNames, apiRequestMetrics);
      }
    } catch (ExecutionException
        | IllegalStateException
        | TimeoutException
        | InterruptedException e) {
      fetchStateLog.error("Exception during the fetch operation", e, state);
      throw e;
    }

    try {
      if (state != null) {
        state.waitForReplication(source.get().getTimeout());
      }
    } catch (InterruptedException e) {
      writeStdErrSync("We are interrupted while waiting replication to complete");
      throw e;
    }
  }

  @Override
  public void writeStdOutSync(String message) {}

  @Override
  public void writeStdErrSync(String message) {}
}
