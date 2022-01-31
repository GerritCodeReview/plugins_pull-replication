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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener.Event;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.Result;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
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
  @Mock Result httpResult;

  @Captor ArgumentCaptor<String> stringCaptor;
  @Captor ArgumentCaptor<Project.NameKey> projectNameKeyCaptor;

  private ExcludedRefsFilter refsFilter;
  private ReplicationQueue objectUnderTest;
  private SitePaths sitePaths;
  private Path pluginDataPath;

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
    when(revReader.read(any(), any(), anyString())).thenReturn(Optional.of(revisionData));
    when(fetchClientFactory.create(any())).thenReturn(fetchRestApiClient);
    when(fetchRestApiClient.callSendObject(any(), anyString(), anyBoolean(), any(), any()))
        .thenReturn(httpResult);
    when(fetchRestApiClient.callFetch(any(), anyString(), any())).thenReturn(httpResult);
    when(httpResult.isSuccessful()).thenReturn(true);

    objectUnderTest =
        new ReplicationQueue(wq, rd, dis, sl, fetchClientFactory, refsFilter, revReader);
  }

  @Test
  public void shouldCallSendObjectWhenMetaRef() throws ClientProtocolException, IOException {
    Event event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();
    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callSendObject(any(), anyString(), eq(false), any(), any());
  }

  @Test
  public void shouldCallSendObjectWhenPatchSetRef() throws ClientProtocolException, IOException {
    Event event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();
    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callSendObject(any(), anyString(), eq(false), any(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenIOException()
      throws ClientProtocolException, IOException, LargeObjectException, RefUpdateException {
    Event event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString())).thenThrow(IOException.class);

    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenLargeRef()
      throws ClientProtocolException, IOException, LargeObjectException, RefUpdateException {
    Event event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(revReader.read(any(), any(), anyString())).thenReturn(Optional.empty());

    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldSkipEventWhenUsersRef() {
    Event event = new TestEvent("refs/users/00/1000000");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenGroupsRef() {
    Event event = new TestEvent("refs/groups/a1/a16d5b33cc789d60b682c654f03f9cc2feb12975");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenGroupNamesRef() {
    Event event = new TestEvent("refs/meta/group-names");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenMultiSequenceRef() {
    Event event = new TestEvent("refs/sequences/changes");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
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
        new ReplicationQueue(wq, rd, dis, sl, fetchClientFactory, refsFilter, revReader);
    Event event = new TestEvent("refs/multi-site/version");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenStarredChangesRef() {
    Event event = new TestEvent("refs/starred-changes/41/2941/1000000");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenConfigRef() {
    Event event = new TestEvent("refs/meta/config");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenExternalIdsRef() {
    Event event = new TestEvent("refs/meta/external-ids");
    objectUnderTest.onGitReferenceUpdated(event);

    verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldCallDeleteWhenReplicateProjectDeletionsTrue() throws IOException {
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
  public void shouldNotCallDeleteWhenProjectNotToDelete() throws IOException {
    when(source.wouldDeleteProject(any())).thenReturn(false);

    FakeProjectDeletedEvent event = new FakeProjectDeletedEvent("testProject");

    objectUnderTest.start();
    objectUnderTest.onProjectDeleted(event);

    verify(source, never()).scheduleDeleteProject(any(), any());
  }

  @Test
  public void shouldScheduleUpdateHeadWhenWouldFetchProject() throws IOException {
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
  public void shouldNotScheduleUpdateHeadWhenNotWouldFetchProject() throws IOException {
    when(source.wouldFetchProject(any())).thenReturn(false);

    String projectName = "aProject";
    String newHEAD = "newHEAD";

    objectUnderTest.start();
    objectUnderTest.onHeadUpdated(new FakeHeadUpdateEvent("oldHead", newHEAD, projectName));
    verify(source, never()).scheduleUpdateHead(any(), any(), any());
  }

  protected static Path createTempPath(String prefix) throws IOException {
    return createTempDirectory(prefix);
  }

  private class TestEvent implements GitReferenceUpdatedListener.Event {
    private String refName;
    private String projectName;
    private ObjectId newObjectId;

    public TestEvent(String refName) {
      this(refName, "defaultProject", ObjectId.zeroId());
    }

    public TestEvent(String refName, String projectName, ObjectId newObjectId) {
      this.refName = refName;
      this.projectName = projectName;
      this.newObjectId = newObjectId;
    }

    @Override
    public String getRefName() {
      return refName;
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
    public String getOldObjectId() {
      return ObjectId.zeroId().getName();
    }

    @Override
    public String getNewObjectId() {
      return newObjectId.getName();
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

    @Override
    public AccountInfo getUpdater() {
      return null;
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
