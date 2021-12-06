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
import static java.util.stream.Collectors.toList;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.project.ProjectResource;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.AutoReloadConfigDecorator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule")
public class PullReplicationIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 60;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final String TEST_REPLICATION_REMOTE = "remote1";

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  @Inject private DynamicSet<ProjectDeletedListener> deletedListeners;
  private Path gitPath;
  private FileBasedConfig config;
  private FileBasedConfig secureConfig;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    setReplicationSource(
        TEST_REPLICATION_REMOTE,
        TEST_REPLICATION_SUFFIX,
        ALL_PROJECTS); // Simulates a full replication.config initialization
    config.save();

    secureConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("secure.config").toFile(), FS.DETECTED);
    setReplicationCredentials(TEST_REPLICATION_REMOTE, admin.username(), admin.httpPassword());
    secureConfig.save();

    super.setUpTestPlugin();
  }

  @Test
  public void shouldReplicateNewChangeRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    GitReferenceUpdatedListener.Event event =
        new FakeGitReferenceUpdatedEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            ReceiveCommand.Type.CREATE);
    pullReplicationQueue.onGitReferenceUpdated(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
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
    GitReferenceUpdatedListener.Event event =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            ReceiveCommand.Type.CREATE);
    pullReplicationQueue.onGitReferenceUpdated(event);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
  }

  @Test
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
    GitReferenceUpdatedListener.Event event =
        new FakeGitReferenceUpdatedEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            ReceiveCommand.Type.CREATE);
    pullReplicationQueue.onGitReferenceUpdated(event);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
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
    GitReferenceUpdatedListener.Event event =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            ReceiveCommand.Type.CREATE);
    pullReplicationQueue.onGitReferenceUpdated(event);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
  }

  @Test
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

    waitUntil(
        () -> {
          try {
            gApi.projects().name(projectToDelete).get();
            return false;
          } catch (ResourceNotFoundException e) {
            return true;
          } catch (RestApiException e) {
            return false;
          }
        });
  }

  @Test
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
    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

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

  private Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  private Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  private void setReplicationSource(
      String remoteName, String replicaSuffix, Optional<String> project)
      throws IOException, ConfigInvalidException {
    setReplicationSource(remoteName, Arrays.asList(replicaSuffix), project);
  }

  private void setReplicationSource(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException, ConfigInvalidException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setString("replication", null, "instanceLabel", remoteName);
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setString("remote", remoteName, "apiUrl", adminRestSession.url());
    config.setString("remote", remoteName, "fetch", "+refs/*:refs/*");
    config.setInt("remote", remoteName, "timeout", 600);
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  private void setReplicationCredentials(String remoteName, String username, String password)
      throws IOException {
    secureConfig.setString("remote", remoteName, "username", username);
    secureConfig.setString("remote", remoteName, "password", password);
    secureConfig.save();
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  private <T> T getInstance(Class<T> classObj) {
    return plugin.getSysInjector().getInstance(classObj);
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  @Singleton
  public static class FakeDeleteProjectPlugin implements RestModifyView<ProjectResource, Input> {
    private int deleteEndpointCalls;

    FakeDeleteProjectPlugin() {
      this.deleteEndpointCalls = 0;
    }

    @Override
    public Response<?> apply(ProjectResource resource, Input input)
        throws AuthException, BadRequestException, ResourceConflictException, Exception {
      deleteEndpointCalls += 1;
      return Response.ok();
    }

    int getDeleteEndpointCalls() {
      return deleteEndpointCalls;
    }
  }
}
