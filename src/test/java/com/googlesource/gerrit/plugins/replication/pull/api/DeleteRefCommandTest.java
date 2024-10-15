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
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.googlesource.gerrit.plugins.replication.pull.LocalGitRepositoryManagerProvider;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DeleteRefCommandTest {
  private static final String TEST_TASK_ID = "task-id";
  private static final String TEST_SOURCE_LABEL = "test-source-label";
  private static final String TEST_REF_NAME = "refs/changes/01/1/1";

  private static final NameKey TEST_PROJECT_NAME = Project.nameKey("test-project");

  @Mock private PullReplicationStateLogger fetchStateLog;
  @Mock private ProjectCache projectCache;
  @Mock private ProjectState projectState;
  @Mock private SourcesCollection sourceCollection;
  @Mock private Source source;
  @Mock private LocalDiskRepositoryManager gitManager;
  @Mock private RefUpdate refUpdate;
  @Mock private Repository repository;
  @Mock private Ref currentRef;

  private DeleteRefCommand objectUnderTest;

  @Before
  public void setup() throws Exception {
    when(projectCache.get(any())).thenReturn(Optional.of(projectState));
    when(sourceCollection.getByRemoteName(TEST_SOURCE_LABEL)).thenReturn(Optional.of(source));
    when(gitManager.openRepository(any())).thenReturn(repository);
    when(repository.updateRef(any())).thenReturn(refUpdate);
    when(repository.exactRef(anyString())).thenReturn(currentRef);
    when(refUpdate.delete()).thenReturn(Result.FORCED);

    objectUnderTest =
        new DeleteRefCommand(
            fetchStateLog,
            projectCache,
            sourceCollection,
            new LocalGitRepositoryManagerProvider(gitManager));
  }

  @Test
  public void shouldReturnLockFailureWhenRefDeletionFails() throws Exception {
    when(source.isMirror()).thenReturn(true);
    when(refUpdate.delete()).thenReturn(Result.LOCK_FAILURE);

    RefUpdateState deleteRefResult =
        objectUnderTest.deleteRef(
            TEST_TASK_ID, TEST_PROJECT_NAME, TEST_REF_NAME, TEST_SOURCE_LABEL);
    assertThat(deleteRefResult.getResult()).isEqualTo(Result.LOCK_FAILURE);
  }
}
