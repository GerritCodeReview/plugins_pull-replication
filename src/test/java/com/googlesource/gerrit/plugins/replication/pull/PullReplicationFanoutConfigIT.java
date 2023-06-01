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
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.AutoReloadConfigDecorator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule")
public class PullReplicationFanoutConfigIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int TEST_REPLICATION_DELAY = 60;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final String TEST_REPLICATION_REMOTE = "remote1";

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  private Path gitPath;
  private FileBasedConfig config;
  private FileBasedConfig remoteConfig;
  private FileBasedConfig secureConfig;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    remoteConfig =
        new FileBasedConfig(
            sitePaths
                .etc_dir
                .resolve("replication/" + TEST_REPLICATION_REMOTE + ".config")
                .toFile(),
            FS.DETECTED);

    setReplicationSource(
        TEST_REPLICATION_REMOTE); // Simulates a full replication.config initialization

    setRemoteConfig(TEST_REPLICATION_SUFFIX, ALL_PROJECTS);

    secureConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("secure.config").toFile(), FS.DETECTED);
    setReplicationCredentials(TEST_REPLICATION_REMOTE, admin.username(), admin.httpPassword());
    secureConfig.save();

    super.setUpTestPlugin();
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReplicateNewChangeRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    String sourceRef = pushResult.getPatchSet().refName();

    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
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
  public void shouldReplicateNewChangeRefAfterConfigReloaded() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    // Trigger configuration autoreload
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    SourcesCollection sources = getInstance(SourcesCollection.class);
    remoteConfig.setInt("remote", null, "timeout", 1000);
    remoteConfig.save();
    autoReloadConfigDecorator.reload();
    waitUntil(() -> !sources.getAll().isEmpty() && sources.getAll().get(0).getTimeout() == 1000);

    Result pushResult = createChange();
    RevCommit sourceCommit = pushResult.getCommit();
    final String sourceRef = pushResult.getPatchSet().refName();
    ReplicationQueue pullReplicationQueue = getInstance(ReplicationQueue.class);
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
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
  public void shouldAutoReloadConfiguration() throws Exception {
    SourcesCollection sources = getInstance(SourcesCollection.class);
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    assertThat(sources.getAll().get(0).getTimeout()).isEqualTo(600);
    remoteConfig.setInt("remote", null, "timeout", 1000);
    remoteConfig.save();
    autoReloadConfigDecorator.reload();
    waitUntil(() -> !sources.getAll().isEmpty() && sources.getAll().get(0).getTimeout() == 1000);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldAutoReloadConfigurationWhenRemoteConfigAdded() throws Exception {
    FileBasedConfig newRemoteConfig =
        new FileBasedConfig(
            sitePaths.etc_dir.resolve("replication/new-remote-config.config").toFile(),
            FS.DETECTED);
    try {
      SourcesCollection sources = getInstance(SourcesCollection.class);
      AutoReloadConfigDecorator autoReloadConfigDecorator =
          getInstance(AutoReloadConfigDecorator.class);
      assertThat(sources.getAll().size()).isEqualTo(1);

      setRemoteConfig(newRemoteConfig, TEST_REPLICATION_SUFFIX, ALL_PROJECTS);
      autoReloadConfigDecorator.reload();
      waitUntil(() -> sources.getAll().size() == 2);

    } finally {
      // clean up
      Files.delete(newRemoteConfig.getFile().toPath());
    }
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldAutoReloadConfigurationWhenRemoteConfigDeleted() throws Exception {
    SourcesCollection sources = getInstance(SourcesCollection.class);
    AutoReloadConfigDecorator autoReloadConfigDecorator =
        getInstance(AutoReloadConfigDecorator.class);
    assertThat(sources.getAll().size()).isEqualTo(1);

    FileBasedConfig newRemoteConfig =
        new FileBasedConfig(
            sitePaths.etc_dir.resolve("replication/new-remote-config.config").toFile(),
            FS.DETECTED);
    setRemoteConfig(newRemoteConfig, TEST_REPLICATION_SUFFIX, ALL_PROJECTS);
    autoReloadConfigDecorator.reload();
    waitUntil(() -> sources.getAll().size() == 2);

    Files.delete(newRemoteConfig.getFile().toPath());

    autoReloadConfigDecorator.reload();

    waitUntil(() -> sources.getAll().size() == 1);
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

  private void setReplicationSource(String remoteName) throws IOException {
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  private void setRemoteConfig(String replicaSuffix, Optional<String> project) throws IOException {
    setRemoteConfig(remoteConfig, replicaSuffix, project);
  }

  private void setRemoteConfig(
      FileBasedConfig remoteConfig, String replicaSuffix, Optional<String> project)
      throws IOException {
    setRemoteConfig(remoteConfig, Arrays.asList(replicaSuffix), project);
  }

  private void setRemoteConfig(
      FileBasedConfig remoteConfig, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {
    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    remoteConfig.setStringList("remote", null, "url", replicaUrls);
    remoteConfig.setString("remote", null, "apiUrl", adminRestSession.url());
    remoteConfig.setString("remote", null, "fetch", "+refs/*:refs/*");
    remoteConfig.setInt("remote", null, "timeout", 600);
    remoteConfig.setInt("remote", null, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> remoteConfig.setString("remote", null, "projects", prj));
    remoteConfig.save();
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
}
