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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.fetch.DeleteRef;
import java.util.Optional;
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
  private static final NameKey TEST_PROJECT_NAME = Project.nameKey("test-project");

  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDataItem;
  @Mock private EventDispatcher eventDispatcher;
  @Mock private ProjectCache projectCache;
  @Mock private DeleteRef deleteRef;
  @Mock private ProjectState projectState;
  @Mock private PermissionBackend permissionBackend;
  @Mock private Provider<CurrentUser> userProvider;
  @Mock private CurrentUser currentUser;
  @Mock private PermissionBackend.WithUser userPermission;
  @Mock private PermissionBackend.ForProject projectsPermission;
  @Mock private PermissionBackend.ForRef refPermission;

  @Captor ArgumentCaptor<Event> eventCaptor;

  private DeleteRefCommand objectUnderTest;

  @Before
  public void setup() {
    when(eventDispatcherDataItem.get()).thenReturn(eventDispatcher);
    when(projectCache.get(any())).thenReturn(Optional.of(projectState));
    when(projectState.getNameKey()).thenReturn(TEST_PROJECT_NAME);
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isInternalUser()).thenReturn(true);
    objectUnderTest =
        new DeleteRefCommand(
            fetchStateLog,
            projectCache,
            deleteRef,
            eventDispatcherDataItem,
            permissionBackend,
            userProvider);
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
  public void shouldCheckDeletePermissionForNonInternalUser() throws Exception {
    when(currentUser.isInternalUser()).thenReturn(false);

    when(permissionBackend.user(currentUser)).thenReturn(userPermission);
    when(userPermission.project(TEST_PROJECT_NAME)).thenReturn(projectsPermission);
    when(projectsPermission.ref(TEST_REF_NAME)).thenReturn(refPermission);

    objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);

    verify(refPermission).check(RefPermission.DELETE);
  }

  @Test
  public void shouldSkipkDeletePermissionCheckForInternalUser() throws Exception {
    when(currentUser.isInternalUser()).thenReturn(true);

    objectUnderTest.deleteRef(TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);

    verify(refPermission, never()).check(RefPermission.DELETE);
  }
}
