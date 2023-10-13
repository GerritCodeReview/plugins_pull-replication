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
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.gerrit.acceptance.GitUtil.deleteRef;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;

import com.google.common.collect.Lists;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.googlesource.gerrit.plugins.replication.AutoReloadSecureCredentialsFactoryDecorator;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.fetch.Fetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchClientImplementation;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import com.googlesource.gerrit.plugins.replication.pull.fetch.JGitFetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.PermanentTransportException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.JGitFetchIT$TestModule")
public class JGitFetchIT extends FetchITBase {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final String TEST_TASK_ID = "taskid";
  private static final RefSpec ALL_REFS = new RefSpec("+refs/*:refs/*");

  @Inject private ProjectOperations projectOperations;

  @Before
  public void allowRefDeletion() {
    projectOperations
        .allProjectsForUpdate()
        .add(allow(Permission.DELETE).ref("refs/*").group(adminGroupUuid()))
        .update();
  }

  @Test(expected = PermanentTransportException.class)
  public void shouldThrowPermanentTransportExceptionWhenRefDoesNotExists() throws Exception {

    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String nonExistingRef = "refs/changes/02/20000/1:refs/changes/02/20000/1";
    try (Repository repo = repoManager.openRepository(project)) {
      Fetch objectUnderTest =
          fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), repo);
      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(nonExistingRef)));
    }
  }

  @Test
  public void shouldPruneRefsWhenMirrorIsTrue() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String branchName = "anyBranch";
    String branchRef = Constants.R_HEADS + branchName;
    String tagName = "anyTag";
    String tagRef = Constants.R_TAGS + tagName;

    PushOneCommit.Result branchPush = pushFactory.create(user.newIdent(), testRepo).to(branchRef);
    branchPush.assertOkStatus();

    PushResult tagPush = pushHead(testRepo, tagRef, false, false);
    assertOkStatus(tagPush, tagRef);

    try (Repository localRepo = repoManager.openRepository(project)) {
      List<RefUpdateState> fetchCreated = fetchAllRefs(localRepo);
      assertThat(fetchCreated.toString())
          .contains(new RefUpdateState(branchRef, RefUpdate.Result.NEW).toString());
      assertThat(fetchCreated.toString())
          .contains(new RefUpdateState(tagRef, RefUpdate.Result.NEW).toString());

      assertThat(getRef(localRepo, branchRef)).isNotNull();

      PushResult deleteBranchResult = deleteRef(testRepo, branchRef);
      assertOkStatus(deleteBranchResult, branchRef);

      PushResult deleteTagResult = deleteRef(testRepo, tagRef);
      assertOkStatus(deleteTagResult, tagRef);

      List<RefUpdateState> fetchDeleted = fetchAllRefs(localRepo);
      assertThat(fetchDeleted.toString())
          .contains(new RefUpdateState(branchRef, RefUpdate.Result.FORCED).toString());
      assertThat(getRef(localRepo, branchRef)).isNull();

      assertThat(fetchDeleted.toString())
          .contains(new RefUpdateState(tagRef, RefUpdate.Result.FORCED).toString());
      assertThat(getRef(localRepo, tagRef)).isNull();
    }
  }

  private List<RefUpdateState> fetchAllRefs(Repository localRepo)
      throws URISyntaxException, IOException {
    Fetch fetch = fetchFactory.create(TEST_TASK_ID, new URIish(testRepoPath.toString()), localRepo);
    return fetch.fetch(Lists.newArrayList(ALL_REFS));
  }

  private static void assertOkStatus(PushResult result, String ref) {
    RemoteRefUpdate refUpdate = result.getRemoteUpdate(ref);
    assertThat(refUpdate).isNotNull();
    assertWithMessage(refUpdate.getMessage())
        .that(refUpdate.getStatus())
        .isEqualTo(RemoteRefUpdate.Status.OK);
  }

  @SuppressWarnings("unused")
  private static class TestModule extends FactoryModule {
    @Override
    protected void configure() {
      Config cf = new Config();
      cf.setInt("remote", "test_config", "timeout", 0);
      cf.setBoolean("remote", "test_config", "mirror", true);
      try {
        RemoteConfig remoteConfig = new RemoteConfig(cf, "test_config");
        SourceConfiguration sourceConfig = new SourceConfiguration(remoteConfig, cf);
        bind(ReplicationConfig.class).to(ReplicationFileBasedConfig.class);
        bind(CredentialsFactory.class)
            .to(AutoReloadSecureCredentialsFactoryDecorator.class)
            .in(Scopes.SINGLETON);

        bind(SourceConfiguration.class).toInstance(sourceConfig);
        install(
            new FactoryModuleBuilder()
                .implement(Fetch.class, JGitFetch.class)
                .implement(Fetch.class, FetchClientImplementation.class, JGitFetch.class)
                .build(FetchFactory.class));
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
