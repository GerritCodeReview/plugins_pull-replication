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
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicationQueue
    implements ObservableQueue, LifecycleListener, GitReferenceUpdatedListener {

  static final String PULL_REPLICATION_LOG_NAME = "pull_replication_log";
  static final Logger repLog = LoggerFactory.getLogger(PULL_REPLICATION_LOG_NAME);

  private static final Integer DEFAULT_FETCH_CALLS_TIMEOUT = 0;
  private final ReplicationStateListener stateLog;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private FetchRestApiClient.Factory fetchClientFactory;
  private Integer fetchCallsTimeout;
  private RefsFilter refsFilter;
  private RevisionReader revisionReader;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      Provider<SourcesCollection> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      FetchRestApiClient.Factory fetchClientFactory,
      RefsFilter refsFilter,
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
      fetchCallsTimeout =
          2
              * sources.get().getAll().stream()
                  .mapToInt(Source::getConnectionTimeout)
                  .max()
                  .orElse(DEFAULT_FETCH_CALLS_TIMEOUT);

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
      fire(event.getProjectName(), ObjectId.fromString(event.getNewObjectId()), event.getRefName());
    }
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(String projectName, ObjectId objectId, String refName) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), objectId, refName, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(
      Project.NameKey project, ObjectId objectId, String refName, ReplicationState state) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(ReferenceUpdatedEvent.create(project.get(), refName, objectId));
      return;
    }
    ForkJoinPool fetchCallsPool = null;
    try {
      fetchCallsPool = new ForkJoinPool(sources.get().getAll().size());

      final Consumer<Source> callFunction = callFunction(project, refName, state);
      fetchCallsPool
          .submit(() -> sources.get().getAll().parallelStream().forEach(callFunction))
          .get(fetchCallsTimeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      stateLog.error(
          String.format(
              "Exception during the pull replication fetch rest api call.  Message:%s",
              e.getMessage()),
          e,
          state);
    } finally {
      if (fetchCallsPool != null) {
        fetchCallsPool.shutdown();
      }
    }
  }

  private Consumer<Source> callFunction(NameKey project, String refName, ReplicationState state) {
    CallFunction call = getCallFunction(project, refName, state);

    return (source) -> {
      try {
        call.call(source);
      } catch (MissingParentObjectException e) {
        callFetch(source, project, refName, state);
      }
    };
  }

  private CallFunction getCallFunction(NameKey project, String refName, ReplicationState state) {
    try {
      Optional<RevisionData> revisionData = revisionReader.read(project, refName);
      if (revisionData.isPresent()) {
        return ((source) -> callSendObject(source, project, refName, revisionData.get(), state));
      }
    } catch (IOException | RefUpdateException e) {
      stateLog.error(
          String.format(
              "Exception during reading ref: %s, project:%s, message: %s",
              refName, project.get(), e.getMessage()),
          e,
          state);
    }

    return (source) -> callFetch(source, project, refName, state);
  }

  private void callSendObject(
      Source source,
      Project.NameKey project,
      String refName,
      RevisionData revision,
      ReplicationState state)
      throws MissingParentObjectException {
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchRestApiClient fetchClient = fetchClientFactory.create(source);

          HttpResult result = fetchClient.callSendObject(project, refName, revision, uri);
          if (!result.isSuccessful()) {
            repLog.warn(
                String.format(
                    "Pull replication rest api apply object call failed. Endpoint url: %s, reason:%s",
                    apiUrl, result.getMessage().orElse("unknown")));
            if (result.isParentObjectMissing()) {
              throw new MissingParentObjectException(
                  project, refName, source.getRemoteConfigName());
            }
          }
        } catch (URISyntaxException e) {
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
        } catch (IOException e) {
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s, message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
        }
      }
    }
  }

  private void callFetch(
      Source source, Project.NameKey project, String refName, ReplicationState state) {
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchRestApiClient fetchClient = fetchClientFactory.create(source);

          HttpResult result = fetchClient.callFetch(project, refName, uri);
          if (!result.isSuccessful()) {
            stateLog.warn(
                String.format(
                    "Pull replication rest api fetch call failed. Endpoint url: %s, reason:%s",
                    apiUrl, result.getMessage().orElse("unknown")),
                state);
          }
        } catch (URISyntaxException e) {
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
        } catch (Exception e) {
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s, message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
        }
      }
    }
  }

  public boolean retry(int attempt, int maxRetries) {
    return maxRetries == 0 || attempt < maxRetries;
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        fire(event.projectName(), event.objectId(), event.refName());
        eventsReplayed.add(eventKey);
      }
    }
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(String projectName, String refName, ObjectId objectId) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(projectName, refName, objectId);
    }

    public abstract String projectName();

    public abstract String refName();

    public abstract ObjectId objectId();
  }

  @FunctionalInterface
  private interface CallFunction {
    void call(Source source) throws MissingParentObjectException;
  }
}
