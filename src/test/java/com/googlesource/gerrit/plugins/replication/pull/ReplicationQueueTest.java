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
import static java.nio.file.Files.createTempDirectory;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.BatchRefUpdateEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.filter.ApplyObjectsRefsFilter;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReplicationQueueTest {
  private static int CONNECTION_TIMEOUT = 1000000;
  private static final String LOCAL_INSTANCE_ID = "local instance id";
  private static final String FOREIGN_INSTANCE_ID = "any other instance id";
  private static final String TEST_REF_NAME = "refs/meta/heads/anyref";

  private static final Project.NameKey PROJECT = Project.nameKey("defaultProject");
  private static final String NEW_OBJECT_ID =
      ObjectId.fromString("3c1ddc050d7906adb0e29bc3bc46af8749b2f63b").getName();

  @Mock private WorkQueue wq;
  @Mock private Source source;
  @Mock private SourcesCollection sourceCollection;
  @Mock private Provider<SourcesCollection> rd;
  @Mock private DynamicItem<EventDispatcher> dis;
  @Mock ReplicationStateListeners sl;
  @Mock FetchRestApiClient fetchRestApiClient;
  @Mock FetchApiClient.Factory fetchClientFactory;
  @Mock AccountAttribute accountAttribute;
  @Mock RevisionReader revReader;
  @Mock RevisionData revisionData;
  @Mock HttpResult successfulHttpResult;
  @Mock HttpResult fetchHttpResult;
  @Mock HttpResult batchFetchHttpResult;
  @Mock RevisionData revisionDataWithParents;
  List<ObjectId> revisionDataParentObjectIds;
  @Mock HttpResult httpResult;
  @Mock HttpResult batchHttpResult;
  @Mock ApplyObjectsRefsFilter applyObjectsRefsFilter;

  @Mock Config config;
  ApplyObjectMetrics applyObjectMetrics;
  ReplicationQueueMetrics queueMetrics;
  ShutdownState shutdownState;

  @Captor ArgumentCaptor<String> stringCaptor;
  @Captor ArgumentCaptor<Project.NameKey> projectNameKeyCaptor;
  @Captor ArgumentCaptor<List<RevisionData>> revisionsDataCaptor;
  @Captor ArgumentCaptor<List<BatchApplyObjectData>> batchRefsCaptor;

  private ExcludedRefsFilter refsFilter;
  private ReplicationQueue objectUnderTest;
  private SitePaths sitePaths;
  private Path pluginDataPath;

  @Before
  public void setup() throws Exception {
    Path sitePath = createTempPath("site");
    sitePaths = new SitePaths(sitePath);
    Path pluginDataPath = createTempPath("data");
    ReplicationConfig replicationConfig = new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
    refsFilter = new ExcludedRefsFilter(replicationConfig);
    when(source.getConnectionTimeout()).thenReturn(CONNECTION_TIMEOUT);
    when(source.wouldFetchProject(any())).thenReturn(true);
    when(source.wouldFetchRef(anyString())).thenReturn(true);
    ImmutableList<String> apis = ImmutableList.of("http://localhost:18080");
    when(source.getApis()).thenReturn(apis);
    when(source.enableBatchedRefs()).thenReturn(true);
    when(sourceCollection.getAll()).thenReturn(Lists.newArrayList(source));
    when(rd.get()).thenReturn(sourceCollection);
    when(config.getBoolean("event", "stream-events", "enableBatchRefUpdatedEvents", false))
        .thenReturn(true);
    lenient()
        .when(revReader.read(any(), any(), anyString(), eq(0)))
        .thenReturn(Optional.of(revisionData));
    lenient().when(revReader.read(any(), anyString(), eq(0))).thenReturn(Optional.of(revisionData));
    lenient()
        .when(revReader.read(any(), any(), anyString(), eq(Integer.MAX_VALUE)))
        .thenReturn(Optional.of(revisionDataWithParents));
    lenient()
        .when(revReader.read(any(), anyString(), eq(Integer.MAX_VALUE)))
        .thenReturn(Optional.of(revisionDataWithParents));
    revisionDataParentObjectIds =
        Arrays.asList(
            ObjectId.fromString("9f8d52853089a3cf00c02ff7bd0817bd4353a95a"),
            ObjectId.fromString("b5d7bcf1d1c5b0f0726d10a16c8315f06f900bfb"));
    when(revisionDataWithParents.getParentObjetIds()).thenReturn(revisionDataParentObjectIds);

    when(fetchClientFactory.create(any())).thenReturn(fetchRestApiClient);
    lenient()
        .when(
            fetchRestApiClient.callSendObject(
                any(), anyString(), anyLong(), anyBoolean(), any(), any()))
        .thenReturn(httpResult);
    lenient()
        .when(fetchRestApiClient.callSendObjects(any(), anyString(), anyLong(), any(), any()))
        .thenReturn(httpResult);
    lenient()
        .when(fetchRestApiClient.callBatchSendObject(any(), any(), anyLong(), any()))
        .thenReturn(batchHttpResult);
    when(fetchRestApiClient.callFetch(any(), anyString(), any())).thenReturn(fetchHttpResult);
    when(fetchRestApiClient.callBatchFetch(any(), any(), any())).thenReturn(batchFetchHttpResult);
    when(fetchRestApiClient.initProject(any(), any(), anyLong(), any()))
        .thenReturn(successfulHttpResult);
    when(successfulHttpResult.isSuccessful()).thenReturn(true);
    when(httpResult.isSuccessful()).thenReturn(true);
    when(batchHttpResult.isSuccessful()).thenReturn(true);
    when(fetchHttpResult.isSuccessful()).thenReturn(true);
    when(batchFetchHttpResult.isSuccessful()).thenReturn(true);
    when(httpResult.isProjectMissing(any())).thenReturn(false);
    when(batchHttpResult.isProjectMissing(any())).thenReturn(false);
    when(applyObjectsRefsFilter.match(any())).thenReturn(false);

    applyObjectMetrics = new ApplyObjectMetrics("pull-replication", new DisabledMetricMaker());
    queueMetrics = new ReplicationQueueMetrics("pull-replication", new DisabledMetricMaker());
    shutdownState = new ShutdownState();

    objectUnderTest =
        new ReplicationQueue(
            wq,
            rd,
            dis,
            sl,
            fetchClientFactory,
            refsFilter,
            () -> revReader,
            applyObjectMetrics,
            queueMetrics,
            LOCAL_INSTANCE_ID,
            config,
            applyObjectsRefsFilter,
            shutdownState);
  }

  @Test
  public void shouldCallBatchSendObjectWhenMetaRef() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/meta");
    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchSendObject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldCallSendObjectWhenMetaRefAndRefUpdateEvent() throws Exception {
    when(config.getBoolean("event", "stream-events", "enableBatchRefUpdatedEvents", false))
        .thenReturn(false);

    objectUnderTest =
        new ReplicationQueue(
            wq,
            rd,
            dis,
            sl,
            fetchClientFactory,
            refsFilter,
            () -> revReader,
            applyObjectMetrics,
            queueMetrics,
            LOCAL_INSTANCE_ID,
            config,
            applyObjectsRefsFilter,
            shutdownState);

    Event event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchSendObject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldIgnoreEventWhenIsNotLocalInstanceId() throws Exception {
    Event event = generateBatchRefUpdateEvent(TEST_REF_NAME);
    event.instanceId = FOREIGN_INSTANCE_ID;
    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, never()).callBatchSendObject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldCallInitProjectWhenProjectIsMissing() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/meta");
    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isProjectMissing(any())).thenReturn(true);
    when(source.isCreateMissingRepositories()).thenReturn(true);

    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).initProject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldCallSendObjectReorderingRefsHavingMetaAtTheEnd() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/meta", "refs/changes/01/1/1");
    objectUnderTest.start();
    objectUnderTest.onEvent(event);
    verifySendObjectOrdering("refs/changes/01/1/1", "refs/changes/01/1/meta");
  }

  @Test
  public void shouldCallSendObjectKeepingMetaAtTheEnd() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1", "refs/changes/01/1/meta");
    objectUnderTest.start();
    objectUnderTest.onEvent(event);
    verifySendObjectOrdering("refs/changes/01/1/1", "refs/changes/01/1/meta");
  }

  @Test
  public void shouldNotCallInitProjectWhenReplicateNewRepositoriesNotSet() throws Exception {
    Event event = new TestEvent("refs/changes/01/1/meta");

    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, never()).initProject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldNotCallInitProjectWhenProjectWithoutConfiguration() throws Exception {
    Event event = new TestEvent("refs/changes/01/1/meta");
    lenient().when(httpResult.isSuccessful()).thenReturn(false);

    lenient().when(httpResult.isProjectMissing(any())).thenReturn(true);
    lenient().when(source.isCreateMissingRepositories()).thenReturn(true);
    lenient()
        .when(revReader.read(any(), any(), eq(RefNames.REFS_CONFIG), anyInt()))
        .thenReturn(Optional.empty());

    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, never()).initProject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldCallBatchSendObjectWhenPatchSetRefAndRefUpdateEvent() throws Exception {
    when(config.getBoolean("event", "stream-events", "enableBatchRefUpdatedEvents", false))
        .thenReturn(false);

    objectUnderTest =
        new ReplicationQueue(
            wq,
            rd,
            dis,
            sl,
            fetchClientFactory,
            refsFilter,
            () -> revReader,
            applyObjectMetrics,
            queueMetrics,
            LOCAL_INSTANCE_ID,
            config,
            applyObjectsRefsFilter,
            shutdownState);
  }

  @Test
  public void shouldCallBatchSendObjectWhenPatchSetRef() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");
    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchSendObject(any(), any(), anyLong(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenIOException() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/meta");

    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString(), anyInt())).thenThrow(IOException.class);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchFetch(any(), any(), any());
  }

  @Test
  public void shouldFallbackToCallBatchFetchWhenLargeRef() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");

    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString(), anyInt())).thenReturn(Optional.empty());

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchFetch(any(), any(), any());
  }

  @Test
  public void
      shouldFallbackToCallBatchFetchWhenParentObjectIsMissingAndRefDoesntMatchApplyObjectsRefsFilter()
          throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(true);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchFetch(any(), any(), any());
  }

  @Test
  public void
      shouldFallbackToApplyObjectsForEachRefWhenParentObjectIsMissingAndRefMatchesApplyObjectsRefFilter()
          throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1", "refs/changes/02/1/1");
    objectUnderTest.start();

    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(true);
    when(applyObjectsRefsFilter.match(any())).thenReturn(true);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, times(2))
        .callSendObjects(any(), anyString(), anyLong(), any(), any());
    verify(fetchRestApiClient, never()).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallBatchFetchWhenParentObjectNotMissingButApplyObjectFails()
      throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(false);
    lenient().when(httpResult.isSuccessful()).thenReturn(false);
    lenient().when(httpResult.isParentObjectMissing()).thenReturn(false);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient).callBatchFetch(any(), any(), any());
  }

  @Test
  public void shouldFallbackToApplyAllParentObjectsWhenParentObjectIsMissingOnMetaRef()
      throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(true);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, times(1))
        .callSendObjects(any(), anyString(), anyLong(), revisionsDataCaptor.capture(), any());
    List<List<RevisionData>> revisionsDataValues = revisionsDataCaptor.getAllValues();
    assertThat(revisionsDataValues).hasSize(1);

    List<RevisionData> firstRevisionsValues = revisionsDataValues.get(0);
    assertThat(firstRevisionsValues).hasSize(1 + revisionDataParentObjectIds.size());
    assertThat(firstRevisionsValues).contains(revisionData);
  }

  @Test
  public void shouldFallbackToApplyAllParentObjectsWhenParentObjectIsMissingOnAllowedRefs()
      throws Exception {
    String refName = "refs/tags/test-tag";
    Event event = generateBatchRefUpdateEvent(refName);
    objectUnderTest.start();

    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(true);
    when(applyObjectsRefsFilter.match(refName)).thenReturn(true);

    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, times(1))
        .callSendObjects(any(), anyString(), anyLong(), revisionsDataCaptor.capture(), any());
    List<List<RevisionData>> revisionsDataValues = revisionsDataCaptor.getAllValues();
    assertThat(revisionsDataValues).hasSize(1);

    List<RevisionData> firstRevisionsValues = revisionsDataValues.get(0);
    assertThat(firstRevisionsValues).hasSize(1 + revisionDataParentObjectIds.size());
    assertThat(firstRevisionsValues).contains(revisionData);
  }

  @Test
  public void shouldCallSendObjectsIfBatchedRefsNotEnabledAtSource() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");
    when(source.enableBatchedRefs()).thenReturn(false);
    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, never()).callBatchSendObject(any(), any(), anyLong(), any());
    verify(fetchRestApiClient).callSendObjects(any(), anyString(), anyLong(), any(), any());
  }

  @Test
  public void shouldCallFetchIfBatchedRefsNotEnabledAtSource() throws Exception {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1");
    when(source.enableBatchedRefs()).thenReturn(false);
    when(httpResult.isSuccessful()).thenReturn(false);
    when(httpResult.isParentObjectMissing()).thenReturn(false);

    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, never()).callBatchFetch(any(), any(), any());
    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldCallBatchFetchForAllTheRefsInTheBatchIfApplyObjectFails()
      throws Exception, URISyntaxException {
    Event event = generateBatchRefUpdateEvent("refs/changes/01/1/1", "refs/changes/02/1/1");
    when(batchHttpResult.isSuccessful()).thenReturn(false);
    when(batchHttpResult.isParentObjectMissing()).thenReturn(true);
    when(applyObjectsRefsFilter.match(any())).thenReturn(true, true);
    when(httpResult.isSuccessful()).thenReturn(true, false);

    objectUnderTest.start();
    objectUnderTest.onEvent(event);

    verify(fetchRestApiClient, times(2))
        .callSendObjects(any(), anyString(), anyLong(), any(), any());
    verify(fetchRestApiClient)
        .callBatchFetch(
            PROJECT,
            List.of("refs/changes/01/1/1", "refs/changes/02/1/1"),
            new URIish("http://localhost:18080"));
  }

  @Test
  public void shouldSkipEventWhenMultiSiteVersionRef() throws Exception {
    FileBasedConfig fileConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    fileConfig.setString("replication", null, "excludeRefs", "refs/multi-site/version");
    fileConfig.save();
    ReplicationConfig replicationConfig = new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
    refsFilter = new ExcludedRefsFilter(replicationConfig);

    objectUnderTest =
        new ReplicationQueue(
            wq,
            rd,
            dis,
            sl,
            fetchClientFactory,
            refsFilter,
            () -> revReader,
            applyObjectMetrics,
            queueMetrics,
            LOCAL_INSTANCE_ID,
            config,
            applyObjectsRefsFilter,
            shutdownState);
    Event event = generateBatchRefUpdateEvent("refs/multi-site/version");
    objectUnderTest.onEvent(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountAttribute);
  }

  @Test
  public void shouldSetShutdownStateWhenStopping() throws Exception {
    objectUnderTest.stop();
    assertThat(shutdownState.isShuttingDown()).isTrue();
  }

  @Test
  public void shouldSkipEventWhenStarredChangesRef() {
    Event event = generateBatchRefUpdateEvent("refs/starred-changes/41/2941/1000000");
    objectUnderTest.onEvent(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountAttribute);
  }

  @Test
  public void shouldCallDeleteWhenReplicateProjectDeletionsTrue() {
    when(source.wouldDeleteProject(any())).thenReturn(true);

    String projectName = "testProject";
    FakeProjectDeletedEvent event = new FakeProjectDeletedEvent(projectName);

    objectUnderTest.start();
    objectUnderTest.onProjectDeleted(event);

    verify(source, times(1))
        .scheduleDeleteProject(stringCaptor.capture(), projectNameKeyCaptor.capture());
    assertThat(stringCaptor.getValue()).isEqualTo(source.getApis().get(0));
    assertThat(projectNameKeyCaptor.getValue()).isEqualTo(Project.NameKey.parse(projectName));
  }

  @Test
  public void shouldNotCallDeleteWhenProjectNotToDelete() {
    when(source.wouldDeleteProject(any())).thenReturn(false);

    FakeProjectDeletedEvent event = new FakeProjectDeletedEvent("testProject");

    objectUnderTest.start();
    objectUnderTest.onProjectDeleted(event);

    verify(source, never()).scheduleDeleteProject(any(), any());
  }

  @Test
  public void shouldScheduleUpdateHeadWhenWouldFetchProject() {
    when(source.wouldFetchProject(any())).thenReturn(true);

    String projectName = "aProject";
    String newHEAD = "newHEAD";

    objectUnderTest.start();
    objectUnderTest.onHeadUpdated(new FakeHeadUpdateEvent("oldHead", newHEAD, projectName));
    verify(source, times(1))
        .scheduleUpdateHead(any(), projectNameKeyCaptor.capture(), stringCaptor.capture());

    assertThat(stringCaptor.getValue()).isEqualTo(newHEAD);
    assertThat(projectNameKeyCaptor.getValue()).isEqualTo(Project.NameKey.parse(projectName));
  }

  @Test
  public void shouldNotScheduleUpdateHeadWhenNotWouldFetchProject() {
    when(source.wouldFetchProject(any())).thenReturn(false);

    String projectName = "aProject";
    String newHEAD = "newHEAD";

    objectUnderTest.start();
    objectUnderTest.onHeadUpdated(new FakeHeadUpdateEvent("oldHead", newHEAD, projectName));
    verify(source, never()).scheduleUpdateHead(any(), any(), any());
  }

  protected static Path createTempPath(String prefix) throws Exception {
    return createTempDirectory(prefix);
  }

  private BatchRefUpdateEvent generateBatchRefUpdateEvent(String... refs) {
    List<RefUpdateAttribute> refUpdates =
        Arrays.stream(refs)
            .map(
                ref -> {
                  RefUpdateAttribute upd = new RefUpdateAttribute();
                  upd.newRev = NEW_OBJECT_ID;
                  upd.oldRev = ObjectId.zeroId().getName();
                  upd.project = PROJECT.get();
                  upd.refName = ref;
                  return upd;
                })
            .collect(Collectors.toList());

    BatchRefUpdateEvent event =
        new BatchRefUpdateEvent(
            PROJECT, Suppliers.ofInstance(refUpdates), Suppliers.ofInstance(accountAttribute));
    event.instanceId = LOCAL_INSTANCE_ID;
    return event;
  }

  private void verifySendObjectOrdering(String firstRef, String secondRef) throws Exception {
    verify(fetchRestApiClient)
        .callBatchSendObject(any(), batchRefsCaptor.capture(), anyLong(), any());
    List<BatchApplyObjectData> batchRefs = batchRefsCaptor.getValue();

    assertThat(batchRefs.get(0).refName()).isEqualTo(firstRef);
    assertThat(batchRefs.get(1).refName()).isEqualTo(secondRef);
  }

  private class TestEvent extends RefUpdatedEvent {

    public TestEvent() {
      this(TEST_REF_NAME);
    }

    public TestEvent(String refName) {
      this(
          refName,
          "defaultProject",
          ObjectId.fromString("3c1ddc050d7906adb0e29bc3bc46af8749b2f63b"));
    }

    public TestEvent(String refName, String projectName, ObjectId newObjectId) {
      RefUpdateAttribute upd = new RefUpdateAttribute();
      upd.newRev = newObjectId.getName();
      upd.oldRev = ObjectId.zeroId().getName();
      upd.project = projectName;
      upd.refName = refName;
      this.refUpdate = Suppliers.ofInstance(upd);
      this.instanceId = LOCAL_INSTANCE_ID;
    }
  }

  private class FakeProjectDeletedEvent implements ProjectDeletedListener.Event {
    private String projectName;

    public FakeProjectDeletedEvent(String projectName) {
      this.projectName = projectName;
    }

    @Override
    public NotifyHandling getNotify() {
      return null;
    }

    @Override
    public String getProjectName() {
      return projectName;
    }
  }
}
