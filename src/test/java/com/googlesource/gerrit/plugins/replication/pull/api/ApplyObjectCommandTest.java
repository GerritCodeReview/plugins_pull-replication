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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.ApplyObjectMetrics;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ApplyObjectCommandTest {
  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private ApplyObject applyObject;
  @Mock private ApplyObjectMetrics metrics;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDataItem;
  @Mock private EventDispatcher eventDispatcher;
  @Mock private Timer1.Context<String> timetContext;
  @Captor ArgumentCaptor<Event> eventCaptor;

  private ApplyObjectCommand objectUnderTest;

  @Before
  public void setup() throws MissingParentObjectException, IOException {
    RefUpdateState state = new RefUpdateState("test_remote_name", RefUpdate.Result.NEW);
    when(eventDispatcherDataItem.get()).thenReturn(eventDispatcher);
    when(metrics.start(Mockito.anyString())).thenReturn(timetContext);
    when(timetContext.stop()).thenReturn(100L);
    when(applyObject.apply(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(state);

    objectUnderTest =
        new ApplyObjectCommand(fetchStateLog, applyObject, metrics, eventDispatcherDataItem);
  }

  @Test
  public void shouldEventEventWhenApplyObject()
      throws PermissionBackendException, IOException, RefUpdateException,
          MissingParentObjectException {
    objectUnderTest.applyObject(
        Project.nameKey("test-project"),
        "refs/changes/01/1/1",
        createSampleRevisionData(),
        "test-source-label");

    Mockito.verify(eventDispatcher).postEvent(eventCaptor.capture());
    Event sentEvent = eventCaptor.getValue();
    assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
  }

  private RevisionData createSampleRevisionData() {
    RevisionObjectData commitData = new RevisionObjectData(Constants.OBJ_COMMIT, new byte[] {});
    RevisionObjectData treeData = new RevisionObjectData(Constants.OBJ_TREE, new byte[] {});
    return new RevisionData(commitData, treeData, Lists.newArrayList());
  }
}
