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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.gerrit.server.events.EventDispatcher;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.CommandProcessing;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState.RefFetchResult;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class FetchGitUpdateProcessingTest {
  private EventDispatcher dispatcherMock;
  private GitUpdateProcessing gitUpdateProcessing;
  private CommandProcessing commandProcessing;
  private Command sshCommandMock;
  private static URIish sourceUri;

  @Before
  public void setUp() throws Exception {
    dispatcherMock = mock(EventDispatcher.class);
    gitUpdateProcessing = new GitUpdateProcessing(dispatcherMock);
    sshCommandMock = mock(Command.class);
    commandProcessing = new CommandProcessing(sshCommandMock, dispatcherMock);
    sourceUri = new URIish("git://someHost/someProject.git");
  }

  @Test
  public void headRefReplicatedInGitUpdateProcessing() throws Exception {
    FetchRefReplicatedEvent expectedEvent =
        new FetchRefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            sourceUri,
            RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);

    gitUpdateProcessing.onOneProjectReplicationDone(
        "someProject",
        "refs/heads/master",
        sourceUri,
        RefFetchResult.SUCCEEDED,
        RefUpdate.Result.NEW);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedEvent));
  }

  @Test
  public void headRefReplicatedInCommandProcessing() throws Exception {
    FetchRefReplicatedEvent expectedEvent =
        new FetchRefReplicatedEvent(
            "someProject",
            "refs/heads/master",
            sourceUri,
            RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);

    commandProcessing.onOneProjectReplicationDone(
        "someProject",
        "refs/heads/master",
        new URIish("git://someHost/someProject.git"),
        RefFetchResult.SUCCEEDED,
        RefUpdate.Result.NEW);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedEvent));
  }

  @Test
  public void changeRefReplicated() throws Exception {
    FetchRefReplicatedEvent expectedEvent =
        new FetchRefReplicatedEvent(
            "someProject",
            "refs/changes/01/1/1",
            sourceUri,
            RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_OTHER_REASON);

    gitUpdateProcessing.onOneProjectReplicationDone(
        "someProject",
        "refs/changes/01/1/1",
        new URIish("git://someHost/someProject.git"),
        RefFetchResult.FAILED,
        RefUpdate.Result.REJECTED_OTHER_REASON);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedEvent));
  }

  @Test
  public void onAllNodesReplicated() throws Exception {
    FetchRefReplicationDoneEvent expectedDoneEvent =
        new FetchRefReplicationDoneEvent("someProject", "refs/heads/master", 5);

    gitUpdateProcessing.onRefReplicatedFromAllNodes("someProject", "refs/heads/master", 5);
    verify(dispatcherMock, times(1)).postEvent(eq(expectedDoneEvent));
  }
}
