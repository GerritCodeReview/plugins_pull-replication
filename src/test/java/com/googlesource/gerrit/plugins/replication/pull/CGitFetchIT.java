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
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.fetch.BatchFetchClient;
import com.googlesource.gerrit.plugins.replication.pull.fetch.CGitFetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.Fetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchClientImplementation;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.apache.http.client.utils.URIBuilder;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.CGitFetchIT$TestModule")
public class CGitFetchIT extends LightweightPluginDaemonTest {
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int TEST_REPLICATION_DELAY = 60;
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(TEST_REPLICATION_DELAY * 2);
  private static CredentialsProvider userNameCredentialsProvider;

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  private FetchFactory fetchFactory;
  private Path gitPath;
  private Path testRepoPath;

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");
    testRepoPath = gitPath.resolve(project + TEST_REPLICATION_SUFFIX + ".git");
    userNameCredentialsProvider =
        new UsernamePasswordCredentialsProvider(admin.username(), admin.httpPassword());

    super.setUpTestPlugin();
    fetchFactory = plugin.getSysInjector().getInstance(FetchFactory.class);
  }

  @Test
  public void shouldFetchRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      RevCommit sourceCommit = pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      Fetch objectUnderTest = fetchFactory.create(new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef)));

      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldFetchRefWithAuthentication() throws Exception {
    Project.NameKey testRepoName = createTestProject(project + TEST_REPLICATION_SUFFIX);
    testRepo = cloneProject(testRepoName);

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      RevCommit sourceCommit = pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      String uri = removeCredentials(admin.getHttpUrl(server)) + "/a/" + testRepoName.get();
      Fetch objectUnderTest = fetchFactory.create(new URIish(uri), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef)));

      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }
  }

  @Test
  public void shouldThrowExceptionWhenUnknownUser() throws Exception {
    Project.NameKey testRepoName = createTestProject(project + TEST_REPLICATION_SUFFIX);
    testRepo = cloneProject(testRepoName);

    userNameCredentialsProvider =
        new UsernamePasswordCredentialsProvider("unknown_user", admin.httpPassword());

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      String uri = removeCredentials(admin.getHttpUrl(server)) + "/a/" + testRepoName.get();
      Fetch objectUnderTest = fetchFactory.create(new URIish(uri), repo);

      TransportException e =
          assertThrows(
              TransportException.class,
              () ->
                  objectUnderTest.fetch(
                      Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef))));
      assertThat(e).hasMessageThat().contains("Unauthorized");
    }
  }

  @Test
  public void shouldThrowExceptionWhenIncorrectPassword() throws Exception {
    Project.NameKey testRepoName = createTestProject(project + TEST_REPLICATION_SUFFIX);
    testRepo = cloneProject(testRepoName);

    userNameCredentialsProvider =
        new UsernamePasswordCredentialsProvider(admin.username(), "incorrect_password");

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      String uri = removeCredentials(admin.getHttpUrl(server)) + "/a/" + testRepoName.get();
      Fetch objectUnderTest = fetchFactory.create(new URIish(uri), repo);

      TransportException e =
          assertThrows(
              TransportException.class,
              () ->
                  objectUnderTest.fetch(
                      Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef))));
      assertThat(e).hasMessageThat().contains("Unauthorized");
    }
  }

  @Test
  public void shouldThrowExceptionWhenUserIsMissing() throws Exception {
    Project.NameKey testRepoName = createTestProject(project + TEST_REPLICATION_SUFFIX);
    testRepo = cloneProject(testRepoName);

    String emptyUsername = "";

    userNameCredentialsProvider =
        new UsernamePasswordCredentialsProvider(emptyUsername, admin.httpPassword());

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      String uri = removeCredentials(admin.getHttpUrl(server)) + "/a/" + testRepoName.get();
      Fetch objectUnderTest = fetchFactory.create(new URIish(uri), repo);

      TransportException e =
          assertThrows(
              TransportException.class,
              () ->
                  objectUnderTest.fetch(
                      Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef))));
      assertThat(e).hasMessageThat().contains("could not read Username");
    }
  }

  @Test
  public void shouldThrowExceptionWhenPasswordIsMissing() throws Exception {
    Project.NameKey testRepoName = createTestProject(project + TEST_REPLICATION_SUFFIX);
    testRepo = cloneProject(testRepoName);

    String emptyPassword = "";

    userNameCredentialsProvider =
        new UsernamePasswordCredentialsProvider(admin.username(), emptyPassword);

    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      pushResult.getCommit();
      String sourceRef = pushResult.getPatchSet().refName();

      String uri = removeCredentials(admin.getHttpUrl(server)) + "/a/" + testRepoName.get();
      Fetch objectUnderTest = fetchFactory.create(new URIish(uri), repo);

      TransportException e =
          assertThrows(
              TransportException.class,
              () ->
                  objectUnderTest.fetch(
                      Lists.newArrayList(new RefSpec(sourceRef + ":" + sourceRef))));
      assertThat(e).hasMessageThat().contains("could not read Username");
    }
  }

  @Test(expected = TransportException.class)
  public void shouldThrowExecptionWhenRefDoesNotExists() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    String nonExistingRef = "refs/changes/02/20000/1:refs/changes/02/20000/1";
    try (Repository repo = repoManager.openRepository(project)) {

      createChange();

      Fetch objectUnderTest = fetchFactory.create(new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(Lists.newArrayList(new RefSpec(nonExistingRef)));
    }
  }

  @Test(expected = TransportException.class)
  public void shouldThrowExecptionWhenSourceDoesNotExists() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    try (Repository repo = repoManager.openRepository(project)) {

      Result pushResult = createChange();
      String sourceRef = pushResult.getPatchSet().refName();

      Fetch objectUnderTest = fetchFactory.create(new URIish("/not_existing_path/"), repo);

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

      Fetch objectUnderTest = fetchFactory.create(new URIish(testRepoPath.toString()), repo);

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
    when(fetchFactory.createPlainImpl(uri, repo)).thenReturn(fetchClient);
    when(fetchClient.fetch(any())).thenReturn(fetchResultList);

    Fetch objectUnderTest =
        new BatchFetchClient(sourceConfig, fetchFactory, new URIish(testRepoPath.toString()), repo);

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
      Fetch objectUnderTest = fetchFactory.create(new URIish(testRepoPath.toString()), repo);

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
      Fetch objectUnderTest = fetchFactory.create(new URIish(testRepoPath.toString()), repo);

      objectUnderTest.fetch(
          Lists.newArrayList(new RefSpec("non_existing_branch" + ":" + "non_existing_branch")));
    }
  }

  private void waitUntil(Supplier<Boolean> waitCondition) throws InterruptedException {
    WaitUtil.waitUntil(waitCondition, TEST_TIMEOUT);
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

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).create();
  }

  private String removeCredentials(String uriStr) throws URISyntaxException {
    URIish uri = new URIish(uriStr);
    return new URIBuilder()
        .setScheme(uri.getScheme())
        .setHost(uri.getHost())
        .setPort(uri.getPort())
        .toString();
  }

  @SuppressWarnings("unused")
  private static class TestModule extends FactoryModule {
    @Override
    protected void configure() {
      Config cf = new Config();
      cf.setInt("remote", "test_config", "timeout", 0);
      try {
        RemoteConfig remoteConfig = new RemoteConfig(cf, "test_config");
        SourceConfiguration sourceConfig = new SourceConfiguration(remoteConfig, cf);
        bind(ReplicationConfig.class).to(ReplicationFileBasedConfig.class);
        bind(CredentialsFactory.class)
            .toInstance(
                new CredentialsFactory() {

                  @Override
                  public CredentialsProvider create(String remoteName) {
                    return userNameCredentialsProvider;
                  }
                });

        bind(SourceConfiguration.class).toInstance(sourceConfig);
        install(
            new FactoryModuleBuilder()
                .implement(Fetch.class, CGitFetch.class)
                .implement(Fetch.class, FetchClientImplementation.class, CGitFetch.class)
                .build(FetchFactory.class));
      } catch (URISyntaxException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
