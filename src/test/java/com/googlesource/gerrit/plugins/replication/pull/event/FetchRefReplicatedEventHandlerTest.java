// Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class FetchRefReplicatedEventHandlerTest {
  private ChangeIndexer changeIndexerMock;
  private FetchRefReplicatedEventHandler fetchRefReplicatedEventHandler;
  private static URIish sourceUri;

  @Before
  public void setUp() throws Exception {
    changeIndexerMock = mock(ChangeIndexer.class);
    fetchRefReplicatedEventHandler = new FetchRefReplicatedEventHandler(changeIndexerMock);
    sourceUri = new URIish("git://aSourceNode/testProject.git");
  }

  @Test
  public void onEventShouldIndexExistingChange() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/meta";
    Change.Id changeId = Change.Id.fromRef(ref);
    try {
      Context.setLocalEvent(true);
      fetchRefReplicatedEventHandler.onEvent(
          new FetchRefReplicatedEvent(
              projectNameKey.get(),
              ref,
              sourceUri,
              ReplicationState.RefFetchResult.SUCCEEDED,
              RefUpdate.Result.FAST_FORWARD));
      verify(changeIndexerMock, times(1)).index(eq(projectNameKey), eq(changeId));
    } finally {
      Context.unsetLocalEvent();
    }
  }

  @Test
  public void onEventShouldNotIndexIfNotLocalEvent() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/meta";
    Change.Id changeId = Change.Id.fromRef(ref);
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.get(),
            ref,
            sourceUri,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(eq(projectNameKey), eq(changeId));
  }

  @Test
  public void onEventShouldIndexOnlyMetaRef() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/1";
    Change.Id changeId = Change.Id.fromRef(ref);
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.get(),
            ref,
            sourceUri,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(eq(projectNameKey), eq(changeId));
  }

  @Test
  public void onEventShouldNotIndexMissingChange() {
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            Project.nameKey("testProject").get(),
            "invalidRef",
            sourceUri,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(any(), any());
  }

  @Test
  public void onEventShouldNotIndexFailingChange() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/meta";
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.get(),
            ref,
            sourceUri,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(any(), any());
  }

  @Test
  public void onEventShouldNotIndexNotAttemptedChange() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/meta";
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.get(),
            ref,
            sourceUri,
            ReplicationState.RefFetchResult.NOT_ATTEMPTED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(any(), any());
  }
}
