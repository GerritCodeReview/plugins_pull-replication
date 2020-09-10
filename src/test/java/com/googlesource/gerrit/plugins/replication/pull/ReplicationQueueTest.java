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

import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener.Event;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReplicationQueueTest {
  @Mock private WorkQueue wq;
  @Mock private Provider<SourcesCollection> rd;
  @Mock private DynamicItem<EventDispatcher> dis;
  @Mock ReplicationStateListeners sl;
  @Mock FetchRestApiClient.Factory fetchClientFactory;
  @Mock AccountInfo accountInfo;

  private ReplicationQueue objectUnderTest;

  @Before
  public void setup() {
    objectUnderTest = new ReplicationQueue(wq, rd, dis, sl, fetchClientFactory);
  }

  @Test
  public void shouldSkipEventWhenUsersRef() {
    Event event = new TestEvent("refs/users/00/1000000");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenGroupsRef() {
    Event event = new TestEvent("refs/groups/a1/a16d5b33cc789d60b682c654f03f9cc2feb12975");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenGroupNamesRef() {
    Event event = new TestEvent("refs/meta/group-names");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenMultiSequenceRef() {
    Event event = new TestEvent("refs/sequences/changes");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenMultiSiteVersionRef() {
    Event event = new TestEvent("refs/multi-site/version");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenStarredChangesRef() {
    Event event = new TestEvent("refs/starred-changes/41/2941/1000000");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenConfigRef() {
    Event event = new TestEvent("refs/meta/config");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  @Test
  public void shouldSkipEventWhenExternalIdsRef() {
    Event event = new TestEvent("refs/meta/external-ids");
    objectUnderTest.onGitReferenceUpdated(event);

    Mockito.verifyZeroInteractions(wq, rd, dis, sl, fetchClientFactory, accountInfo);
  }

  private class TestEvent implements GitReferenceUpdatedListener.Event {
    private String refName;

    public TestEvent(String refName) {
      this.refName = refName;
    }

    @Override
    public String getRefName() {
      return refName;
    }

    @Override
    public String getProjectName() {
      return null;
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
      return null;
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
