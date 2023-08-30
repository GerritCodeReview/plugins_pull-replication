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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Queues;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1.Context;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.filter.ApplyObjectsRefsFilter;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ReplicationQueue
    implements ObservableQueue,
        EventListener,
        LifecycleListener,
        ProjectDeletedListener,
        HeadUpdatedListener {

  static final String PULL_REPLICATION_LOG_NAME = "pull_replication_log";
  static final Logger repLog = LoggerFactory.getLogger(PULL_REPLICATION_LOG_NAME);

  private static final Integer DEFAULT_FETCH_CALLS_TIMEOUT = 0;
  private static final String REF_UDPATED_EVENT_TYPE = new RefUpdatedEvent().type;
  private static final String ZEROS_OBJECTID = ObjectId.zeroId().getName();
  private final ReplicationStateListener stateLog;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private FetchApiClient.Factory fetchClientFactory;
  private Integer fetchCallsTimeout;
  private ExcludedRefsFilter refsFilter;
  private Provider<RevisionReader> revReaderProvider;
  private final ApplyObjectMetrics applyObjectMetrics;
  private final FetchReplicationMetrics fetchMetrics;
  private final ReplicationQueueMetrics queueMetrics;
  private final String instanceId;
  private ApplyObjectsRefsFilter applyObjectsRefsFilter;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      Provider<SourcesCollection> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      FetchApiClient.Factory fetchClientFactory,
      ExcludedRefsFilter refsFilter,
      Provider<RevisionReader> revReaderProvider,
      ApplyObjectMetrics applyObjectMetrics,
      FetchReplicationMetrics fetchMetrics,
      ReplicationQueueMetrics queueMetrics,
      @GerritInstanceId String instanceId,
      ApplyObjectsRefsFilter applyObjectsRefsFilter) {
    workQueue = wq;
    dispatcher = dis;
    sources = rd;
    stateLog = sl;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    this.fetchClientFactory = fetchClientFactory;
    this.refsFilter = refsFilter;
    this.revReaderProvider = revReaderProvider;
    this.applyObjectMetrics = applyObjectMetrics;
    this.fetchMetrics = fetchMetrics;
    this.queueMetrics = queueMetrics;
    this.instanceId = instanceId;
    this.applyObjectsRefsFilter = applyObjectsRefsFilter;
    queueMetrics.initCallbackMetrics(this);
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
  public void onEvent(com.google.gerrit.server.events.Event e) {
    if (e.type.equals(REF_UDPATED_EVENT_TYPE) && instanceId.equals(e.instanceId)) {
      RefUpdatedEvent event = (RefUpdatedEvent) e;

      if (isRefToBeReplicated(event.getRefName())) {
        repLog.info(
            "Ref event received: {} on project {}:{} - {} => {}",
            refUpdateType(event),
            event.refUpdate.get().project,
            event.getRefName(),
            event.refUpdate.get().oldRev,
            event.refUpdate.get().newRev);
        fire(
            event.refUpdate.get().project,
            ObjectId.fromString(event.refUpdate.get().newRev),
            event.getRefName(),
            event.eventCreatedOn,
            ZEROS_OBJECTID.equals(event.refUpdate.get().newRev));
      }
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey project = Project.nameKey(event.getProjectName());
    sources.get().getAll().stream()
        .filter((Source s) -> s.wouldDeleteProject(project))
        .forEach(
            source ->
                source.getApis().forEach(apiUrl -> source.scheduleDeleteProject(apiUrl, project)));
  }

  private static String refUpdateType(RefUpdatedEvent event) {
    if (ZEROS_OBJECTID.equals(event.refUpdate.get().oldRev)) {
      return "CREATE";
    } else if (ZEROS_OBJECTID.equals(event.refUpdate.get().newRev)) {
      return "DELETE";
    } else {
      return "UPDATE";
    }
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(
      String projectName,
      ObjectId objectId,
      String refName,
      long eventCreatedOn,
      boolean isDelete) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), objectId, refName, eventCreatedOn, isDelete, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(
      NameKey project,
      ObjectId objectId,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      ReplicationState state) {

    queueMetrics.incrementEventFired();

    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(
          ReferenceUpdatedEvent.create(project.get(), refName, objectId, eventCreatedOn, isDelete));

      queueMetrics.incrementQueuedBeforStartup();
      return;
    }
    ForkJoinPool fetchCallsPool = null;
    try {
      fetchCallsPool = new ForkJoinPool(sources.get().getAll().size());

      final Consumer<Source> callFunction =
          callFunction(project, objectId, refName, eventCreatedOn, isDelete, state);
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

  private Consumer<Source> callFunction(
      NameKey project,
      ObjectId objectId,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      ReplicationState state) {
    CallFunction call =
        getCallFunction(project, objectId, refName, eventCreatedOn, isDelete, state);

    return (source) -> {
      boolean callSuccessful;
      try {
        callSuccessful = call.call(source);
      } catch (Exception e) {
        repLog.warn(
            String.format(
                "Failed to apply object %s on project %s:%s, falling back to git fetch",
                objectId.name(), project, refName),
            e);
        callSuccessful = false;
      }

      if (!callSuccessful) {
        callFetch(source, project, refName, state);
      }
    };
  }

  private CallFunction getCallFunction(
      NameKey project,
      ObjectId objectId,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      ReplicationState state) {
    if (isDelete) {
      return ((source) ->
          callSendObject(source, project, refName, eventCreatedOn, isDelete, null, state));
    }

    try {
      Optional<RevisionData> revisionData =
          revReaderProvider.get().read(project, objectId, refName, 0);
      repLog.info(
          "RevisionData is {} for {}:{}",
          revisionData.map(RevisionData::toString).orElse("ABSENT"),
          project,
          refName);

      if (revisionData.isPresent()) {
        return ((source) ->
            callSendObject(
                source,
                project,
                refName,
                eventCreatedOn,
                isDelete,
                Arrays.asList(revisionData.get()),
                state));
      }
    } catch (InvalidObjectIdException | IOException e) {
      stateLog.error(
          String.format(
              "Exception during reading ref: %s, project:%s, message: %s",
              refName, project.get(), e.getMessage()),
          e,
          state);
    }

    return (source) -> callFetch(source, project, refName, state);
  }

  private boolean callSendObject(
      Source source,
      NameKey project,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      List<RevisionData> revision,
      ReplicationState state)
      throws MissingParentObjectException {
    boolean resultIsSuccessful = true;
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchApiClient fetchClient = fetchClientFactory.create(source);
          repLog.info(
              "Pull replication REST API apply object to {} for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision);
          Context<String> apiTimer = applyObjectMetrics.startEnd2End(source.getRemoteConfigName());
          HttpResult result =
              isDelete
                  ? fetchClient.callSendObject(
                      project, refName, eventCreatedOn, isDelete, null, uri)
                  : fetchClient.callSendObjects(project, refName, eventCreatedOn, revision, uri);
          boolean resultSuccessful = result.isSuccessful();
          repLog.info(
              "Pull replication REST API apply object to {} COMPLETED for {}:{} - {}, HTTP Result:"
                  + " {} - time:{} ms",
              apiUrl,
              project,
              refName,
              revision,
              result,
              apiTimer.stop() / 1000000.0);

          if (!resultSuccessful
              && result.isProjectMissing(project)
              && source.isCreateMissingRepositories()) {
            result = initProject(project, uri, fetchClient, result);
            repLog.info("Missing project {} created, HTTP Result:{}", project, result);
          }

          if (!resultSuccessful) {
            if (result.isParentObjectMissing()) {

              if ((RefNames.isNoteDbMetaRef(refName) || applyObjectsRefsFilter.match(refName))
                  && revision.size() == 1) {
                List<RevisionData> allRevisions =
                    fetchWholeMetaHistory(project, refName, revision.get(0));
                repLog.info(
                    "Pull replication REST API apply object to {} for {}:{} - {}",
                    apiUrl,
                    project,
                    refName,
                    allRevisions);
                return callSendObject(
                    source, project, refName, eventCreatedOn, isDelete, allRevisions, state);
              }

              throw new MissingParentObjectException(
                  project, refName, source.getRemoteConfigName());
            }
          }

          resultIsSuccessful &= resultSuccessful;
        } catch (URISyntaxException e) {
          repLog.warn(
              "Pull replication REST API apply object to {} *FAILED* for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision,
              e);
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
          resultIsSuccessful = false;
        } catch (IOException e) {
          repLog.warn(
              "Pull replication REST API apply object to {} *FAILED* for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision,
              e);
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s,"
                      + " message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
          resultIsSuccessful = false;
        }
      }
    }

    return resultIsSuccessful;
  }

  private List<RevisionData> fetchWholeMetaHistory(
      NameKey project, String refName, RevisionData revision)
      throws RepositoryNotFoundException, MissingObjectException, IncorrectObjectTypeException,
          CorruptObjectException, IOException {
    RevisionReader revisionReader = revReaderProvider.get();
    Optional<RevisionData> revisionDataWithParents =
        revisionReader.read(project, refName, Integer.MAX_VALUE);

    ImmutableList.Builder<RevisionData> revisionDataBuilder = ImmutableList.builder();
    List<ObjectId> parentObjectIds =
        revisionDataWithParents
            .map(RevisionData::getParentObjetIds)
            .orElse(Collections.emptyList());
    for (ObjectId parentObjectId : parentObjectIds) {
      revisionReader.read(project, parentObjectId, refName, 0).ifPresent(revisionDataBuilder::add);
    }

    revisionDataBuilder.add(revision);

    return revisionDataBuilder.build();
  }

  private boolean callFetch(
      Source source, Project.NameKey project, String refName, ReplicationState state) {
    boolean resultIsSuccessful = true;
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchApiClient fetchClient = fetchClientFactory.create(source);
          repLog.info("Pull replication REST API fetch to {} for {}:{}", apiUrl, project, refName);
          Context<String> timer = fetchMetrics.startEnd2End(source.getRemoteConfigName());
          HttpResult result = fetchClient.callFetch(project, refName, uri);
          long elapsedMs = TimeUnit.NANOSECONDS.toMillis(timer.stop());
          boolean resultSuccessful = result.isSuccessful();
          repLog.info(
              "Pull replication REST API fetch to {} COMPLETED for {}:{}, HTTP Result:"
                  + " {} - time:{} ms",
              apiUrl,
              project,
              refName,
              result,
              elapsedMs);
          if (!resultSuccessful
              && result.isProjectMissing(project)
              && source.isCreateMissingRepositories()) {
            result = initProject(project, uri, fetchClient, result);
          }
          if (!resultSuccessful) {
            stateLog.warn(
                String.format(
                    "Pull replication rest api fetch call failed. Endpoint url: %s, reason:%s",
                    apiUrl, result.getMessage().orElse("unknown")),
                state);
          }

          resultIsSuccessful &= result.isSuccessful();
        } catch (URISyntaxException e) {
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
          resultIsSuccessful = false;
        } catch (Exception e) {
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s,"
                      + " message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
          resultIsSuccessful = false;
        }
      }
    }

    return resultIsSuccessful;
  }

  public boolean retry(int attempt, int maxRetries) {
    return maxRetries == 0 || attempt < maxRetries;
  }

  private HttpResult initProject(
      Project.NameKey project, URIish uri, FetchApiClient fetchClient, HttpResult result)
      throws IOException, ClientProtocolException {
    HttpResult initProjectResult = fetchClient.initProject(project, uri);
    if (initProjectResult.isSuccessful()) {
      result = fetchClient.callFetch(project, FetchOne.ALL_REFS, uri);
    } else {
      String errorMessage = initProjectResult.getMessage().map(e -> " - Error: " + e).orElse("");
      repLog.error("Cannot create project " + project + errorMessage);
    }
    return result;
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        fire(
            event.projectName(),
            event.objectId(),
            event.refName(),
            event.eventCreatedOn(),
            event.isDelete());
        eventsReplayed.add(eventKey);
      }
    }
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    sources.get().getAll().stream()
        .filter(s -> s.wouldFetchProject(p))
        .forEach(
            s ->
                s.getApis()
                    .forEach(apiUrl -> s.scheduleUpdateHead(apiUrl, p, event.getNewHeadName())));
  }

  SourcesCollection sourcesCollection() {
    return sources.get();
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(
        String projectName,
        String refName,
        ObjectId objectId,
        long eventCreatedOn,
        boolean isDelete) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(
          projectName, refName, objectId, eventCreatedOn, isDelete);
    }

    public abstract String projectName();

    public abstract String refName();

    public abstract ObjectId objectId();

    public abstract long eventCreatedOn();

    public abstract boolean isDelete();
  }

  @FunctionalInterface
  private interface CallFunction {
    boolean call(Source source) throws MissingParentObjectException;
  }
}
