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

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

public abstract class FetchITBase extends LightweightPluginDaemonTest {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int TEST_REPLICATION_DELAY = 60;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);
  private static final RefSpec ALL_REFS = new RefSpec("+refs/*:refs/*");

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  FetchFactory fetchFactory;
  private Path gitPath;
  Path testRepoPath;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");
    testRepoPath = gitPath.resolve(project + TEST_REPLICATION_SUFFIX + ".git");

    super.setUpTestPlugin();
    fetchFactory = plugin.getSysInjector().getInstance(FetchFactory.class);
  }

  void waitUntil(Supplier<Boolean> waitCondition) throws Exception {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
  }

  Ref getRef(Repository repo, String branchName) throws Exception {
    return repo.getRefDatabase().exactRef(branchName);
  }

  Ref checkedGetRef(Repository repo, String branchName) {
    try {
      return repo.getRefDatabase().exactRef(branchName);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("failed to get ref %s in repo %s", branchName, repo);
      return null;
    }
  }

  protected void fetchAllRefs(String taskId, Path remotePath, Repository localRepo)
      throws URISyntaxException, IOException {
    fetchFactory
        .create(taskId, new URIish(remotePath.toString()), localRepo)
        .fetch(Lists.newArrayList(ALL_REFS));
  }

  Project.NameKey createTestProject(String name) {
    return projectOperations.newProject().name(name).create();
  }
}
