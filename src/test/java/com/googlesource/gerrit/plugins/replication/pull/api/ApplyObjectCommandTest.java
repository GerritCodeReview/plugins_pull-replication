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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.pull.ApplyObjectMetrics;
import com.googlesource.gerrit.plugins.replication.pull.ApplyObjectsCacheKey;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Collections;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ApplyObjectCommandTest {
  private static final String TEST_SOURCE_LABEL = "test-source-label";
  private static final String TEST_REF_NAME = "refs/changes/01/1/1";
  private static final NameKey TEST_PROJECT_NAME = Project.nameKey("test-project");
  private static final String TEST_REMOTE_NAME = "test-remote-name";

  private String sampleCommitObjectId = "9f8d52853089a3cf00c02ff7bd0817bd4353a95a";
  private String sampleTreeObjectId = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
  private String sampleBlobObjectId = "b5d7bcf1d1c5b0f0726d10a16c8315f06f900bfb";

  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private ApplyObject applyObject;
  @Mock private ApplyObjectMetrics metrics;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDataItem;
  @Mock private EventDispatcher eventDispatcher;
  @Mock private Timer1.Context<String> timetContext;
  @Captor ArgumentCaptor<Event> eventCaptor;
  private Cache<ApplyObjectsCacheKey, Boolean> cache;

  private ApplyObjectCommand objectUnderTest;

  @Before
  public void setup() throws MissingParentObjectException, IOException {
    cache = CacheBuilder.newBuilder().build();
    RefUpdateState state = new RefUpdateState(TEST_REMOTE_NAME, RefUpdate.Result.NEW);
    when(eventDispatcherDataItem.get()).thenReturn(eventDispatcher);
    when(metrics.start(anyString())).thenReturn(timetContext);
    when(timetContext.stop()).thenReturn(100L);
    when(applyObject.apply(any(), any(), any())).thenReturn(state);

    objectUnderTest =
        new ApplyObjectCommand(fetchStateLog, applyObject, metrics, eventDispatcherDataItem, cache);
  }

  @Test
  public void shouldSendEventWhenApplyObject()
      throws PermissionBackendException, IOException, RefUpdateException,
          MissingParentObjectException {
    RevisionData sampleRevisionData = createSampleRevisionData();
    objectUnderTest.applyObject(
        TEST_PROJECT_NAME, TEST_REF_NAME, sampleRevisionData, TEST_SOURCE_LABEL);

    verify(eventDispatcher).postEvent(eventCaptor.capture());
    Event sentEvent = eventCaptor.getValue();
    assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
    FetchRefReplicatedEvent fetchEvent = (FetchRefReplicatedEvent) sentEvent;
    assertThat(fetchEvent.getProjectNameKey()).isEqualTo(TEST_PROJECT_NAME);
    assertThat(fetchEvent.getRefName()).isEqualTo(TEST_REF_NAME);
    assertThat(
            cache.getIfPresent(ApplyObjectsCacheKey.create(sampleRevisionData.getCommitObject().getSha1(), TEST_REF_NAME, TEST_PROJECT_NAME.get()))
    ).isTrue();
  }

  private RevisionData createSampleRevisionData() {
    RevisionObjectData commitData =
        new RevisionObjectData(sampleCommitObjectId, Constants.OBJ_COMMIT, new byte[] {});
    RevisionObjectData treeData =
        new RevisionObjectData(sampleTreeObjectId, Constants.OBJ_TREE, new byte[] {});
    return new RevisionData(Collections.emptyList(), commitData, treeData, Lists.newArrayList());
  }
}
