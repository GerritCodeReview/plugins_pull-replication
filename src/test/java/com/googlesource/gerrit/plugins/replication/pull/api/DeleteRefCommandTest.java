// Copyright (C) 2022 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.LocalGitRepositoryManagerProvider;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState.RefFetchResult;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DeleteRefCommandTest {
  private static final String TEST_SOURCE_LABEL = "test-source-label";
  private static final String TEST_REF_NAME = "refs/changes/01/1/1";

  private static final String NON_EXISTING_REF_NAME = "refs/changes/01/11101/1";
  private static final NameKey TEST_PROJECT_NAME = Project.nameKey("test-project");

  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDataItem;
  @Mock private EventDispatcher eventDispatcher;
  @Mock private ProjectCache projectCache;
  @Mock private ApplyObject applyObject;
  @Mock private ProjectState projectState;
  @Mock private LocalDiskRepositoryManager gitManager;
  @Mock private RefUpdate refUpdate;
  @Mock private Repository repository;
  @Mock private Ref currentRef;
  @Mock private RefDatabase refDb;

  @Captor ArgumentCaptor<Event> eventCaptor;

  private DeleteRefCommand objectUnderTest;

  @Before
  public void setup() throws Exception {
    when(eventDispatcherDataItem.get()).thenReturn(eventDispatcher);
    when(projectCache.get(any())).thenReturn(Optional.of(projectState));
    when(gitManager.openRepository(any())).thenReturn(repository);
    when(repository.updateRef(any())).thenReturn(refUpdate);
    when(repository.getRefDatabase()).thenReturn(refDb);
    when(refDb.exactRef(anyString())).thenReturn(currentRef);
    when(refUpdate.delete()).thenReturn(Result.FORCED);

    objectUnderTest =
        new DeleteRefCommand(
            fetchStateLog,
            projectCache,
            applyObject,
            eventDispatcherDataItem,
            new LocalGitRepositoryManagerProvider(gitManager));
  }

  @Test
  public void shouldSendEventWhenDeletingRef() throws Exception {
    objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);

    verify(eventDispatcher).postEvent(eventCaptor.capture());
    Event sentEvent = eventCaptor.getValue();
    assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
    FetchRefReplicatedEvent fetchEvent = (FetchRefReplicatedEvent) sentEvent;
    assertThat(fetchEvent.getProjectNameKey()).isEqualTo(TEST_PROJECT_NAME);
    assertThat(fetchEvent.getRefName()).isEqualTo(TEST_REF_NAME);
  }

  @Test
  public void shouldNotThrowNPEWhenDeletingNonExistingRef() throws Exception {
    when(refDb.exactRef(anyString())).thenReturn(null);

    objectUnderTest.deleteRef(TEST_PROJECT_NAME, NON_EXISTING_REF_NAME, TEST_SOURCE_LABEL);

    verify(eventDispatcher).postEvent(eventCaptor.capture());
    Event sentEvent = eventCaptor.getValue();
    assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
    FetchRefReplicatedEvent fetchEvent = (FetchRefReplicatedEvent) sentEvent;
    assertThat(fetchEvent.getProjectNameKey()).isEqualTo(TEST_PROJECT_NAME);
    assertThat(fetchEvent.getRefName()).isEqualTo(NON_EXISTING_REF_NAME);
    assertThat(fetchEvent.getStatus()).isEqualTo(RefFetchResult.NOT_ATTEMPTED.toString());
  }
}
