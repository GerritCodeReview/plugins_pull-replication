// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Bytes;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.RevisionReader;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObjectIT$TestModule")
public class ApplyObjectIT extends LightweightPluginDaemonTest {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";

  @Inject private ProjectOperations projectOperations;
  @Inject ApplyObject objectUnderTest;
  RevisionReader reader;

  @Before
  public void setup() {
    reader = plugin.getSysInjector().getInstance(RevisionReader.class);
  }

  @Test
  public void shouldApplyRefMetaObject() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    Result pushResult = createChange();
    String refName = RefNames.changeMetaRef(pushResult.getChange().getId());

    Optional<RevisionData> revisionData =
        reader.read(
            Project.nameKey(testRepoProjectName), pushResult.getCommit().toObjectId(), refName, 0);

    RefSpec refSpec = new RefSpec(refName);
    objectUnderTest.apply(project, refSpec, toArray(revisionData));
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo); ) {
      Optional<RevisionData> newRevisionData =
          reader.read(project, repo.exactRef(refName).getObjectId(), refName, 0);
      compareObjects(revisionData.get(), newRevisionData);
      testRepo.fsck();
    }
  }

  @Test
  public void shouldApplyRefSequencesChanges() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    createChange();
    String seqChangesRef = RefNames.REFS_SEQUENCES + "changes";

    Optional<RevisionData> revisionData = reader.read(allProjects, seqChangesRef, 0);

    RefSpec refSpec = new RefSpec(seqChangesRef);
    objectUnderTest.apply(project, refSpec, toArray(revisionData));
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo); ) {

      Optional<RevisionData> newRevisionData =
          reader.read(project, repo.exactRef(seqChangesRef).getObjectId(), seqChangesRef, 0);
      compareObjects(revisionData.get(), newRevisionData);
      testRepo.fsck();
    }
  }

  @Test
  public void shouldApplyRefMetaObjectWithComments() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    Result pushResult = createChange();
    Change.Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(changeId);
    RefSpec refSpec = new RefSpec(refName);

    NameKey testRepoKey = Project.nameKey(testRepoProjectName);
    try (Repository repo = repoManager.openRepository(testRepoKey)) {
      Optional<RevisionData> revisionData =
          reader.read(testRepoKey, repo.exactRef(refName).getObjectId(), refName, 0);
      objectUnderTest.apply(project, refSpec, toArray(revisionData));
    }

    ReviewInput reviewInput = new ReviewInput();
    CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> testRepo = new TestRepository<>(repo)) {
      Optional<RevisionData> revisionDataWithComment =
          reader.read(testRepoKey, repo.exactRef(refName).getObjectId(), refName, 0);

      objectUnderTest.apply(project, refSpec, toArray(revisionDataWithComment));

      Optional<RevisionData> newRevisionData =
          reader.read(project, repo.exactRef(refName).getObjectId(), refName, 0);

      compareObjects(revisionDataWithComment.get(), newRevisionData);

      testRepo.fsck();
    }
  }

  @Test
  public void shouldThrowExceptionWhenParentCommitObjectIsMissing() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    NameKey createTestProject = createTestProject(testRepoProjectName);
    try (Repository repo = repoManager.openRepository(createTestProject)) {
      testRepo = cloneProject(createTestProject);

      Result pushResult = createChange();
      Change.Id changeId = pushResult.getChange().getId();
      String refName = RefNames.changeMetaRef(changeId);

      CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");
      ReviewInput reviewInput = new ReviewInput();
      reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
      gApi.changes().id(changeId.get()).current().review(reviewInput);

      Optional<RevisionData> revisionData =
          reader.read(createTestProject, repo.exactRef(refName).getObjectId(), refName, 0);

      RefSpec refSpec = new RefSpec(refName);
      assertThrows(
          MissingParentObjectException.class,
          () -> objectUnderTest.apply(project, refSpec, toArray(revisionData)));
    }
  }

  private void compareObjects(RevisionData expected, Optional<RevisionData> actualOption) {
    assertThat(actualOption.isPresent()).isTrue();
    RevisionData actual = actualOption.get();
    compareContent(expected.getCommitObject(), actual.getCommitObject());
    compareContent(expected.getTreeObject(), expected.getTreeObject());
    List<List<Byte>> actualBlobs =
        actual.getBlobs().stream()
            .map(revision -> Bytes.asList(revision.getContent()))
            .collect(Collectors.toList());
    List<List<Byte>> expectedBlobs =
        expected.getBlobs().stream()
            .map(revision -> Bytes.asList(revision.getContent()))
            .collect(Collectors.toList());
    assertThat(actualBlobs).containsExactlyElementsIn(expectedBlobs);
  }

  private void compareContent(RevisionObjectData expected, RevisionObjectData actual) {
    if (expected == actual) {
      return;
    }
    assertThat(actual.getType()).isEqualTo(expected.getType());
    assertThat(Bytes.asList(actual.getContent()))
        .containsExactlyElementsIn(Bytes.asList(expected.getContent()))
        .inOrder();
  }

  private CommentInput createCommentInput(
      int startLine, int startCharacter, int endLine, int endCharacter, String message) {
    CommentInput comment = new CommentInput();
    comment.range = new Comment.Range();
    comment.range.startLine = startLine;
    comment.range.startCharacter = startCharacter;
    comment.range.endLine = endLine;
    comment.range.endCharacter = endCharacter;
    comment.message = message;
    comment.path = Patch.COMMIT_MSG;
    return comment;
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).parent(project).create();
  }

  @SuppressWarnings("unused")
  private static class TestModule extends FactoryModule {
    @Override
    protected void configure() {
      bind(ReplicationConfig.class).to(ReplicationFileBasedConfig.class);
      bind(RevisionReader.class).in(Scopes.SINGLETON);
      bind(ApplyObject.class);
    }
  }

  private RevisionData[] toArray(Optional<RevisionData> optional) {
    ImmutableList.Builder<RevisionData> listBuilder = ImmutableList.builder();
    optional.ifPresent(listBuilder::add);
    return listBuilder.build().toArray(new RevisionData[1]);
  }
}
