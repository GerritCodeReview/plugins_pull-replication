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

import com.google.common.collect.Lists;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
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
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
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

  public List<RefUpdateState> deleteRef(NameKey name, String refName, String sourceLabel)
      throws IOException, ResourceNotFoundException, PermissionBackendException {
    repLog.info("Delete ref from {} for project {}, ref name {}", sourceLabel, name, refName);
    Optional<ProjectState> projectState = projectCache.get(name);
    if (!projectState.isPresent()) {
      throw new ResourceNotFoundException(String.format("Project %s was not found", name));
    }

    Optional<Ref> ref = getRef(name, refName);
    if (!ref.isPresent()) {
      logger.atFine().log("Ref %s was not found in project %s", refName, name);
      return Collections.emptyList();
    }

    try {

      Context.setLocalEvent(true);
      RefUpdateState result = deleteRef(name, ref.get());

      eventDispatcher
          .get()
          .postEvent(
              new FetchRefReplicatedEvent(
                  name.get(),
                  refName,
                  sourceLabel,
                  ReplicationState.RefFetchResult.SUCCEEDED,
                  RefUpdate.Result.FORCED));
      repLog.info(
          "Delete ref from {} for project {}, ref name {} completed", sourceLabel, name, refName);
      return Lists.newArrayList(result);
    } catch (PermissionBackendException e) {
      logger.atSevere().withCause(e).log(
          "Unexpected error while trying to delete ref '%s' on project %s and notifying it",
          refName, name);
      throw e;
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
  }

  private Optional<Ref> getRef(Project.NameKey repo, String refName) throws IOException {
    try (Repository repository = gitManager.openRepository(repo)) {
      Ref ref = repository.exactRef(refName);
      return Optional.ofNullable(ref);
    }
  }

  private RefUpdateState deleteRef(Project.NameKey name, Ref ref) throws IOException {
    try (Repository repository = gitManager.openRepository(name)) {

      RefUpdate.Result result;
      RefUpdate u = repository.updateRef(ref.getName());
      u.setExpectedOldObjectId(ref.getObjectId());
      u.setNewObjectId(ObjectId.zeroId());
      u.setForceUpdate(true);

      result = u.delete();
      return new RefUpdateState(ref.getName(), result);
    }
  }
}
