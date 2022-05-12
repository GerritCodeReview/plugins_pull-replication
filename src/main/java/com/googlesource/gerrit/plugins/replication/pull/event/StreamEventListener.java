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
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.restapi.project.ProjectsCollection;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchCommand;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectDeletionAction;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;

public class StreamEventListener implements EventListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private String instanceId;
  private FetchCommand fetchCommand;
  private WorkQueue workQueue;
  private ProjectInitializationAction projectInitializationAction;
  private ProjectDeletionAction projectDeletionAction;
  private ProjectsCollection projectsCollection;

  @Inject
  public StreamEventListener(
      @Nullable @GerritInstanceId String instanceId,
      FetchCommand command,
      ProjectInitializationAction projectInitializationAction,
      WorkQueue workQueue,
      ProjectDeletionAction projectDeletionAction,
      ProjectsCollection projectsCollection) {
    this.instanceId = instanceId;
    this.fetchCommand = command;
    this.projectInitializationAction = projectInitializationAction;
    this.workQueue = workQueue;
    this.projectDeletionAction = projectDeletionAction;
    this.projectsCollection = projectsCollection;

    requireNonNull(
        Strings.emptyToNull(this.instanceId), "gerrit.instanceId cannot be null or empty");
  }

  @Override
  public void onEvent(Event event) {
    if (!instanceId.equals(event.instanceId)) {
      if (event instanceof RefUpdatedEvent) {
        RefUpdatedEvent refUpdatedEvent = (RefUpdatedEvent) event;
        fetchRefsAsync(
            refUpdatedEvent.getRefName(),
            refUpdatedEvent.instanceId,
            refUpdatedEvent.getProjectNameKey());
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
      if ("project-deleted".equals(event.type) && event instanceof ProjectEvent) {
        ProjectEvent projectDeletedEvent = (ProjectEvent) event;
        deleteProject(projectDeletedEvent);
      }
    }
  }

  protected void deleteProject(ProjectEvent projectDeletedEvent) {
    try {
      ProjectResource projectResource =
          projectsCollection.parse(
              TopLevelResource.INSTANCE,
              IdString.fromDecoded(projectDeletedEvent.getProjectNameKey().get()));
      projectDeletionAction.apply(projectResource, new ProjectDeletionAction.DeleteInput());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot delete project:%s", projectDeletedEvent.getProjectNameKey().get());
    }
  }

  protected void fetchRefsAsync(String refName, String sourceInstanceId, NameKey projectNameKey) {
    FetchAction.Input input = new FetchAction.Input();
    input.refName = refName;
    input.label = sourceInstanceId;
    workQueue.getDefaultQueue().submit(new FetchJob(fetchCommand, projectNameKey, input));
  }

  private String getProjectRepositoryName(ProjectCreatedEvent projectCreatedEvent) {
    return String.format("%s.git", projectCreatedEvent.projectName);
  }
}
