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
import static com.google.common.truth.Truth8.assertThat;
import static com.google.gerrit.acceptance.GitUtil.fetch;
import static com.google.gerrit.acceptance.GitUtil.pushOne;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.common.base.Strings;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.AutoReloadConfigDecorator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.util.FS;
import org.junit.Ignore;
import org.junit.Test;

/** Base class to run regular and async acceptance tests */
public abstract class PullReplicationITAbstract extends PullReplicationSetupBase {
  private BufferedEventListener eventListener;

  public static class PullReplicationTestModule extends PullReplicationModule {
    @Inject
    public PullReplicationTestModule(SitePaths site, InMemoryMetricMaker memMetric) {
      super(site, memMetric);
    }

    @Override
    protected void configure() {
      super.configure();

      DynamicSet.bind(binder(), EventListener.class)
          .to(BufferedEventListener.class)
          .asEagerSingleton();
    }
  }

  @Singleton
  public static class BufferedEventListener implements EventListener {

    private List<Event> eventsReceived;
    private String eventTypeFilter;

    @Inject
    public BufferedEventListener() {
      eventsReceived = new ArrayList<>();
    }

    @Override
    public void onEvent(Event event) {
      if (event.getType().equals(eventTypeFilter)) {
        eventsReceived.add(event);
      }
    }

    public void clearFilter(String expectedEventType) {
      eventsReceived.clear();
      eventTypeFilter = expectedEventType;
    }

    public int numEventsReceived() {
      return eventsReceived.size();
    }

    @SuppressWarnings("unchecked")
    public <T extends Event> Stream<T> eventsStream() {
      return (Stream<T>) eventsReceived.stream();
    }
  }

  @Override
  protected void setUpTestPlugin(boolean loadExisting) throws Exception {
    super.setUpTestPlugin(loadExisting);

    eventListener = plugin.getSysInjector().getInstance(BufferedEventListener.class);
  }

  protected boolean isAsyncReplication() {
    FileBasedConfig config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    try {
      config.load();
    } catch (IOException | ConfigInvalidException e) {
      throw new IllegalStateException(e);
    }
    return !Strings.isNullOrEmpty(config.getString("replication", null, "syncRefs"));
  }

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
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);

    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }

    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
  }

  private void assertTasksMetricScheduledAndCompleted(int numTasks) {
    if (isAsyncReplication()) {
      assertTasksMetric("scheduled", numTasks);
      assertTasksMetric("started", numTasks);
      assertTasksMetric("completed", numTasks);
      assertEmptyTasksMetric("failed");
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
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }

    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldFailReplicatingInexistentRepository() throws Exception {
    String newBranch = "refs/heads/mybranch";
    String branchRevision = "7bb81c29e14a4169e5ca4f43992094c209aae26c";

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationFailed(1);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNull();
    }

    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.FAILED);
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);

    TestRepository<InMemoryRepository> testProject = cloneProject(testProjectNameKey);
    fetch(testProject, RefNames.REFS_HEADS + "*:" + RefNames.REFS_HEADS + "*");
    RevCommit amendedCommit = testProject.amendRef(newBranch).message("Amended commit").create();
    PushResult pushResult =
        pushOne(testProject, newBranch, newBranch, false, forcedPush, Collections.emptyList());
    Collection<RemoteRefUpdate> pushedRefs = pushResult.getRemoteUpdates();
    assertThat(pushedRefs).hasSize(1);
    assertThat(pushedRefs.iterator().next().getStatus()).isEqualTo(Status.OK);

    FakeGitReferenceUpdatedEvent forcedPushEvent =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            branchRevision,
            amendedCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(forcedPushEvent);
    waitUntilReplicationCompleted(2);

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

    assertTasksMetricScheduledAndCompleted(2);
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }

    assertTasksMetricScheduledAndCompleted(1);
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            newBranch,
            ObjectId.zeroId().getName(),
            branchRevision,
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project);
        Repository sourceRepo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, newBranch) != null);

      Ref targetBranchRef = getRef(repo, newBranch);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId().getName()).isEqualTo(branchRevision);
    }

    assertTasksMetricScheduledAndCompleted(1);
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
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
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    for (ProjectDeletedListener l : deletedListeners) {
      l.onProjectDeleted(event);
    }
    waitUntilReplicationCompleted(1);

    waitUntil(() -> !repoManager.list().contains(project));

    assertTasksMetricScheduledAndCompleted(1);
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
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
    String branchRevision = gApi.projects().name(testProjectName).branch(newBranch).get().revision;

    ReplicationQueue pullReplicationQueue =
        plugin.getSysInjector().getInstance(ReplicationQueue.class);

    HeadUpdatedListener.Event event = new FakeHeadUpdateEvent(master, newBranch, testProjectName);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onHeadUpdated(event);
    waitUntilReplicationCompleted(1);

    waitUntil(
        () -> {
          try {
            return gApi.projects().name(testProjectName).head().equals(newBranch);
          } catch (RestApiException e) {
            return false;
          }
        });

    assertTasksMetricScheduledAndCompleted(1);
    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
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
    FakeGitReferenceUpdatedEvent event =
        new FakeGitReferenceUpdatedEvent(
            project,
            sourceRef,
            ObjectId.zeroId().getName(),
            sourceCommit.getId().getName(),
            TEST_REPLICATION_REMOTE);
    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    waitUntilReplicationCompleted(1);

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceRef) != null);

      Ref targetBranchRef = getRef(repo, sourceRef);
      assertThat(targetBranchRef).isNotNull();
      assertThat(targetBranchRef.getObjectId()).isEqualTo(sourceCommit.getId());
    }

    assertThatEventListenerHasReceivedNumEvents(1);
    assertThatRefReplicatedEventsContainsStatus(ReplicationState.RefFetchResult.SUCCEEDED);
  }

  private void assertThatEventListenerHasReceivedNumEvents(int numExpectedEvents) {
    assertThat(eventListener.numEventsReceived()).isEqualTo(numExpectedEvents);
  }

  private void assertThatRefReplicatedEventsContainsStatus(
      ReplicationState.RefFetchResult refFetchResult) {
    Stream<FetchRefReplicatedEvent> replicatedStream = eventListener.eventsStream();
    assertThat(replicatedStream.map(FetchRefReplicatedEvent::getStatus))
        .contains(refFetchResult.toString());
  }

  private void waitUntilReplicationCompleted(int expected) throws Exception {
    waitUntilReplicationTask("completed", expected);
  }

  private void waitUntilReplicationFailed(int expected) throws Exception {
    waitUntilReplicationTask("failed", expected);
  }

  private void waitUntilReplicationTask(String status, int expected) throws Exception {
    if (isAsyncReplication()) {
      waitUntil(
          () ->
              inMemoryMetrics()
                  .counterValue("tasks/" + status, TEST_REPLICATION_REMOTE)
                  .filter(counter -> counter == expected)
                  .isPresent());
    }
  }

  private InMemoryMetricMaker inMemoryMetrics() {
    return getInstance(InMemoryMetricMaker.class);
  }

  private void assertTasksMetric(String taskMetric, long value) {
    assertThat(inMemoryMetrics().counterValue("tasks/" + taskMetric, TEST_REPLICATION_REMOTE))
        .hasValue(value);
  }

  private void assertEmptyTasksMetric(String taskMetric) {
    assertThat(inMemoryMetrics().counterValue("tasks/" + taskMetric, TEST_REPLICATION_REMOTE))
        .isEmpty();
  }
}
