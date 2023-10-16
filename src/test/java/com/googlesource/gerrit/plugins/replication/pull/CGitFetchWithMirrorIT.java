// Copyright (C) 2023 The Android Open Source Project
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

import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.CGitFetch;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Before;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.CGitFetchWithMirrorIT$TestModule")
public class CGitFetchWithMirrorIT extends FetchITBase {
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
  public void shouldPruneRefsWhenMirrorIsTrue() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String BRANCH_REF = Constants.R_HEADS + "anyBranch";
    String TAG_REF = Constants.R_TAGS + "anyTag";

    Result branchPush = pushFactory.create(user.newIdent(), testRepo).to(BRANCH_REF);
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
              checkedGetRef(localRepo, BRANCH_REF) == null
                  && checkedGetRef(localRepo, TAG_REF) == null);
      assertThat(getRef(localRepo, BRANCH_REF)).isNull();
      assertThat(getRef(localRepo, TAG_REF)).isNull();
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
      cf.setBoolean("remote", "test_config", "mirror", true);
      return cf;
    }
  }
}
