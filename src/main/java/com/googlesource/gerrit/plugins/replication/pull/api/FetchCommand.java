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
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationType;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.RefSpec;

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
          TimeoutException, TransportException {
    fetch(name, label, refName, ASYNC, Optional.of(apiRequestMetrics));
  }

  public void fetchSync(Project.NameKey name, String label, String refName)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException, TransportException {
    fetch(name, label, refName, SYNC, Optional.empty());
  }

  private void fetch(
      Project.NameKey name,
      String label,
      String refName,
      ReplicationType fetchType,
      Optional<PullReplicationApiRequestMetrics> apiRequestMetrics)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException, TransportException {
    ReplicationState state =
        fetchReplicationStateFactory.create(
            new FetchResultProcessing.CommandProcessing(this, eventDispatcher.get()));

    Optional<Source> source =
        sources.getAll().stream().filter(s -> s.getRemoteConfigName().equals(label)).findFirst();
    if (!source.isPresent()) {
      String msg = String.format("Remote configuration section %s not found", label);
      fetchStateLog.error(msg, state);
      throw new RemoteConfigurationMissingException(msg);
    }

    try {
      if (fetchType == ReplicationType.ASYNC) {
        state.markAllFetchTasksScheduled();
        Future<?> future = source.get().schedule(name, refName, state, apiRequestMetrics);
        future.get(source.get().getTimeout(), TimeUnit.SECONDS);
      } else {
        Optional<FetchOne> maybeFetch =
            source
                .get()
                .fetchSync(name, refName, source.get().getURI(name), state, apiRequestMetrics);
        if (maybeFetch.map(FetchOne::getFetchRefSpecs).filter(List::isEmpty).isPresent()) {
          fetchStateLog.error(
              String.format(
                  "[%s] Nothing to fetch, ref-specs is empty", maybeFetch.get().getTaskIdHex()));
        } else if (maybeFetch.map(fetch -> !fetch.hasSucceeded()).orElse(false)) {
          throw newTransportException(maybeFetch.get());
        }
      }
    } catch (ExecutionException
        | IllegalStateException
        | TimeoutException
        | InterruptedException e) {
      fetchStateLog.error("Exception during the fetch operation", e, state);
      throw e;
    }

    try {
      if (fetchType == ReplicationType.ASYNC) {
        state.waitForReplication(source.get().getTimeout());
      }
    } catch (InterruptedException e) {
      writeStdErrSync("We are interrupted while waiting replication to complete");
      throw e;
    }
  }

  private TransportException newTransportException(FetchOne fetchOne) {
    List<RefSpec> fetchRefSpecs = fetchOne.getFetchRefSpecs();
    String combinedErrorMessage =
        fetchOne.getFetchFailures().stream()
            .map(TransportException::getMessage)
            .reduce("", (e1, e2) -> e1 + "\n" + e2);
    return new TransportException(combinedErrorMessage + " trying to fetch " + fetchRefSpecs);
  }

  @Override
  public void writeStdOutSync(String message) {}

  @Override
  public void writeStdErrSync(String message) {}
}
