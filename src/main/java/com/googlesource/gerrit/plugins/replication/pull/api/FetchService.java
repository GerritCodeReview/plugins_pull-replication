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
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.eclipse.jgit.transport.URIish;

public class FetchService implements Command {

  private ReplicationState.Factory fetchReplicationStateFactory;
  private PullReplicationStateLogger fetchStateLog;
  SourcesCollection sources;

  @Inject
  public FetchService(
      ReplicationState.Factory fetchReplicationStateFactory,
      PullReplicationStateLogger fetchStateLog,
      SourcesCollection sources) {
    this.fetchReplicationStateFactory = fetchReplicationStateFactory;
    this.fetchStateLog = fetchStateLog;
    this.sources = sources;
  }

  public void fetch(Project.NameKey name, URIish url, String sha1)
      throws InterruptedException, ExecutionException {
    ReplicationState state =
        fetchReplicationStateFactory.create(new FetchResultProcessing.CommandProcessing(this));
    Optional<Source> source =
        sources.getAll().stream().filter(s -> s.getURIs(name, null).contains(url)).findFirst();
    if (!source.isPresent()) {
      fetchStateLog.warn("Unknown configuration section for:" + url.getPath(), state);
    }
    Future<?> future = source.get().schedule(name, sha1, url, state, true);
    state.markAllFetchTasksScheduled();
    if (future != null) {
      try {
        future.get();
      } catch (InterruptedException e) {
        fetchStateLog.error(
            "Thread was interrupted while waiting for FetchAll operation to finish", e, state);
        throw e;
      } catch (ExecutionException e) {
        fetchStateLog.error("An exception was thrown in FetchAll operation", e, state);
        throw e;
      }
    }

    if (state.hasFetchTask()) {
      try {
        state.waitForReplication();
      } catch (InterruptedException e) {
        writeStdErrSync("We are interrupted while waiting replication to complete");
        throw e;
      }
    } else {
      writeStdOutSync("Nothing to replicate");
    }
  }

  @Override
  public void writeStdOutSync(String message) {}

  @Override
  public void writeStdErrSync(String message) {}
}
