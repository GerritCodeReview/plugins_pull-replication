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
import static com.google.gerrit.acceptance.GitUtil.deleteRef;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.BatchFetchClient;
import com.googlesource.gerrit.plugins.replication.pull.fetch.CGitFetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.Fetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.util.List;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.CGitFetchIT$TestModule")
public class CGitFetchIT extends FetchITBase {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final String TEST_TASK_ID = "taskid";

  @Inject private ProjectOperations projectOperations;

  @Before
  public void allowRefDeletion() {
    projectOperations
        .allProjectsForUpdate()
        .add(allow(Permission.DELETE).ref("refs/*").group(adminGroupUuid()))
        .update();
  }

  @Test
  public void shouldFetchRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      RevCommit sourceCommit = pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef)));

      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test(expected = TransportException.class)
  public void shouldThrowExceptionWhenRefDoesNotExists() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String nonExistingRef = "refs/changes/02/20000/1:refs/changes/02/20000/1";
    try (Repository repo = repoManager.openRepository(project)) {

      createChange();

      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(nonExistingRef)));
    }
  }

  @Test(expected = TransportException.class)
  public void shouldThrowExceptionWhenSourceDoesNotExists() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      String sourceRef = pushResult.getPatchSet().refName();

      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish("/not_existing_path/"), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef)));
    }
  }

  @Test
  public void shouldFetchMultipleRefs() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResultOne = createChange();
      String sourceRefOne = pushResultOne.getPatchSet().refName();
      Result pushResultTwo = createChange();
      String sourceRefTwo = pushResultTwo.getPatchSet().refName();

      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(
          Lists.newArrayList(
              new RefSpec(sourceRefOne + ":" + sourceRefOne),
              new RefSpec(sourceRefTwo + ":" + sourceRefTwo)));

      waitUntil(
          () ->
              checkedGetRef(repo, sourceRefOne) != null
                  && checkedGetRef(repo, sourceRefTwo) != null);

      Ref targetBranchRef = getRef(repo, sourceRefOne);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(pushResultOne.getCommit().getId());

      targetBranchRef = getRef(repo, sourceRefTwo);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(pushResultTwo.getCommit().getId());
    }
  }

  @Test
  public void shouldFetchMultipleRefsInMultipleBatches() throws Exception {
    Config cf = new Config();
    cf.setInt("remote", "test_config", "timeout", 0);
    cf.setInt("replication", null, "refsBatchSize", 2);
    URIish uri = new URIish(testRepoPath.toString());
    List<RefUpdateState> fetchResultList =
        Lists.newArrayList(new RefUpdateState("test_config", RefUpdate.Result.NEW));
    RemoteConfig remoteConfig = new RemoteConfig(cf, "test_config");
    SourceConfiguration sourceConfig = new SourceConfiguration(remoteConfig, cf);

    Repository repo = mock(Repository.class);
    FetchFactory fetchFactory = mock(FetchFactory.class);
    Fetch fetchClient = mock(Fetch.class);
    when(fetchFactory.createPlainImpl(TEST_TASK_ID, uri, repo)).thenReturn(fetchClient);
    when(fetchClient.fetch(any())).thenReturn(fetchResultList);

    Fetch objectUnderTest =
        new BatchFetchClient(
            sourceConfig, fetchFactory, TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

    objectUnderTest.fetch(
        Lists.newArrayList(
            new RefSpec("refs/changes/01/1/1:refs/changes/01/1/1"),
            new RefSpec("refs/changes/02/2/1:refs/changes/02/2/1"),
            new RefSpec("refs/changes/03/3/1:refs/changes/03/3/1")));
    verify(fetchClient, times(2)).fetch(any());
  }

  @Test
  public void shouldFetchNewBranch() throws Exception {
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

    try (Repository repo = repoManager.openRepository(project)) {
      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(newBranch + ":" + newBranch)));

      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
  }

  @Test(expected = TransportException.class)
  public void shouldThrowExceptionWhenBranchDoesNotExists() throws Exception {
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);

    try (Repository repo = repoManager.openRepository(project)) {
      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(
          Lists.newArrayList(new RefSpec("non_existing_branch" + ":" + "non_existing_branch")));
    }
  }

  @Test
  public void shouldNotPruneRefsWhenMirrorIsUnset() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String BRANCH_REF = Constants.R_HEADS + "anyBranch";
    String TAG_REF = Constants.R_TAGS + "anyTag";

    PushOneCommit.Result branchPush = pushFactory.create(user.newIdent(), testRepo).to(BRANCH_REF);
    branchPush.assertOkStatus();

    PushResult tagPush = pushHead(testRepo, TAG_REF, false, false);
    assertOkStatus(tagPush, TAG_REF);

    try (Repository localRepo = repoManager.openRepository(project)) {
      fetchAllRefs(TEST_TASK_ID, testRepoPath, localRepo);
      waitUntil(
          () ->
              checkedGetRef(localRepo, BRANCH_REF) != null
                  && checkedGetRef(localRepo, TAG_REF) != null);
      assertThat(getRef(localRepo, BRANCH_REF)).isNotNull();
      assertThat(getRef(localRepo, TAG_REF)).isNotNull();

      PushResult deleteBranchResult = deleteRef(testRepo, BRANCH_REF);
      assertOkStatus(deleteBranchResult, BRANCH_REF);

      PushResult deleteTagResult = deleteRef(testRepo, TAG_REF);
      assertOkStatus(deleteTagResult, TAG_REF);

      fetchAllRefs(TEST_TASK_ID, testRepoPath, localRepo);
      waitUntil(
          () ->
              checkedGetRef(localRepo, BRANCH_REF) != null
                  && checkedGetRef(localRepo, TAG_REF) != null);
      assertThat(getRef(localRepo, BRANCH_REF)).isNotNull();
      assertThat(getRef(localRepo, TAG_REF)).isNotNull();
    }
  }

  @SuppressWarnings("unused")
  private static class TestModule extends FetchModule<CGitFetch> {
    @Override
    Class<CGitFetch> clientClass() {
      return CGitFetch.class;
    }

    @Override
    Config cf() {
      Config cf = new Config();
      cf.setInt("remote", "test_config", "timeout", 0);
      return cf;
    }
  }
}
