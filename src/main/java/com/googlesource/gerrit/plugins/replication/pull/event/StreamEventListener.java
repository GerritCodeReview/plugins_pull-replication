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

package com.googlesource.gerrit.plugins.replication.pull.event;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.api.DeleteRefCommand;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;

public class StreamEventListener implements EventListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private String instanceId;
  private WorkQueue workQueue;
  private ProjectInitializationAction projectInitializationAction;
  private DeleteRefCommand deleteCommand;

  private Factory fetchJobFactory;

  @Inject
  public StreamEventListener(
      @Nullable @GerritInstanceId String instanceId,
      DeleteRefCommand deleteCommand,
      ProjectInitializationAction projectInitializationAction,
      WorkQueue workQueue,
      FetchJob.Factory fetchJobFactory) {
    this.instanceId = instanceId;
    this.deleteCommand = deleteCommand;
    this.projectInitializationAction = projectInitializationAction;
    this.workQueue = workQueue;
    this.fetchJobFactory = fetchJobFactory;

    requireNonNull(
        Strings.emptyToNull(this.instanceId), "gerrit.instanceId cannot be null or empty");
  }

  @Override
  public void onEvent(Event event) {
    if (!instanceId.equals(event.instanceId)) {
      if (event instanceof RefUpdatedEvent) {
        RefUpdatedEvent refUpdatedEvent = (RefUpdatedEvent) event;
        if (!isProjectDelete(refUpdatedEvent)) {
          if (isRefDelete(refUpdatedEvent)) {
            try {
              deleteCommand.deleteRef(
                  refUpdatedEvent.getProjectNameKey(),
                  refUpdatedEvent.getRefName(),
                  refUpdatedEvent.instanceId);
            } catch (IOException | RestApiException e) {
              logger.atSevere().withCause(e).log(
                  "Cannot delete ref %s project:%s",
                  refUpdatedEvent.getRefName(), refUpdatedEvent.getProjectNameKey());
            }
          } else {
            fetchRefsAsync(
                refUpdatedEvent.getRefName(),
                refUpdatedEvent.instanceId,
                refUpdatedEvent.getProjectNameKey());
          }
        }
      }

      if (event instanceof ProjectCreatedEvent) {
        ProjectCreatedEvent projectCreatedEvent = (ProjectCreatedEvent) event;
        try {
          projectInitializationAction.initProject(getProjectRepositoryName(projectCreatedEvent));
          fetchRefsAsync(
              FetchOne.ALL_REFS,
              projectCreatedEvent.instanceId,
              projectCreatedEvent.getProjectNameKey());
        } catch (AuthException | PermissionBackendException e) {
          logger.atSevere().withCause(e).log(
              "Cannot initialise project:%s", projectCreatedEvent.projectName);
        }
      }
    }
  }

  private boolean isRefDelete(RefUpdatedEvent event) {
    return !Strings.isNullOrEmpty(event.refUpdate.get().newRev)
        && ObjectId.zeroId().equals(ObjectId.fromString(event.refUpdate.get().newRev));
  }

  private boolean isProjectDelete(RefUpdatedEvent event) {
    return RefNames.isConfigRef(event.getRefName()) && isRefDelete(event);
  }

  protected void fetchRefsAsync(String refName, String sourceInstanceId, NameKey projectNameKey) {
    FetchAction.Input input = new FetchAction.Input();
    input.refName = refName;
    input.label = sourceInstanceId;
    workQueue.getDefaultQueue().submit(fetchJobFactory.create(projectNameKey, input));
  }

  private String getProjectRepositoryName(ProjectCreatedEvent projectCreatedEvent) {
    return String.format("%s.git", projectCreatedEvent.projectName);
  }
}
