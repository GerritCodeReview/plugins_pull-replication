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

import static java.util.stream.Collectors.toList;

import com.google.common.base.Suppliers;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.BatchRefUpdateEvent;
import com.google.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

public abstract class PullReplicationSetupBase extends LightweightPluginDaemonTest {

  protected static final Optional<String> ALL_PROJECTS = Optional.empty();
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected static final int TEST_REPLICATION_DELAY = 1;
  protected static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2000);
  protected static final String TEST_REPLICATION_SUFFIX = "suffix1";
  protected static final String TEST_REPLICATION_REMOTE = "remote1";
  @Inject protected SitePaths sitePaths;
  @Inject protected ProjectOperations projectOperations;
  @Inject protected DynamicSet<ProjectDeletedListener> deletedListeners;
  protected Path gitPath;
  protected FileBasedConfig config;
  protected FileBasedConfig secureConfig;

  protected void setUpTestPlugin(boolean loadExisting) throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    File configFile = sitePaths.etc_dir.resolve("replication.config").toFile();
    config = new FileBasedConfig(configFile, FS.DETECTED);
    if (loadExisting && configFile.exists()) {
      config.load();
    }
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

  protected Ref getRef(Repository repo, String branchName) throws IOException {
    return repo.getRefDatabase().exactRef(branchName);
  }

  protected Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  protected void setReplicationSource(
      String remoteName, String replicaSuffix, Optional<String> project)
      throws IOException, ConfigInvalidException {
    setReplicationSource(remoteName, Arrays.asList(replicaSuffix), project);
  }

  protected abstract void setReplicationSource(
      String remoteName, List<String> replicaSuffixes, Optional<String> project) throws IOException;

  protected void setReplicationCredentials(String remoteName, String username, String password)
      throws IOException {
    secureConfig.setString("remote", remoteName, "username", username);
    secureConfig.setString("remote", remoteName, "password", password);
    secureConfig.save();
  }

  protected void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  protected <T> T getInstance(Class<T> classObj) {
    return plugin.getSysInjector().getInstance(classObj);
  }

  protected NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  protected List<String> buildReplicaURLs(
      List<String> replicaSuffixes, Function<String, String> toURL) {
    return replicaSuffixes.stream().map(suffix -> toURL.apply(suffix)).collect(toList());
  }

  protected BatchRefUpdateEvent generateBatchRefUpdateEvent(
      Project.NameKey project,
      String ref,
      String oldObjectId,
      String newObjectId,
      String instanceId) {
    RefUpdateAttribute upd = new RefUpdateAttribute();
    upd.newRev = newObjectId;
    upd.oldRev = oldObjectId;
    upd.project = project.get();
    upd.refName = ref;
    BatchRefUpdateEvent event =
        new BatchRefUpdateEvent(
            project,
            Suppliers.ofInstance(List.of(upd)),
            Suppliers.ofInstance(new AccountAttribute(admin.id().get())));
    event.instanceId = instanceId;
    return event;
  }
}
