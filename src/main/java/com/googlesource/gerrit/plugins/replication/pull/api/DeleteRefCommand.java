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
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.DeleteRefException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;

public class DeleteRefCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PullReplicationStateLogger fetchStateLog;
  private final DynamicItem<EventDispatcher> eventDispatcher;
  private final ProjectCache projectCache;
  private final SourcesCollection sourcesCollection;
  private final GitRepositoryManager gitManager;

  @Inject
  public DeleteRefCommand(
      PullReplicationStateLogger fetchStateLog,
      ProjectCache projectCache,
      SourcesCollection sourcesCollection,
      DynamicItem<EventDispatcher> eventDispatcher,
      LocalGitRepositoryManagerProvider gitManagerProvider) {
    this.fetchStateLog = fetchStateLog;
    this.projectCache = projectCache;
    this.eventDispatcher = eventDispatcher;
    this.sourcesCollection = sourcesCollection;
    this.gitManager = gitManagerProvider.get();
  }

  public void deleteRefsSync(
      Project.NameKey name, Set<String> deletedRefNames, String sourceLabel) {
    deletedRefNames.forEach(
        r -> {
          try {
            deleteRef(name, r, sourceLabel);
          } catch (RestApiException | IOException e) {
            repLog.error("Could not delete ref {}:{} from source {}", name.get(), r, sourceLabel);
          }
        });
  }

  public void deleteRef(Project.NameKey name, String refName, String sourceLabel)
      throws IOException, RestApiException {
    Source source =
        sourcesCollection
            .getByRemoteName(sourceLabel)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        String.format("Could not find URI for %s remote", sourceLabel)));
    if (!source.isMirror()) {
      repLog.info(
          "Ignoring ref {} deletion from project {}, as mirror option is false", refName, name);
      return;
    }
    try {
      repLog.info("Delete ref from {} for project {}, ref name {}", sourceLabel, name, refName);
      Optional<ProjectState> projectState = projectCache.get(name);
      if (!projectState.isPresent()) {
        throw new ResourceNotFoundException(String.format("Project %s was not found", name));
      }

      Optional<Ref> ref = getRef(name, refName);
      if (!ref.isPresent()) {
        logger.atFine().log("Ref %s was not found in project %s", refName, name);
        return;
      }

      URIish sourceUri = source.getURI(name);

      try {

        Context.setLocalEvent(true);
        RefUpdate.Result successResult = ensureSuccess(deleteRef(name, ref.get()));

        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceUri,
                    ReplicationState.RefFetchResult.SUCCEEDED,
                    successResult));
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log(
            "Unexpected error while trying to delete ref '%s' on project %s and notifying it",
            refName, name);
        throw RestApiException.wrap(e.getMessage(), e);
      } catch (IOException e) {
        RefUpdate.Result refUpdateResult =
            e instanceof DeleteRefException
                ? ((DeleteRefException) e).getResult()
                : RefUpdate.Result.LOCK_FAILURE;
        eventDispatcher
            .get()
            .postEvent(
                new FetchRefReplicatedEvent(
                    name.get(),
                    refName,
                    sourceUri,
                    ReplicationState.RefFetchResult.FAILED,
                    refUpdateResult));
        String message =
            String.format(
                "RefUpdate lock failure for: sourceLabel=%s, project=%s, refName=%s",
                sourceLabel, name, refName);
        logger.atSevere().withCause(e).log("%s", message);
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

  private static RefUpdate.Result ensureSuccess(RefUpdateState refUpdateState)
      throws DeleteRefException {
    switch (refUpdateState.getResult()) {
      case NOT_ATTEMPTED:
      case REJECTED:
      case REJECTED_CURRENT_BRANCH:
      case REJECTED_MISSING_OBJECT:
      case LOCK_FAILURE:
      case IO_FAILURE:
      case REJECTED_OTHER_REASON:
        throw new DeleteRefException("Failed ref deletion", refUpdateState.getResult());
    }
    return refUpdateState.getResult();
  }
}
