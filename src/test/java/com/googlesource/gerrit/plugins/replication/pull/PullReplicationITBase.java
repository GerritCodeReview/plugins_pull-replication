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
import static com.google.gerrit.acceptance.GitUtil.fetch;
import static com.google.gerrit.acceptance.GitUtil.pushOne;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.events.ProjectEvent;
import com.googlesource.gerrit.plugins.replication.AutoReloadConfigDecorator;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.URIish;
import org.junit.Ignore;
import org.junit.Test;

abstract class PullReplicationITBase extends PullReplicationSetupBase {

  @Override
  protected void setReplicationSource(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {
    List<String> fetchUrls =
        buildReplicaURLs(replicaSuffixes, s -> gitPath.resolve("${name}" + s + ".git").toString());
    config.setStringList("remote", remoteName, "url", fetchUrls);
    config.setString("remote", remoteName, "apiUrl", adminRestSession.url());
    config.setString("remote", remoteName, "fetch", "+refs/*:refs/*");
    config.setInt("remote", remoteName, "timeout", 600);
    config.setBoolean("remote", remoteName, "mirror", true);
    config.setInt("remote", remoteName, "replicationDelay", replicationDelaySec());
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setBoolean("gerrit", null, "autoReload", true);
    config.setStringList("replication", null, "syncRefs", syncRefs());
    config.save();
  }

  protected List<String> syncRefs() {
    return List.of();
  }

  @Override
  public void setUpTestPlugin() throws Exception {
    setUpTestPlugin(false);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateNewChangeRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateNewBranch() throws Exception {
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateBranchDeleted() throws Exception {
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(project.get()).branch(newBranch).create(input);
    String branchRevision = gApi.projects().name(project.get()).branch(newBranch).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            newBranch,
            branchRevision,
            ObjectId.zeroId().getName(),
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) == null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNull();
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateBranchUpdatedAndThenDeleted() throws Exception {
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String master = "refs/heads/master";
    String initial = gApi.projects().name(testProjectName).branch(master).get().revision;
    testRepo = cloneProject(NameKey.parse(testProjectName));
    PushOneCommit push =
        pushFactory.create(admin.newIdent(), testRepo, "change1", "a.txt", "content");
    PushOneCommit.Result result = push.to("refs/heads/master");
    result.assertOkStatus();

    String updated = gApi.projects().name(testProjectName).branch(master).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(project, master, initial, updated, TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    gApi.projects().name(testProjectName).branch(master).delete();
    ProjectEvent deleteEvent =
        generateUpdateEvent(
            project, master, updated, ObjectId.zeroId().getName(), TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(deleteEvent);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, master) == null);
    }
  }

  @Test
  @UseLocalDisk
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateForceUpdatedBranch() throws Exception {
    boolean forcedPush = true;
    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    NameKey testProjectNameKey = createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);

    projectOperations
        .project(testProjectNameKey)
        .forUpdate()
        .add(allow(Permission.PUSH).ref(newBranch).group(REGISTERED_USERS).force(true))
        .update();

    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }

    TestRepository<InMemoryRepository> testProject = cloneProject(testProjectNameKey);
    fetch(testProject, RefNames.REFS_HEADS + "*:" + RefNames.REFS_HEADS + "*");
    RevCommit amendedCommit = testProject.amendRef(newBranch).message("Amended commit").create();
    PushResult pushResult =
        pushOne(testProject, newBranch, newBranch, false, forcedPush, Collections.emptyList());
    Collection<RemoteRefUpdate> pushedRefs = pushResult.getRemoteUpdates();
    assertThat(pushedRefs).hasSize(1);
    assertThat(pushedRefs.iterator().next().getStatus()).isEqualTo(Status.OK);

    ProjectEvent forcedPushEvent =
        generateUpdateEvent(
            project,
            newBranch,
            branchRevision,
            amendedCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(forcedPushEvent);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(
          () ->
              checkedGetRef(repo, newBranch) != null
                  && checkedGetRef(repo, newBranch)
                      .getObjectId()
                      .getName()
                      .equals(amendedCommit.getId().getName()));
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateNewChangeRefCGitClient() throws Exception {
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);

    config.setBoolean("replication", null, "useCGitClient", true);
    config.save();

    autoReloadConfigDecorator.reload();

    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateNewBranchCGitClient() throws Exception {
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);

    config.setBoolean("replication", null, "useCGitClient", true);
    config.save();

    autoReloadConfigDecorator.reload();

    String testProjectName = project + TEST_REPLICATION_SUFFIX;
    createTestProject(testProjectName);

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldCreateNewProject() throws Exception {
    NameKey projectToCreate = Project.nameKey(project.get() + "_created");

    setReplicationSource(TEST_REPLICATION_REMOTE, "", Optional.of(projectToCreate.get()));
    config.save();
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    autoReloadConfigDecorator.reload();
    Source source =
        getInstance(SourcesCollection.class).getByRemoteName(TEST_REPLICATION_REMOTE).get();

    FetchApiClient client = getInstance(FetchApiClient.Factory.class).create(source);
    client.initProject(
        projectToCreate,
        new URIish(source.getApis().get(0)),
        System.currentTimeMillis(),
        Collections.emptyList());

    waitUntil(() -> repoManager.list().contains(projectToCreate));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateProjectDeletion() throws Exception {
    String projectToDelete = project.get();
    setReplicationSource(TEST_REPLICATION_REMOTE, "", Optional.of(projectToDelete));
    config.save();
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    autoReloadConfigDecorator.reload();

    ProjectDeletedListener.Event event =
        new ProjectDeletedListener.Event() {
          @Override
          public String getProjectName() {
            return projectToDelete;
          }

          @Override
          public NotifyHandling getNotify() {
            return NotifyHandling.NONE;
          }
        };
    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(event);
    }

    waitUntil(() -> !repoManager.list().contains(project));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateHeadUpdate() throws Exception {
    String testProjectName = project.get();
    setReplicationSource(TEST_REPLICATION_REMOTE, "", Optional.of(testProjectName));
    config.save();
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    autoReloadConfigDecorator.reload();

    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);

    HeadUpdatedListener.Event event = new FakeHeadUpdateEvent(master, newBranch, testProjectName);
    pullReplicationQueue.onHeadUpdated(event);

    waitUntil(
        () -> {
          try {
            return gApi.projects().name(testProjectName).head().equals(newBranch);
          } catch (RestApiException e) {
            return false;
          }
        });
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReplicateNewChangeRefToReplica() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    ProjectEvent event =
        generateUpdateEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    pullReplicationQueue.onEvent(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }
}
