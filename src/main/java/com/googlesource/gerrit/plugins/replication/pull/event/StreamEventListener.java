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

import static com.googlesource.gerrit.plugins.replication.pull.ApplyObjectCacheModule.APPLY_OBJECTS_CACHE;
import static com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.BatchInput.fromInput;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.ProjectCreatedEvent;
import com.google.gerrit.server.events.ProjectEvent;
import com.google.gerrit.server.events.ProjectHeadUpdatedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.restapi.project.ProjectsCollection;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.googlesource.gerrit.plugins.deleteproject.ProjectDeletedEvent;
import com.googlesource.gerrit.plugins.replication.pull.ApplyObjectsCacheKey;
import com.googlesource.gerrit.plugins.replication.pull.FetchOne;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchJob.Factory;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectDeletionAction;
import com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.api.UpdateHeadCommand;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;

public class StreamEventListener implements EventListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String ZERO_ID_NAME = ObjectId.zeroId().name();

  private final ExcludedRefsFilter refsFilter;
  private final Factory fetchJobFactory;
  private final UpdateHeadCommand updateHeadCommand;
  private final ProjectInitializationAction projectInitializationAction;
  private final Provider<PullReplicationApiRequestMetrics> metricsProvider;
  private final SourcesCollection sources;
  private final String instanceId;
  private final WorkQueue workQueue;
  private final Cache<ApplyObjectsCacheKey, Long> refUpdatesSucceededCache;
  private final ProjectDeletionAction projectDeletionAction;
  private final ProjectsCollection projectsCollection;

  @Inject
  public StreamEventListener(
      @Nullable @GerritInstanceId String instanceId,
      UpdateHeadCommand updateHeadCommand,
      ProjectInitializationAction projectInitializationAction,
      WorkQueue workQueue,
      FetchJob.Factory fetchJobFactory,
      Provider<PullReplicationApiRequestMetrics> metricsProvider,
      SourcesCollection sources,
      ExcludedRefsFilter excludedRefsFilter,
      @Named(APPLY_OBJECTS_CACHE) Cache<ApplyObjectsCacheKey, Long> refUpdatesSucceededCache,
      ProjectDeletionAction projectDeletionAction,
      ProjectsCollection projectsCollection) {
    this.instanceId = instanceId;
    this.updateHeadCommand = updateHeadCommand;
    this.projectInitializationAction = projectInitializationAction;
    this.workQueue = workQueue;
    this.fetchJobFactory = fetchJobFactory;
    this.metricsProvider = metricsProvider;
    this.sources = sources;
    this.refsFilter = excludedRefsFilter;
    this.refUpdatesSucceededCache = refUpdatesSucceededCache;
    this.projectDeletionAction = projectDeletionAction;
    this.projectsCollection = projectsCollection;

    requireNonNull(
        Strings.emptyToNull(this.instanceId), "gerrit.instanceId cannot be null or empty");
  }

  @Override
  public void onEvent(Event event) {
    try {
      fetchRefsForEvent(event);
    } catch (AuthException
        | PermissionBackendException
        | IOException
        | UnprocessableEntityException
        | ResourceNotFoundException e) {
      logger.atSevere().withCause(e).log(
          "This is the event handler of Gerrit's event-bus. It isn't"
              + "supposed to throw any exception, otherwise the other handlers "
              + "won't be executed");
    }
  }

  public void fetchRefsForEvent(Event event)
      throws AuthException,
          PermissionBackendException,
          IOException,
          UnprocessableEntityException,
          ResourceNotFoundException {
    if (instanceId.equals(event.instanceId) || !shouldReplicateProject(event)) {
      return;
    }

    PullReplicationApiRequestMetrics metrics = metricsProvider.get();
    metrics.start(event);
    if (event instanceof RefUpdatedEvent) {
      RefUpdatedEvent refUpdatedEvent = (RefUpdatedEvent) event;
      if (!isRefToBeReplicated(refUpdatedEvent.getRefName())) {
        logger.atFine().log(
            "Skipping excluded ref '%s' for project '%s'",
            refUpdatedEvent.getRefName(), refUpdatedEvent.getProjectNameKey());
        return;
      }

      if (isApplyObjectsCacheHit(refUpdatedEvent)) {
        logger.atFine().log(
            "Skipping refupdate '%s'  '%s'=>'%s' (eventCreatedOn=%d) for project '%s' because has"
                + " been already replicated via apply-object",
            refUpdatedEvent.getRefName(),
            refUpdatedEvent.refUpdate.get().oldRev,
            refUpdatedEvent.refUpdate.get().newRev,
            refUpdatedEvent.eventCreatedOn,
            refUpdatedEvent.getProjectNameKey());
        return;
      }

      fetchRefsAsync(
          refUpdatedEvent.getRefName(),
          refUpdatedEvent.instanceId,
          refUpdatedEvent.getProjectNameKey(),
          isRefDelete(refUpdatedEvent),
          metrics);
    } else if (event instanceof ProjectCreatedEvent) {
      ProjectCreatedEvent projectCreatedEvent = (ProjectCreatedEvent) event;
      try {
        projectInitializationAction.initProject(
            getProjectRepositoryName(projectCreatedEvent), projectCreatedEvent.headName);
        fetchRefsAsync(
            FetchOne.ALL_REFS,
            projectCreatedEvent.instanceId,
            projectCreatedEvent.getProjectNameKey(),
            false,
            metrics);
      } catch (AuthException | PermissionBackendException | IOException e) {
        logger.atSevere().withCause(e).log(
            "Cannot initialise project:%s", projectCreatedEvent.projectName);
        throw e;
      }
    } else if (event instanceof ProjectHeadUpdatedEvent) {
      ProjectHeadUpdatedEvent headUpdatedEvent = (ProjectHeadUpdatedEvent) event;
      try {
        updateHeadCommand.doUpdate(headUpdatedEvent.getProjectNameKey(), headUpdatedEvent.newHead);
      } catch (UnprocessableEntityException | ResourceNotFoundException e) {
        logger.atSevere().withCause(e).log(
            "Failed to update HEAD on project: %s", headUpdatedEvent.projectName);
        throw e;
      }
    } else if (event instanceof ProjectDeletedEvent) {
      deleteProject((ProjectDeletedEvent) event);
    }
  }

  protected void deleteProject(ProjectEvent projectDeletedEvent) {
    try {
      ProjectResource projectResource =
          projectsCollection.parse(
              TopLevelResource.INSTANCE,
              IdString.fromDecoded(projectDeletedEvent.getProjectNameKey().get()));
      projectDeletionAction.apply(projectResource, new ProjectDeletionAction.DeleteInput());
    } catch (ResourceNotFoundException e) {
      logger.atFine().withCause(e).log(
          "Repository not found whilst trying to delete project:%s",
          projectDeletedEvent.getProjectNameKey().get());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Cannot delete project:%s", projectDeletedEvent.getProjectNameKey().get());
    }
  }

  private boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private boolean shouldReplicateProject(Event event) {
    if (!isInterestingEventType(event)) {
      return false;
    }

    Optional<Source> maybeSource =
        sources.getAll().stream()
            .filter(s -> s.getRemoteConfigName().equals(event.instanceId))
            .findFirst();

    if (!maybeSource.isPresent()) {
      return false;
    }

    Source source = maybeSource.get();
    if (event instanceof ProjectCreatedEvent) {
      ProjectCreatedEvent projectCreatedEvent = (ProjectCreatedEvent) event;

      return source.isCreateMissingRepositories()
          && source.wouldCreateProject(projectCreatedEvent.getProjectNameKey());
    }

    if (event instanceof ProjectDeletedEvent) {
      ProjectDeletedEvent projectDeletedEvent = (ProjectDeletedEvent) event;

      return source.wouldDeleteProject(projectDeletedEvent.getProjectNameKey());
    }

    ProjectEvent projectEvent = (ProjectEvent) event;
    return source.wouldFetchProject(projectEvent.getProjectNameKey());
  }

  private static boolean isInterestingEventType(Event event) {
    return event instanceof ProjectDeletedEvent
        || event instanceof ProjectCreatedEvent
        || event instanceof RefUpdatedEvent
        || event instanceof ProjectHeadUpdatedEvent;
  }

  private boolean isRefDelete(RefUpdatedEvent event) {
    return ZERO_ID_NAME.equals(event.refUpdate.get().newRev);
  }

  private boolean isProjectDelete(RefUpdatedEvent event) {
    return RefNames.isConfigRef(event.getRefName()) && isRefDelete(event);
  }

  protected void fetchRefsAsync(
      String refName,
      String sourceInstanceId,
      NameKey projectNameKey,
      boolean isDelete,
      PullReplicationApiRequestMetrics metrics) {
    FetchAction.Input input = new FetchAction.Input();
    input.refName = refName;
    input.label = sourceInstanceId;
    input.isDelete = isDelete;
    workQueue
        .getDefaultQueue()
        .submit(fetchJobFactory.create(projectNameKey, fromInput(input), metrics));
  }

  private String getProjectRepositoryName(ProjectCreatedEvent projectCreatedEvent) {
    return String.format("%s.git", projectCreatedEvent.projectName);
  }

  private boolean isApplyObjectsCacheHit(RefUpdatedEvent refUpdateEvent) {
    RefUpdateAttribute refUpdateAttribute = refUpdateEvent.refUpdate.get();
    Long refUpdateSuccededTimestamp =
        refUpdatesSucceededCache.getIfPresent(
            ApplyObjectsCacheKey.create(
                refUpdateAttribute.newRev, refUpdateAttribute.refName, refUpdateAttribute.project));

    return refUpdateSuccededTimestamp != null
        && refUpdateEvent.eventCreatedOn <= refUpdateSuccededTimestamp;
  }
}
