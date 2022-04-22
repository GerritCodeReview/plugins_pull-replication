// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.fetch.DeleteRef;
import java.io.IOException;
import org.eclipse.jgit.lib.RefUpdate;

public class DeleteRefCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PullReplicationStateLogger fetchStateLog;
  private final DeleteRef deleteRef;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final ProjectCache projectCache;
  private final PermissionBackend permissionBackend;
  private final Provider<CurrentUser> userProvider;

  @Inject
  public DeleteRefCommand(
      PullReplicationStateLogger fetchStateLog,
      ProjectCache projectCache,
      DeleteRef deleteRef,
      DynamicItem<EventDispatcher> eventDispatcher,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> userProvider) {
    this.fetchStateLog = fetchStateLog;
    this.projectCache = projectCache;
    this.deleteRef = deleteRef;
    this.eventDispatcher = eventDispatcher;
    this.permissionBackend = permissionBackend;
    this.userProvider = userProvider;
  }

  public void deleteRef(Project.NameKey name, String refName, String sourceLabel)
      throws IOException, RestApiException {
    try {
      repLog.info("Delete ref from {} for project {}, ref name {}", sourceLabel, name, refName);
      ProjectState projectState =
          projectCache
              .get(name)
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          String.format("Project %s was not found", name)));

      try {
        Context.setLocalEvent(true);
        projectState.checkStatePermitsWrite();
        // When triggered internally(for example by consuming stream events) user is not provided
        // and internal user is returned. Reference deletion should be always allowed for internal
        // user.
        if (!userProvider.get().isInternalUser()) {
          permissionBackend
              .user(userProvider.get())
              .project(projectState.getNameKey())
              .ref(refName)
              .check(RefPermission.DELETE);
        }
        deleteRef.deleteSingleRef(projectState, refName);

        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceLabel,
                    ReplicationState.RefFetchResult.SUCCEEDED,
                    RefUpdate.Result.FORCED));
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log(
            "Unexpected error while trying to delete ref '%s' on project %s and notifying it",
            refName, name);
        throw RestApiException.wrap(e.getMessage(), e);
      } catch (ResourceConflictException e) {
        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceLabel,
                    ReplicationState.RefFetchResult.FAILED,
                    RefUpdate.Result.LOCK_FAILURE));
        String message =
            String.format(
                "RefUpdate lock failure for: sourceLabel=%s, project=%s, refName=%s",
                sourceLabel, name, refName);
        logger.atSevere().withCause(e).log(message);
        fetchStateLog.error(message);
        throw e;
      } finally {
        Context.unsetLocalEvent();
      }

      repLog.info(
          "Delete ref from {} for project {}, ref name {} completed", sourceLabel, name, refName);
    } catch (PermissionBackendException e) {
      throw RestApiException.wrap(e.getMessage(), e);
    }
  }
}
