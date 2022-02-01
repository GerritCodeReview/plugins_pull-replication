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

import com.google.auto.value.AutoValue;
import com.google.common.collect.Queues;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitReferenceUpdatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {

  static final String PULL_REPLICATION_LOG_NAME = "pull_replication_log";
  static final Logger repLog = LoggerFactory.getLogger(PULL_REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private FetchApiClient.Factory fetchClientFactory;
  private ExcludedRefsFilter refsFilter;
  private RevisionReader revisionReader;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      Provider<SourcesCollection> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      FetchApiClient.Factory fetchClientFactory,
      ExcludedRefsFilter refsFilter,
      RevisionReader revReader) {
    workQueue = wq;
    dispatcher = dis;
    sources = rd;
    stateLog = sl;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    this.fetchClientFactory = fetchClientFactory;
    this.refsFilter = refsFilter;
    this.revisionReader = revReader;
  }

  @Override
  public void start() {
    if (!running) {
      sources.get().startup(workQueue);
      running = true;
      fireBeforeStartupEvents();
    }
  }

  @Override
  public void stop() {
    running = false;
    int discarded = sources.get().shutdown();
    if (discarded > 0) {
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isReplaying() {
    return replaying;
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    if (isRefToBeReplicated(event.getRefName())) {
      repLog.info(
          "Ref event received: {} on project {}:{} - {} => {}",
          refUpdateType(event),
          event.getProjectName(),
          event.getRefName(),
          event.getOldObjectId(),
          event.getNewObjectId());
      fire(
          event.getProjectName(),
          ObjectId.fromString(event.getNewObjectId()),
          event.getRefName(),
          event.isDelete());
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey project = Project.nameKey(event.getProjectName());
    FetchApiClient fetchClient = fetchClientFactory.create(sources.get());
    fetchClient.deleteProject(project);
  }

  private static String refUpdateType(GitReferenceUpdatedListener.Event event) {
    String forcedPrefix = event.isNonFastForward() ? "FORCED " : " ";
    if (event.isCreate()) {
      return forcedPrefix + "CREATE";
    } else if (event.isDelete()) {
      return forcedPrefix + "DELETE";
    } else {
      return forcedPrefix + "UPDATE";
    }
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(String projectName, ObjectId objectId, String refName, boolean isDelete) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), objectId, refName, isDelete, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(
      Project.NameKey project,
      ObjectId objectId,
      String refName,
      boolean isDelete,
      ReplicationState state) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(
          ReferenceUpdatedEvent.create(project.get(), refName, objectId, isDelete));
      return;
    }
    FetchApiClient fetchClient = fetchClientFactory.create(sources.get());
    if (isDelete) {
      fetchClient.callSendObject(project, refName, isDelete, null);
      return;
    }

    try {
      Optional<RevisionData> revisionData = revisionReader.read(project, objectId, refName);
      if (revisionData.isPresent()) {
        fetchClient.callSendObject(project, refName, isDelete, revisionData.get());
        return;
      }
    } catch (InvalidObjectIdException | IOException e) {
      stateLog.error(
          String.format(
              "Exception during reading ref: %s, project:%s, message: %s",
              refName, project.get(), e.getMessage()),
          e,
          state);
    }

    fetchClient.callFetch(project, refName);
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        fire(event.projectName(), event.objectId(), event.refName(), event.isDelete());
        eventsReplayed.add(eventKey);
      }
    }
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());

    FetchApiClient fetchClient = fetchClientFactory.create(sources.get());
    fetchClient.updateHead(p, event.getNewHeadName());
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(
        String projectName, String refName, ObjectId objectId, boolean isDelete) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(
          projectName, refName, objectId, isDelete);
    }

    public abstract String projectName();

    public abstract String refName();

    public abstract ObjectId objectId();

    public abstract boolean isDelete();
  }
}
