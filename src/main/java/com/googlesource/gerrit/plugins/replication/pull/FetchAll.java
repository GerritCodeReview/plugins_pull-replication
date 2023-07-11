// Copyright (C) 2009 The Android Open Source Project
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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.ReplicationFilter;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.transport.URIish;

public class FetchAll implements Runnable {
  private final ReplicationStateListener stateLog;

  public interface Factory {
    FetchAll create(
        String urlMatch,
        ReplicationFilter filter,
        ReplicationState state,
        ReplicationType replicationType);
  }

  private final WorkQueue workQueue;
  private final ProjectCache projectCache;
  private final String urlMatch;
  private final ReplicationFilter filter;
  private final ReplicationState state;
  private final ReplicationType replicationType;
  private final SourcesCollection sources;

  @Inject
  protected FetchAll(
      WorkQueue wq,
      ProjectCache projectCache,
      ReplicationStateListeners stateLog,
      SourcesCollection sources,
      @Assisted @Nullable String urlMatch,
      @Assisted ReplicationFilter filter,
      @Assisted ReplicationState state,
      @Assisted ReplicationType replicationType) {
    this.workQueue = wq;
    this.projectCache = projectCache;
    this.stateLog = stateLog;
    this.sources = sources;
    this.urlMatch = urlMatch;
    this.filter = filter;
    this.state = state;
    this.replicationType = replicationType;
  }

  Future<?> schedule(long delay, TimeUnit unit) {
    return workQueue.getDefaultQueue().schedule(this, delay, unit);
  }

  @Override
  public void run() {
    try {
      for (Project.NameKey nameKey : projectCache.all()) {
        if (filter.matches(nameKey)) {
          scheduleFullSync(nameKey, urlMatch, state, replicationType);
        }
      }
    } catch (Exception e) {
      stateLog.error("Cannot enumerate known projects", e, state);
    }
    state.markAllFetchTasksScheduled();
  }

  private void scheduleFullSync(
      Project.NameKey project,
      String urlMatch,
      ReplicationState state,
      ReplicationType replicationType) {

    for (Source cfg : sources.getAll()) {
      if (cfg.wouldFetchProject(project)) {
        for (URIish uri : cfg.getURIs(project, urlMatch)) {
          cfg.schedule(
              project, FetchOne.ALL_REFS, uri, state, replicationType, Optional.empty(), false);
        }
      }
    }
  }

  @Override
  public String toString() {
    String s = "Replicate All Projects";
    if (urlMatch != null) {
      s = s + " from " + urlMatch;
    }
    return s;
  }
}
