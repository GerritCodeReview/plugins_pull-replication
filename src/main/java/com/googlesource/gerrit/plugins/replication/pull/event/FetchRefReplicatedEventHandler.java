// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.ssh.SshKeyCache;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;

public class FetchRefReplicatedEventHandler implements EventListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final AllUsersName allUsers;
  private ChangeIndexer changeIndexer;
  private final SshKeyCache sshKeyCache;
  private final IdentifiedUser.GenericFactory userFactory;

  @Inject
  FetchRefReplicatedEventHandler(
      ChangeIndexer changeIndexer,
      AllUsersName allUsers,
      SshKeyCache sshKeyCache,
      IdentifiedUser.GenericFactory userFactory) {
    this.changeIndexer = changeIndexer;
    this.allUsers = allUsers;
    this.sshKeyCache = sshKeyCache;
    this.userFactory = userFactory;
  }

  @Override
  public void onEvent(Event event) {
    if (event instanceof FetchRefReplicatedEvent && isLocalEvent()) {
      FetchRefReplicatedEvent fetchRefReplicatedEvent = (FetchRefReplicatedEvent) event;
      handleAllUsersUpdates(fetchRefReplicatedEvent);
      if (!RefNames.isNoteDbMetaRef(fetchRefReplicatedEvent.getRefName())
          || !fetchRefReplicatedEvent
              .getStatus()
              .equals(ReplicationState.RefFetchResult.SUCCEEDED.toString())) {
        return;
      }

      Project.NameKey projectNameKey = fetchRefReplicatedEvent.getProjectNameKey();
      logger.atFine().log(
          "Indexing ref '%s' for project %s",
          fetchRefReplicatedEvent.getRefName(), projectNameKey.get());
      Change.Id changeId = Change.Id.fromRef(fetchRefReplicatedEvent.getRefName());
      if (changeId != null) {
        changeIndexer.index(projectNameKey, changeId);
      } else {
        logger.atWarning().log(
            "Couldn't get changeId from refName. Skipping indexing of change %s for project %s",
            fetchRefReplicatedEvent.getRefName(), projectNameKey.get());
      }
    }
  }

  private void handleAllUsersUpdates(FetchRefReplicatedEvent fetchRefReplicatedEvent) {
    Project.NameKey projectNameKey = fetchRefReplicatedEvent.getProjectNameKey();
    if (allUsers.equals(projectNameKey)) {
      Account.Id accountId = Account.Id.fromRef(fetchRefReplicatedEvent.getRefName());
      if (accountId != null) {
        userFactory.create(accountId).getUserName().ifPresent(sshKeyCache::evict);
      }
    }
  }

  private boolean isLocalEvent() {
    return Context.isLocalEvent();
  }
}
