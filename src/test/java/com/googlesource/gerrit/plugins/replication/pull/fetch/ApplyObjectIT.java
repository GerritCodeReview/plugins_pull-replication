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
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.googlesource.gerrit.plugins.replication.pull.RevisionReader;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObjectIT$TestModule")
public class ApplyObjectIT extends LightweightPluginDaemonTest {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";

  @Inject private ProjectOperations projectOperations;
  @Inject RevisionReader reader;
  @Inject ApplyObject objectUnderTest;

  @Test
  public void shouldApplyRefMetaObject() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    Result pushResult = createChange();
    String refName = RefNames.changeMetaRef(pushResult.getChange().getId());

    RevisionData revisionData = reader.read(Project.nameKey(testRepoProjectName), refName);

    RefSpec refSpec = new RefSpec(refName);
    objectUnderTest.apply(project, refSpec, revisionData);
    RevisionData newRevisionData = reader.read(project, refName);

    compareObjects(revisionData, newRevisionData);
  }

  @Test
  public void shouldApplyRefMetaObjectWithComments() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    Result pushResult = createChange();
    Change.Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(changeId);

    CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");

    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    RevisionData revisionData = reader.read(Project.nameKey(testRepoProjectName), refName);

    RefSpec refSpec = new RefSpec(refName);
    objectUnderTest.apply(project, refSpec, revisionData);

    RevisionData newRevisionData = reader.read(project, refName);
    compareObjects(revisionData, newRevisionData);
  }

  @Test
  public void shouldApplyRefMetaObjectWithMultipleComments() throws Exception {
    String testRepoProjectName = project + TEST_REPLICATION_SUFFIX;
    testRepo = cloneProject(createTestProject(testRepoProjectName));

    Result pushResult = createChange();
    Change.Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(changeId);

    CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");

    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    comment = createCommentInput(1, 2, 1, 3, "Second test comment");

    reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    RevisionData revisionData = reader.read(Project.nameKey(testRepoProjectName), refName);

    RefSpec refSpec = new RefSpec(refName);
    objectUnderTest.apply(project, refSpec, revisionData);

    RevisionData newRevisionData = reader.read(project, refName);
    compareObjects(revisionData, newRevisionData);
  }

  private void compareObjects(RevisionData expected, RevisionData actual) {
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
    return projectOperations.newProject().name(name).create();
  }

  @SuppressWarnings("unused")
  private static class TestModule extends FactoryModule {
    @Override
    protected void configure() {
      bind(RevisionReader.class).in(Scopes.SINGLETON);
      bind(ApplyObject.class);
    }
  }
}
