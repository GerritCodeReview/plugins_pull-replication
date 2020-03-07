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

import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.Command;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FetchCommand implements Command {

  private ReplicationState.Factory fetchReplicationStateFactory;
  private PullReplicationStateLogger fetchStateLog;
  private SourcesCollection sources;

  @Inject
  public FetchCommand(
      ReplicationState.Factory fetchReplicationStateFactory,
      PullReplicationStateLogger fetchStateLog,
      SourcesCollection sources) {
    this.fetchReplicationStateFactory = fetchReplicationStateFactory;
    this.fetchStateLog = fetchStateLog;
    this.sources = sources;
  }

  public void fetch(Project.NameKey name, String label, String refName)
      throws InterruptedException, ExecutionException, RemoteConfigurationMissingException,
          TimeoutException {
    ReplicationState state =
        fetchReplicationStateFactory.create(new FetchResultProcessing.CommandProcessing(this));
    Optional<Source> source =
        sources.getAll().stream().filter(s -> s.getRemoteConfigName().equals(label)).findFirst();
    if (!source.isPresent()) {
      String msg = String.format("Remote configuration section %s not found", label);
      fetchStateLog.error(msg, state);
      throw new RemoteConfigurationMissingException(msg);
    }

    try {
      Future<?> future = source.get().schedule(name, refName, state, true);
      state.markAllFetchTasksScheduled();
      future.get(source.get().getTimeout(), TimeUnit.SECONDS);
    } catch (ExecutionException
        | IllegalStateException
        | TimeoutException
        | InterruptedException e) {
      fetchStateLog.error("Exception during the fetch operation", e, state);
      throw e;
    }

    try {
      state.waitForReplication(source.get().getTimeout());
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
