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

import static java.nio.file.Files.createTempDirectory;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener.Event;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
  @Mock FetchRestApiClient.Factory fetchClientFactory;
  @Mock AccountInfo accountInfo;
  @Mock RevisionReader revReader;
  @Mock RevisionData revisionData;
  @Mock HttpResult httpResult;

  private RefsFilter refsFilter;
  private ReplicationQueue objectUnderTest;
  private SitePaths sitePaths;
  private Path pluginDataPath;

  @Before
  public void setup() throws IOException, LargeObjectException, RefUpdateException {
    Path sitePath = createTempPath("site");
    sitePaths = new SitePaths(sitePath);
    Path pluginDataPath = createTempPath("data");
    ReplicationConfig replicationConfig = new ReplicationFileBasedConfig(sitePaths, pluginDataPath);
    refsFilter = new RefsFilter(replicationConfig);
    when(source.getConnectionTimeout()).thenReturn(CONNECTION_TIMEOUT);
    when(source.wouldFetchProject(any())).thenReturn(true);
    when(source.wouldFetchRef(anyString())).thenReturn(true);
    ImmutableList<String> apis = ImmutableList.of("http://localhost:18080");
    when(source.getApis()).thenReturn(apis);
    when(sourceCollection.getAll()).thenReturn(Lists.newArrayList(source));
    when(rd.get()).thenReturn(sourceCollection);
    when(revReader.read(any(), anyString())).thenReturn(revisionData);
    when(fetchClientFactory.create(any())).thenReturn(fetchRestApiClient);
    when(fetchRestApiClient.callSendObject(any(), anyString(), any(), any()))
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

    verify(fetchRestApiClient).callSendObject(any(), anyString(), any(), any());
  }

  @Test
  public void shouldCallSendObjectWhenPatchSetRef() throws ClientProtocolException, IOException {
    Event event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();
    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callSendObject(any(), anyString(), any(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenIOException()
      throws ClientProtocolException, IOException, LargeObjectException, RefUpdateException {
    Event event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(revReader.read(any(), anyString())).thenThrow(IOException.class);

    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenLargeRef()
      throws ClientProtocolException, IOException, LargeObjectException, RefUpdateException {
    Event event = new TestEvent("refs/changes/01/1/1");
    objectUnderTest.start();

    when(revReader.read(any(), anyString())).thenThrow(LargeObjectException.class);

    objectUnderTest.onGitReferenceUpdated(event);

    verify(fetchRestApiClient).callFetch(any(), anyString(), any());
  }

  @Test
  public void shouldFallbackToCallFetchWhenParentObjectIsMissing()
      throws ClientProtocolException, IOException {
    Event event = new TestEvent("refs/changes/01/1/meta");
    objectUnderTest.start();

    when(httpResult.isSuccessful()).thenReturn(false);
    when(httpResult.isParentObjectMissing()).thenReturn(true);
    when(fetchRestApiClient.callSendObject(any(), anyString(), any(), any()))
        .thenReturn(httpResult);

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
    refsFilter = new RefsFilter(replicationConfig);

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
      return null;
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
}
