// Copyright (C) 2024 The Android Open Source Project
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
import static com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck.HEALTHCHECK_NAME_SUFFIX;
import static com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck.PERIOD_OF_TIME_FIELD;

import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckConfig;
import com.googlesource.gerrit.plugins.healthcheck.HealthCheckExtensionApiModule;
import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;
import com.googlesource.gerrit.plugins.replication.ApiModule;
import com.googlesource.gerrit.plugins.replication.ReplicationConfigModule;
import com.googlesource.gerrit.plugins.replication.pull.health.PullReplicationTasksHealthCheck;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule =
        "com.googlesource.gerrit.plugins.replication.pull.PullReplicationHealthCheckIT$PullReplicationHealthCheckTestModule",
    httpModule = "com.googlesource.gerrit.plugins.replication.pull.api.HttpModule")
public class PullReplicationHealthCheckIT extends PullReplicationSetupBase {

  public static class PullReplicationHealthCheckTestModule extends AbstractModule {
    private final PullReplicationModule pullReplicationModule;

    @Inject
    PullReplicationHealthCheckTestModule(
        ReplicationConfigModule configModule, InMemoryMetricMaker memMetric) {
      this.pullReplicationModule = new PullReplicationModule(configModule, memMetric);
    }

    @Override
    protected void configure() {
      install(new ApiModule());
      install(new HealthCheckExtensionApiModule());
      install(pullReplicationModule);

      DynamicSet.bind(binder(), EventListener.class)
          .to(PullReplicationHealthCheckIT.BufferedEventListener.class)
          .asEagerSingleton();
    }
  }

  @Inject private SitePaths sitePaths;
  private PullReplicationHealthCheckIT.BufferedEventListener eventListener;
  private final int periodOfTimeSecs = 1;

  @Singleton
  public static class BufferedEventListener implements EventListener {

    private final List<Event> eventsReceived;
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
  public void setUpTestPlugin() throws Exception {

    FileBasedConfig config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    config.setString("replication", null, "syncRefs", "^$");
    config.setString(
        HealthCheckConfig.HEALTHCHECK,
        "pull-replication" + HEALTHCHECK_NAME_SUFFIX,
        PERIOD_OF_TIME_FIELD,
        periodOfTimeSecs + " sec");
    config.save();

    super.setUpTestPlugin(true);
    eventListener = getInstance(PullReplicationHealthCheckIT.BufferedEventListener.class);
  }

  @Override
  protected boolean useBatchRefUpdateEvent() {
    return false;
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
    config.setInt("replication", null, "maxApiPayloadSize", 1);
    config.save();
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldReportInstanceHealthyWhenThereAreNoOutstandingReplicationTasks()
      throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));
    PullReplicationTasksHealthCheck healthcheck =
        getInstance(PullReplicationTasksHealthCheck.class);

    PushOneCommit.Result pushResult = createChange();
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

    eventListener.clearFilter(FetchRefReplicatedEvent.TYPE);
    pullReplicationQueue.onEvent(event);
    // If replication hasn't started yet, the healthcheck returns PASSED
    // but hasReplicationFinished() would be false, ending up in producing
    // flaky failures.
    waitUntil(() -> hasReplicationBeenScheduledOrStarted());

    waitUntil(
        () -> {
          boolean replicationFinished = hasReplicationFinished();
          if (!replicationFinished) {
            boolean healthCheckPassed = healthcheck.run().result == HealthCheck.Result.PASSED;

            assertWithMessage("Instance reported healthy while waiting for replication to finish")
                // Racy condition: we need to make sure that this isn't a false alarm
                // and accept the case when the replication finished between the
                // if(!replicationFinished) check and the assertion here
                .that(!healthCheckPassed || hasReplicationFinished())
                .isTrue();
          }
          return replicationFinished;
        });

    assertThat(healthcheck.run().result).isEqualTo(HealthCheck.Result.PASSED);
  }

  private boolean hasReplicationFinished() {
    return inMemoryMetrics()
        .counterValue("tasks/completed", TEST_REPLICATION_REMOTE)
        .filter(counter -> counter > 0)
        .isPresent();
  }

  private boolean hasReplicationBeenScheduledOrStarted() {
    return inMemoryMetrics()
            .counterValue("tasks/scheduled", TEST_REPLICATION_REMOTE)
            .filter(counter -> counter > 0)
            .isPresent()
        || inMemoryMetrics()
            .counterValue("tasks/started", TEST_REPLICATION_REMOTE)
            .filter(counter -> counter > 0)
            .isPresent();
  }

  private InMemoryMetricMaker inMemoryMetrics() {
    return getInstance(InMemoryMetricMaker.class);
  }
}
