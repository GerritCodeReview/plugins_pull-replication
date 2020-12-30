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
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.Before;
import org.junit.Test;

public class FetchRefReplicatedEventHandlerTest {
  private ChangeIndexer changeIndexerMock;
  private FetchRefReplicatedEventHandler fetchRefReplicatedEventHandler;

  @Before
  public void setUp() throws Exception {
    changeIndexerMock = mock(ChangeIndexer.class);
    fetchRefReplicatedEventHandler = new FetchRefReplicatedEventHandler(changeIndexerMock);
  }

  @Test
  public void onEventShouldIndexExistingChange() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "refs/changes/41/41/meta";
    Change.Id changeId = Change.Id.fromRef(ref);
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.toString(),
            ref,
            "aSourceNode",
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, times(1)).index(eq(projectNameKey), eq(changeId));
  }

  @Test
  public void onEventShouldNotIndexMissingChange() {
    Project.NameKey projectNameKey = Project.nameKey("testProject");
    String ref = "invalidRef";
    fetchRefReplicatedEventHandler.onEvent(
        new FetchRefReplicatedEvent(
            projectNameKey.toString(),
            ref,
            "aSourceNode",
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FAST_FORWARD));
    verify(changeIndexerMock, never()).index(any(), any());
  }
}
