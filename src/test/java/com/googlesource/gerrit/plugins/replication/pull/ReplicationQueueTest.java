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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener.UpdatedRef;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.DisabledMetricMaker;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReplicationQueueTest {
  private static int CONNECTION_TIMEOUT = 1000000;

  @Mock private WorkQueue wq;
  @Mock private Source source;
  @Mock private SourcesCollection sourceCollection;
  @Mock private Provider<SourcesCollection> rd;
  @Mock private DynamicItem<EventDispatcher> dis;
  @Mock ReplicationStateListeners sl;
  @Mock FetchRestApiClient fetchRestApiClient;
  @Mock FetchApiClient.Factory fetchClientFactory;
  @Mock AccountInfo accountInfo;
  @Mock RevisionReader revReader;
  @Mock RevisionData revisionData;
  @Mock HttpResult successfulHttpResult;
  @Mock HttpResult fetchHttpResult;
  @Mock RevisionData revisionDataWithParents;
  List<ObjectId> revisionDataParentObjectIds;
  @Mock HttpResult httpResult;
  ApplyObjectMetrics applyObjectMetrics;
  FetchReplicationMetrics fetchMetrics;

  @Captor ArgumentCaptor<String> stringCaptor;
  @Captor ArgumentCaptor<Project.NameKey> projectNameKeyCaptor;
  @Captor ArgumentCaptor<List<RevisionData>> revisionsDataCaptor;

  private ExcludedRefsFilter refsFilter;
  private ReplicationQueue objectUnderTest;
  private SitePaths sitePaths;
  private Path pluginDataPath;
  private final TestMetricMaker testMetricMaker = new TestMetricMaker();

  @Before
  public void setup() throws IOException, LargeObjectException {
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
    when(sourceCollection.getAll()).thenReturn(Lists.newArrayList(source));
    when(rd.get()).thenReturn(sourceCollection);
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
        .when(fetchRestApiClient.callSendObject(any(), anyString(), anyBoolean(), any(), any()))
        .thenReturn(httpResult);
    lenient()
        .when(fetchRestApiClient.callSendObjects(any(), anyString(), any(), any()))
        .thenReturn(httpResult);
    when(fetchRestApiClient.callFetch(any(), anyString(), any())).thenReturn(fetchHttpResult);
    when(fetchRestApiClient.initProject(any(), any())).thenReturn(successfulHttpResult);
    when(successfulHttpResult.isSuccessful()).thenReturn(true);
    when(httpResult.isSuccessful()).thenReturn(true);
    when(fetchHttpResult.isSuccessful()).thenReturn(true);
    when(httpResult.isProjectMissing(any())).thenReturn(false);

    testMetricMaker.reset();
    applyObjectMetrics = new ApplyObjectMetrics("pull-replication", new DisabledMetricMaker());
    fetchMetrics = new FetchReplicationMetrics("pull-replication", testMetricMaker);

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
            fetchMetrics);
  }

  @Test
  public void shouldCallSendObjectWhenMetaRef() throws ClientProtocolException, IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();
    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callSendObjects(any(), anyString(), any(), any());
  }

  @Test
  public void shouldCallInitProjectWhenProjectIsMissing() throws IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    when(httpResult.isSuccessful()).thenReturn(false);
    when(httpResult.isProjectMissing(any())).thenReturn(true);
    when(source.isCreateMissingRepositories()).thenReturn(true);

    objectUnderTest.start();
    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).initProject(any(), any());
  }

  @Test
  public void shouldNotCallInitProjectWhenReplicateNewRepositoriesNotSet() throws IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    when(httpResult.isSuccessful()).thenReturn(false);
    when(httpResult.isProjectMissing(any())).thenReturn(true);
    when(source.isCreateMissingRepositories()).thenReturn(false);

    objectUnderTest.start();
    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient, never()).initProject(any(), any());
  }

  @Test
  public void shouldCallSendObjectWhenPatchSetRef() throws ClientProtocolException, IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();
    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callSendObjects(any(), anyString(), any(), any());
  }

  @Test
  public void shouldCallSendObjectReorderingRefsHavingMetaAtTheEnd()
      throws ClientProtocolException, IOException {
    sendRefUpdatedEvents("refs/changes/01/1/meta", "refs/changes/01/1/1");
    verifySendObjectOrdering("refs/changes/01/1/1", "refs/changes/01/1/meta");
  }

  @Test
  public void shouldCallSendObjectKeepingMetaAtTheEnd()
      throws ClientProtocolException, IOException {
    sendRefUpdatedEvents("refs/changes/01/1/1", "refs/changes/01/1/meta");
    verifySendObjectOrdering("refs/changes/01/1/1", "refs/changes/01/1/meta");
  }

  private void sendRefUpdatedEvents(String firstRef, String secondRef) {
    objectUnderTest.start();
    objectUnderTest.onGitBatchRefUpdate(new TestEvent(firstRef, secondRef));
  }

  private void verifySendObjectOrdering(String firstRef, String secondRef)
      throws ClientProtocolException, IOException {
    InOrder inOrder = inOrder(fetchRestApiClient);

    inOrder.verify(fetchRestApiClient).callSendObjects(any(), eq(firstRef), any(), any());
    inOrder.verify(fetchRestApiClient).callSendObjects(any(), eq(secondRef), any(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenIOException()
      throws ClientProtocolException, IOException, LargeObjectException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString(), anyInt())).thenThrow(IOException.class);

    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenLargeRef()
      throws ClientProtocolException, IOException, LargeObjectException {
    TestEvent event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString(), anyInt())).thenReturn(Optional.empty());

    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenParentObjectIsMissing()
      throws ClientProtocolException, IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(httpResult.isSuccessful()).thenReturn(false);
    when(httpResult.isParentObjectMissing()).thenReturn(true);
    when(fetchRestApiClient.callSendObjects(any(), anyString(), any(), any()))
        .thenReturn(httpResult);

    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToApplyAllParentObjectsWhenParentObjectIsMissingOnMetaRef()
      throws ClientProtocolException, IOException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(httpResult.isSuccessful()).thenReturn(false, true);
    when(httpResult.isParentObjectMissing()).thenReturn(true, false);
    when(fetchRestApiClient.callSendObjects(any(), anyString(), any(), any()))
        .thenReturn(httpResult);

    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient, times(2))
        .callSendObjects(any(), anyString(), revisionsDataCaptor.capture(), any());
    List<List<RevisionData>> revisionsDataValues = revisionsDataCaptor.getAllValues();
    assertThat(revisionsDataValues).hasSize(2);

    List<RevisionData> firstRevisionsValues = revisionsDataValues.get(0);
    assertThat(firstRevisionsValues).hasSize(1);
    assertThat(firstRevisionsValues).contains(revisionData);

    List<RevisionData> secondRevisionsValues = revisionsDataValues.get(1);
    assertThat(secondRevisionsValues).hasSize(1 + revisionDataParentObjectIds.size());
  }

  @Test
  public void shouldSkipEventWhenMultiSiteVersionRef() throws IOException {
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
            fetchMetrics);
    TestEvent event = new TestEvent("refs/multi-site/version");
    objectUnderTest.onGitBatchRefUpdate(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenStarredChangesRef() {
    TestEvent event = new TestEvent("refs/starred-changes/41/2941/1000000");
    objectUnderTest.onGitBatchRefUpdate(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
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

  @Test
  public void shouldUpdateTheFetchSchedulingTaskMetricWhenTargetAcceptsAFetchTask()
      throws IOException, LargeObjectException {
    TestEvent event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString(), anyInt())).thenThrow(IOException.class);

    objectUnderTest.onGitBatchRefUpdate(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
    assertThat(testMetricMaker.getTimer("replication_fetch_task_scheduling_latency"))
        .isGreaterThan(0);
  }

  protected static Path createTempPath(String prefix) throws IOException {
    return createTempDirectory(prefix);
  }

  private static class TestEvent implements GitBatchRefUpdateListener.Event {
    private String refName;
    private String projectName;
    private List<UpdatedRef> refs;

    public TestEvent(String... refNames) {
      this(
          "defaultProject",
          Arrays.stream(refNames)
              .map(refName -> updateRef(refName, ObjectId.zeroId()))
              .collect(Collectors.toUnmodifiableList()));
    }

    public TestEvent(String projectName, List<UpdatedRef> refs) {
      this.projectName = projectName;
      this.refs = refs;
    }

    @Override
    public String getProjectName() {
      return projectName;
    }

    @Override
    public NotifyHandling getNotify() {
      return null;
    }

    @Override
    public AccountInfo getUpdater() {
      return null;
    }

    @Override
    public Set<UpdatedRef> getUpdatedRefs() {
      return refs.stream().collect(Collectors.toSet());
    }

    private static final GitBatchRefUpdateListener.UpdatedRef updateRef(
        String refName, ObjectId refObjectId) {
      return new GitBatchRefUpdateListener.UpdatedRef() {

        @Override
        public String getRefName() {
          return refName;
        }

        @Override
        public String getOldObjectId() {
          return ObjectId.zeroId().getName();
        }

        @Override
        public String getNewObjectId() {
          return refObjectId.getName();
        }

        @Override
        public boolean isCreate() {
          return false;
        }

        @Override
        public boolean isDelete() {
          return false;
        }

        @Override
        public boolean isNonFastForward() {
          return false;
        }
      };
    }

    @Override
    public Set<String> getRefNames() {
      return Set.of(refName);
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
