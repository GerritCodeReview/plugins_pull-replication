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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackend.ForProject;
import com.google.gerrit.server.permissions.PermissionBackend.ForRef;
import com.google.gerrit.server.permissions.PermissionBackend.WithUser;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.LocalGitRepositoryManagerProvider;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;
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
  private static URIish TEST_REMOTE_URI;

  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDataItem;
  @Mock private EventDispatcher eventDispatcher;
  @Mock private ProjectCache projectCache;
  @Mock private ApplyObject applyObject;
  @Mock private ProjectState projectState;
  @Mock private SourcesCollection sourceCollection;
  @Mock private Source source;
  @Mock private WithUser currentUser;
  @Mock private ForProject forProject;
  @Mock private ForRef forRef;
  @Mock private LocalDiskRepositoryManager gitManager;
  @Mock private RefUpdate refUpdate;
  @Mock private Repository repository;
  @Mock private Ref currentRef;
  @Captor ArgumentCaptor<Event> eventCaptor;

  private DeleteRefCommand objectUnderTest;

  @Before
  public void setup() throws Exception {
    when(eventDispatcherDataItem.get()).thenReturn(eventDispatcher);
    when(projectCache.get(any())).thenReturn(Optional.of(projectState));
    when(sourceCollection.getByRemoteName(TEST_SOURCE_LABEL)).thenReturn(Optional.of(source));
    TEST_REMOTE_URI = new URIish("git://some.remote.uri");
    when(source.getURI(TEST_PROJECT_NAME)).thenReturn(TEST_REMOTE_URI);
    when(gitManager.openRepository(any())).thenReturn(repository);
    when(repository.updateRef(any())).thenReturn(refUpdate);
    when(repository.exactRef(anyString())).thenReturn(currentRef);
    when(refUpdate.delete()).thenReturn(Result.FORCED);

    objectUnderTest =
        new DeleteRefCommand(
            fetchStateLog,
            projectCache,
            sourceCollection,
            eventDispatcherDataItem,
            new LocalGitRepositoryManagerProvider(gitManager));
  }

  @Test
  public void shouldSendEventWhenDeletingRef() throws Exception {
    when(source.isMirror()).thenReturn(true);

    objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);

    verify(eventDispatcher).postEvent(eventCaptor.capture());
    Event sentEvent = eventCaptor.getValue();
    assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
    FetchRefReplicatedEvent fetchEvent = (FetchRefReplicatedEvent) sentEvent;
    assertThat(fetchEvent.getProjectNameKey()).isEqualTo(TEST_PROJECT_NAME);
    assertThat(fetchEvent.getRefName()).isEqualTo(TEST_REF_NAME);
    assertThat(fetchEvent.getStatus())
        .isEqualTo(ReplicationState.RefFetchResult.SUCCEEDED.toString());
    assertThat(fetchEvent.getRefUpdateResult()).isEqualTo(Result.FORCED);
  }

  @Test
  public void shouldNotSendNotSendEventWhenMirroringIsDisabled() throws Exception {
    when(source.isMirror()).thenReturn(false);

    objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);

    verifyNoInteractions(eventDispatcher);
  }

  @Test
  public void shouldHandleNonExistingRef() throws Exception {
    when(source.isMirror()).thenReturn(true);
    when(repository.exactRef(anyString())).thenReturn(null);
    objectUnderTest.deleteRef(TEST_PROJECT_NAME, NON_EXISTING_REF_NAME, TEST_SOURCE_LABEL);

    verify(eventDispatcher, never()).postEvent(any());
  }

  @Test
  public void shouldThrowWhenRefDeletionFails() throws Exception {
    when(source.isMirror()).thenReturn(true);
    when(refUpdate.delete()).thenReturn(Result.LOCK_FAILURE);

    assertThrows(
        IOException.class,
        () -> objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL));
  }

  @Test
  public void shouldSendFailureEventWhenDeletionFails() throws Exception {
    when(source.isMirror()).thenReturn(true);
    when(refUpdate.delete()).thenReturn(Result.LOCK_FAILURE);

    try {
      objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);
    } catch (Exception ignore) {
    } finally {
      verify(eventDispatcher).postEvent(eventCaptor.capture());
      Event sentEvent = eventCaptor.getValue();
      assertThat(sentEvent).isInstanceOf(FetchRefReplicatedEvent.class);
      FetchRefReplicatedEvent fetchEvent = (FetchRefReplicatedEvent) sentEvent;
      assertThat(fetchEvent.getProjectNameKey()).isEqualTo(TEST_PROJECT_NAME);
      assertThat(fetchEvent.getRefName()).isEqualTo(TEST_REF_NAME);
      assertThat(fetchEvent.getStatus())
          .isEqualTo(ReplicationState.RefFetchResult.FAILED.toString());
      assertThat(fetchEvent.getRefUpdateResult()).isEqualTo(Result.LOCK_FAILURE);
    }
  }
}
