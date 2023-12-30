// Copyright (C) 2023 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.Context;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState;
import java.util.Optional;
import javax.inject.Inject;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;

public class UpdateHeadCommand {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final URIish EMPTY_URI = new URIish();

  private final GerritConfigOps gerritConfigOps;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  @Inject
  UpdateHeadCommand(GerritConfigOps gerritConfigOps, DynamicItem<EventDispatcher> eventDispatcher) {
    this.gerritConfigOps = gerritConfigOps;
    this.eventDispatcher = eventDispatcher;
  }

  public void doUpdate(Project.NameKey project, String ref)
      throws UnprocessableEntityException, ResourceNotFoundException {
    boolean succeeded = false;
    // TODO: the .git suffix should not be added here, but rather it should be
    //  dealt with by the caller, honouring the naming style from the
    //  replication.config (Issue 15221)
    Optional<URIish> maybeRepo =
        gerritConfigOps.getGitRepositoryURI(String.format("%s.git", project.get()));

    logger.atInfo().log("do update: %s %s", project, ref);
    try {
      if (maybeRepo.isPresent()) {
        if (new LocalFS(maybeRepo.get()).updateHead(project, ref)) {
          succeeded = true;
          return;
        }

        throw new UnprocessableEntityException(
            String.format("Could not update HEAD of repo %s to ref %s", project, ref));
      }
    } finally {
      fireEvent(project, succeeded);
    }
    throw new ResourceNotFoundException(
        String.format("Could not compute URL for repo: %s", project.get()));
  }

  private void fireEvent(Project.NameKey projectName, boolean succeeded) {
    try {
      Context.setLocalEvent(true);
      eventDispatcher
          .get()
          .postEvent(
              new FetchRefReplicatedEvent(
                  projectName.get(),
                  RefNames.HEAD,
                  EMPTY_URI, // TODO: the remote label is not passed as parameter, hence cannot be
                  // propagated to the event
                  succeeded
                      ? ReplicationState.RefFetchResult.SUCCEEDED
                      : ReplicationState.RefFetchResult.FAILED,
                  RefUpdate.Result.FORCED));
    } catch (PermissionBackendException e) {
      logger.atSevere().withCause(e).log(
          "Cannot post event for refs/meta/config on project %s", projectName);
    } finally {
      Context.unsetLocalEvent();
    }
  }
}
