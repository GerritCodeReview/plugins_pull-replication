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

package com.googlesource.gerrit.plugins.replication.pull;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Truth8;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Change.Id;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.notedb.Sequences;
import com.google.inject.Scopes;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Before;
import org.junit.Test;

@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.RevisionReaderIT$TestModule")
public class RevisionReaderIT extends LightweightPluginDaemonTest {
  RevisionReader objectUnderTest;

  ReplicationFileBasedConfig replicationConfig;

  @Before
  public void setup() {
    objectUnderTest = plugin.getSysInjector().getInstance(RevisionReader.class);
    replicationConfig = plugin.getSysInjector().getInstance(ReplicationFileBasedConfig.class);
  }

  @Test
  public void shouldReadRefMetaObject() throws Exception {
    Result pushResult = createChange();
    String refName = RefNames.changeMetaRef(pushResult.getChange().getId());

    Optional<RevisionData> revisionDataOption =
        refObjectId(refName).flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, 0));

    assertThat(revisionDataOption.isPresent()).isTrue();
    RevisionData revisionData = revisionDataOption.get();
    assertThat(revisionData).isNotNull();
    assertThat(revisionData.getCommitObject()).isNotNull();
    assertThat(revisionData.getCommitObject().getType()).isEqualTo(Constants.OBJ_COMMIT);
    assertThat(revisionData.getCommitObject().getContent()).isNotEmpty();

    assertThat(revisionData.getTreeObject()).isNotNull();
    assertThat(revisionData.getTreeObject().getType()).isEqualTo(Constants.OBJ_TREE);
    assertThat(revisionData.getTreeObject().getContent()).isEmpty();

    assertThat(revisionData.getBlobs()).isEmpty();
  }

  @Test
  public void shouldReadForcePushCommitWithoutParent() throws Exception {
    String refName = "refs/heads/master";
    Project.NameKey emptyProjectName =
        createProjectOverAPI("emptyProject", project, false, /* submitType= */ null);
    TestRepository<InMemoryRepository> emptyRepo = cloneProject(emptyProjectName);
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            emptyRepo,
            "subject1",
            ImmutableMap.of("a/fileA.txt", "content 1", "b/fileB.txt", "content 2"));
    push.setForce(true);
    Result pushResult = push.to(refName);
    pushResult.assertOkStatus();

    try (Repository repo = repoManager.openRepository(emptyProjectName)) {
      Optional<RevisionData> revisionDataOption =
          Optional.ofNullable(repo.exactRef(refName))
              .map(Ref::getObjectId)
              .flatMap(
                  objId -> readRevisionFromObjectUnderTest(emptyProjectName, refName, objId, 0));

      assertThat(revisionDataOption.isPresent()).isTrue();
      RevisionData revisionData = revisionDataOption.get();
      assertThat(revisionData.getCommitObject()).isNotNull();
      assertThat(revisionData.getCommitObject().getType()).isEqualTo(Constants.OBJ_COMMIT);
      assertThat(revisionData.getCommitObject().getContent()).isNotEmpty();

      assertThat(revisionData.getTreeObject()).isNotNull();
      assertThat(revisionData.getTreeObject().getType()).isEqualTo(Constants.OBJ_TREE);
      assertThat(revisionData.getTreeObject().getContent()).isNotEmpty();

      assertThat(revisionData.getBlobs()).hasSize(4);

      assertThat(
              revisionData.getBlobs().stream()
                  .filter(b -> b.getType() == Constants.OBJ_TREE)
                  .collect(Collectors.toList()))
          .hasSize(2);
      assertThat(
              revisionData.getBlobs().stream()
                  .filter(b -> b.getType() == Constants.OBJ_BLOB)
                  .collect(Collectors.toList()))
          .hasSize(2);
    }
  }

  @Test
  public void shouldReadRefMetaObjectWithMaxNumberOfParents() throws Exception {
    int numberOfParents = 3;
    setReplicationConfig(numberOfParents);
    Result pushResult = createChange();
    Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(pushResult.getChange().getId());

    addMultipleComments(numberOfParents, changeId);

    Optional<RevisionData> revisionDataOption =
        refObjectId(refName)
            .flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, numberOfParents));

    assertThat(revisionDataOption.isPresent()).isTrue();
    List<ObjectId> parentObjectIds = revisionDataOption.get().getParentObjetIds();
    assertThat(parentObjectIds).hasSize(numberOfParents);
  }

  @Test
  public void shouldReadRefMetaObjectLimitedToMaxNumberOfParents() throws Exception {
    int numberOfParents = 3;
    setReplicationConfig(numberOfParents);
    Result pushResult = createChange();
    Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(pushResult.getChange().getId());

    addMultipleComments(numberOfParents + 1, changeId);

    Optional<RevisionData> revisionDataOption =
        refObjectId(refName)
            .flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, numberOfParents));

    assertThat(revisionDataOption.isPresent()).isTrue();
    List<ObjectId> parentObjectIds = revisionDataOption.get().getParentObjetIds();
    assertThat(parentObjectIds).hasSize(numberOfParents);
  }

  private void addMultipleComments(int numberOfParents, Id changeId) throws RestApiException {
    for (int i = 0; i < numberOfParents; i++) {
      addComment(changeId);
    }
  }

  private void setReplicationConfig(int numberOfParents) throws IOException {
    FileBasedConfig config = (FileBasedConfig) replicationConfig.getConfig();
    config.setInt(
        "replication", null, RevisionReader.CONFIG_MAX_API_HISTORY_DEPTH, numberOfParents);
    config.save();
  }

  private void addComment(Id changeId) throws RestApiException {
    gApi.changes().id(changeId.get()).current().review(new ReviewInput().message("foo"));
  }

  private Optional<RevisionData> readRevisionFromObjectUnderTest(
      String refName, ObjectId objId, int maxParentsDepth) {
    return readRevisionFromObjectUnderTest(project, refName, objId, maxParentsDepth);
  }

  private Optional<RevisionData> readRevisionFromObjectUnderTest(
      Project.NameKey project, String refName, ObjectId objId, int maxParentsDepth) {
    try {
      return objectUnderTest.read(project, objId, refName, maxParentsDepth);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  protected Optional<ObjectId> refObjectId(String refName) throws IOException {
    try (Repository repo = repoManager.openRepository(project)) {
      return Optional.ofNullable(repo.exactRef(refName)).map(Ref::getObjectId);
    }
  }

  @Test
  public void shouldReadRefMetaObjectWithComments() throws Exception {
    Result pushResult = createChange();
    Change.Id changeId = pushResult.getChange().getId();
    String refName = RefNames.changeMetaRef(changeId);

    CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");

    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    Optional<RevisionData> revisionDataOption =
        refObjectId(refName).flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, 0));

    assertThat(revisionDataOption.isPresent()).isTrue();
    RevisionData revisionData = revisionDataOption.get();
    assertThat(revisionData).isNotNull();
    assertThat(revisionData.getCommitObject()).isNotNull();
    assertThat(revisionData.getCommitObject().getType()).isEqualTo(Constants.OBJ_COMMIT);
    assertThat(revisionData.getCommitObject().getContent()).isNotEmpty();

    assertThat(revisionData.getTreeObject()).isNotNull();
    assertThat(revisionData.getTreeObject().getType()).isEqualTo(Constants.OBJ_TREE);
    assertThat(revisionData.getTreeObject().getContent()).isNotEmpty();

    assertThat(revisionData.getBlobs()).hasSize(1);
    RevisionObjectData blobObject = revisionData.getBlobs().get(0);
    assertThat(blobObject.getType()).isEqualTo(Constants.OBJ_BLOB);
    assertThat(blobObject.getContent()).isNotEmpty();
  }

  @Test
  public void shouldNotReadRefsSequences() throws Exception {
    createChange().assertOkStatus();
    String refName = RefNames.REFS_SEQUENCES + Sequences.NAME_CHANGES;
    Optional<RevisionData> revisionDataOption =
        refObjectId(refName).flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, 0));

    Truth8.assertThat(revisionDataOption).isEmpty();
  }

  @Test
  public void shouldFilterOutGitSubmoduleCommitsWhenReadingTheBlobs() throws Exception {
    String submodulePath = "submodule_path";
    final ObjectId GitSubmoduleCommit =
        ObjectId.fromString("93e2901bc0b4719ef6081ee6353b49c9cdd97614");

    PushOneCommit push =
        pushFactory
            .create(admin.newIdent(), testRepo)
            .addGitSubmodule(submodulePath, GitSubmoduleCommit);
    PushOneCommit.Result pushResult = push.to("refs/for/master");
    pushResult.assertOkStatus();
    Change.Id changeId = pushResult.getChange().getId();
    String refName = RefNames.patchSetRef(pushResult.getPatchSetId());

    CommentInput comment = createCommentInput(1, 0, 1, 1, "Test comment");

    ReviewInput reviewInput = new ReviewInput();
    reviewInput.comments = ImmutableMap.of(Patch.COMMIT_MSG, ImmutableList.of(comment));
    gApi.changes().id(changeId.get()).current().review(reviewInput);

    Optional<RevisionData> revisionDataOption =
        refObjectId(refName).flatMap(objId -> readRevisionFromObjectUnderTest(refName, objId, 0));

    assertThat(revisionDataOption.isPresent()).isTrue();
    RevisionData revisionData = revisionDataOption.get();

    assertThat(revisionData.getBlobs()).hasSize(1);
    RevisionObjectData blobObject = revisionData.getBlobs().get(0);
    assertThat(blobObject.getType()).isEqualTo(Constants.OBJ_BLOB);
    assertThat(blobObject.getSha1()).isNotEqualTo(GitSubmoduleCommit.getName());
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

  @SuppressWarnings("unused")
  private static class TestModule extends FactoryModule {
    @Override
    protected void configure() {
      bind(ReplicationConfig.class).to(ReplicationFileBasedConfig.class);
      bind(RevisionReader.class).in(Scopes.SINGLETON);
      bind(ApplyObject.class);
    }
  }
}
