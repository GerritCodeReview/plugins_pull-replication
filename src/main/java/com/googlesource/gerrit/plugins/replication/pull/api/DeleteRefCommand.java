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
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.LocalGitRepositoryManagerProvider;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState.RefFetchResult;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;

public class DeleteRefCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PullReplicationStateLogger fetchStateLog;
  private final ApplyObject applyObject;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final ProjectCache projectCache;
  private final GitRepositoryManager gitManager;

  @Inject
  public DeleteRefCommand(
      PullReplicationStateLogger fetchStateLog,
      ProjectCache projectCache,
      ApplyObject applyObject,
      DynamicItem<EventDispatcher> eventDispatcher,
      LocalGitRepositoryManagerProvider gitManagerProvider) {
    this.fetchStateLog = fetchStateLog;
    this.projectCache = projectCache;
    this.applyObject = applyObject;
    this.eventDispatcher = eventDispatcher;

    this.gitManager = gitManagerProvider.get();
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
        deleteRef(name, refName);
        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceLabel,
                    ReplicationState.RefFetchResult.SUCCEEDED,
                    RefUpdate.Result.FORCED));
      } catch (IllegalStateException e) {
        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceLabel,
                    RefFetchResult.NOT_ATTEMPTED,
                    Result.REJECTED_MISSING_OBJECT));
        logger.atWarning().withCause(e).log(e.getMessage());
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log(
            "Unexpected error while trying to delete ref '%s' on project %s and notifying it",
            refName, name);
        throw RestApiException.wrap(e.getMessage(), e);
      } catch (IOException e) {
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

  private RefUpdateState deleteRef(Project.NameKey name, String refName) throws IOException {

    try (Repository repository = gitManager.openRepository(name)) {

      Ref ref = repository.exactRef(refName);
      if (Objects.isNull(ref)) {
        throw new IllegalStateException(
            String.format("Unable to find ref %s in project %s", refName, name.get()));
      }

      RefUpdate.Result result;
      RefUpdate u = repository.updateRef(refName);
      u.setExpectedOldObjectId(ref.getObjectId());
      u.setNewObjectId(ObjectId.zeroId());
      u.setForceUpdate(true);

      result = u.delete();
      return new RefUpdateState(refName, result);
    }
  }
}
