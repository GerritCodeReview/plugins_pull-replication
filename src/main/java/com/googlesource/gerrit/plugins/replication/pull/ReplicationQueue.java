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
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationTasksStorage.ReplicateRefUpdate;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResponseHandler.HttpResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitReferenceUpdatedListener,
        HeadUpdatedListener {

  static final String PULL_REPLICATION_LOG_NAME = "pull_replication_log";
  static final Logger repLog = LoggerFactory.getLogger(PULL_REPLICATION_LOG_NAME);

  private final ReplicationStateListener stateLog;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private final ReplicationTasksStorage replicationTasksStorage;
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private FetchRestApiClient.Factory fetchClientFactory;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      Provider<SourcesCollection> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      ReplicationTasksStorage rts,
      FetchRestApiClient.Factory fetchClientFactory) {
    workQueue = wq;
    dispatcher = dis;
    sources = rd;
    stateLog = sl;
    replicationTasksStorage = rts;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    this.fetchClientFactory = fetchClientFactory;
  }

  @Override
  public void start() {
    if (!running) {
      sources.get().startup(workQueue);
      running = true;
      replicationTasksStorage.resetAll();
      firePendingEvents();
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
    fire(event.getProjectName(), ObjectId.fromString(event.getNewObjectId()), event.getRefName());
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    Project.NameKey project = Project.nameKey(event.getProjectName());
    String newHead = event.getNewHeadName();
    for (Source cfg : sources.get().getAll()) {
      for (String apiUrl : cfg.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchRestApiClient fetchClient = fetchClientFactory.create(cfg);
          Optional<HttpResult> result =
              executeCall(
                  () -> {
                    return fetchClient.updateHead(project, newHead, uri);
                  },
                  cfg,
                  apiUrl,
                  state);
          result.ifPresent(
              r -> {
                if (!r.isSuccessful()) {
                  stateLog.warn(
                      String.format(
                          "Pull replication rest api head update call failed. Endpoint url: %s, reason:%s",
                          apiUrl, r.getMessage()),
                      state);
                }
              });
        } catch (URISyntaxException e) {
          stateLog.warn(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
        }
      }
    }
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

    for (Source cfg : sources.get().getAll()) {
      if (cfg.wouldFetchProject(project) && cfg.wouldFetchRef(refName)) {
        for (String apiUrl : cfg.getApis()) {
          try {
            URIish uri = new URIish(apiUrl);
            ReplicateRefUpdate replicationRefUpdate =
                new ReplicateRefUpdate(
                    project.get(), refName, objectId.getName(), uri, cfg.getRemoteConfigName());
            replicationTasksStorage.create(replicationRefUpdate);
            FetchRestApiClient fetchClient = fetchClientFactory.create(cfg);
            replicationTasksStorage.start(replicationRefUpdate);

            Optional<HttpResult> result =
                executeCall(
                    () -> {
                      HttpResult r = fetchClient.callFetch(project, objectId, uri);
                      if (r.isProjectMissing(project)) {
                        fetchClient.createProject(project, uri);
                        r = fetchClient.callFetch(project, objectId, uri);
                      }
                      return r;
                    },
                    cfg,
                    apiUrl,
                    state);

            result.ifPresent(
                r -> {
                  if (!r.isSuccessful()) {
                    stateLog.warn(
                        String.format(
                            "Pull replication rest api fetch call failed. Endpoint url: %s, reason:%s",
                            apiUrl, r.getMessage().orElse("unknown")),
                        state);
                  } else {
                    replicationTasksStorage.finish(replicationRefUpdate);
                  }
                });
          } catch (URISyntaxException e) {
            stateLog.warn(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
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
  }

  private Optional<HttpResult> executeCall(
      Callable<HttpResult> fn, Source source, String apiUrl, ReplicationState state) {
    try {
      return Optional.of(executeCall(fn, 0, source.getMaxRetries()));
    } catch (ClientProtocolException e) {
      stateLog.warn(
          String.format(
              "Http protocol error during the pull replication rest api call. Endpoint url:%s, message:%s",
              apiUrl, e.getMessage()),
          state);
    } catch (IOException e) {
      stateLog.warn(
          String.format(
              "Problem with network connection during the pull replication rest api call. Endpoint url:%s, message:%s",
              apiUrl, e.getMessage()),
          state);
    } catch (Exception e) {
      stateLog.error(
          String.format("Unknown exception. Endpoint url:%s, message:%s", apiUrl, e.getMessage()),
          e,
          state);
    }
    return Optional.empty();
  }

  private HttpResult executeCall(Callable<HttpResult> fn, int attempts, int maxRetries)
      throws Exception {
    try {
      HttpResult result = fn.call();
      if (!result.shouldRetry() || !retry(attempts, maxRetries)) {
        return result;
      }
      repLog.warn(
          "Api call failed, retry attempt[{}], message:{}",
          attempts,
          result.getMessage().orElse("unknown"));
    } catch (Exception e) {
      if (!retry(attempts, maxRetries)) {
        throw e;
      }
      repLog.warn(String.format("Exception during the api call, retry attempt[%s]", attempts), e);
    }

    return executeCall(fn, ++attempts, maxRetries);
  }

  public boolean retry(int attempt, int maxRetries) {
    return maxRetries == 0 || attempt < maxRetries;
  }

  private void firePendingEvents() {
    replaying = true;
    try {
      Set<String> eventsReplayed = new HashSet<>();
      replaying = true;
      for (ReplicationTasksStorage.ReplicateRefUpdate t : replicationTasksStorage.listWaiting()) {
        String eventKey = String.format("%s:%s:%s", t.project, t.ref, t.objectId);
        if (!eventsReplayed.contains(eventKey)) {
          repLog.info("Firing pending task {}", eventKey);
          fire(t.project, ObjectId.fromString(t.objectId), t.ref);
          eventsReplayed.add(eventKey);
        }
      }
    } finally {
      replaying = false;
    }
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
}
