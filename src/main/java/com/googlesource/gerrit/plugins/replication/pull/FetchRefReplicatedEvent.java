// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.events.RemoteRefReplicationEvent;
import java.util.Objects;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;

public class FetchRefReplicatedEvent extends RemoteRefReplicationEvent {
  static final String TYPE = "fetch-ref-replicated";

  final RefUpdate.Result refUpdateResult;

  public FetchRefReplicatedEvent(
      String project,
      String ref,
      URIish sourceUri,
      ReplicationState.RefFetchResult status,
      RefUpdate.Result refUpdateResult) {
    super(TYPE, project, ref, sourceUri, status.toString());
    this.refUpdateResult = refUpdateResult;
  }

  public String getStatus() {
    return status;
  }

  @Override
  public int hashCode() {
    return Objects.hash(project, ref, status, refUpdateResult);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof FetchRefReplicatedEvent)) {
      return false;
    }
    FetchRefReplicatedEvent event = (FetchRefReplicatedEvent) other;
    if (!Objects.equals(event.project, this.project)) {
      return false;
    }
    if (!Objects.equals(event.ref, this.ref)) {
      return false;
    }
    if (!Objects.equals(event.targetUri, this.targetUri)) {
      return false;
    }
    if (!Objects.equals(event.status, this.status)) {
      return false;
    }
    return Objects.equals(event.refUpdateResult, this.refUpdateResult);
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }

  @Override
  public String getRefName() {
    return ref;
  }

  @VisibleForTesting
  public RefUpdate.Result getRefUpdateResult() {
    return refUpdateResult;
  }
}
