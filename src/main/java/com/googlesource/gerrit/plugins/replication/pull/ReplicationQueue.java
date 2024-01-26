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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

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
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.data.RefUpdateAttribute;
import com.google.gerrit.server.events.BatchRefUpdateEvent;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.RefInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchRestApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResultUtils;
import com.googlesource.gerrit.plugins.replication.pull.filter.ApplyObjectsRefsFilter;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
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
  private static final String BATCH_REF_UPDATED_EVENT_TYPE = BatchRefUpdateEvent.TYPE;
  private static final String REF_UDPATED_EVENT_TYPE = new RefUpdatedEvent().type;
  private static final String ZEROS_OBJECTID = ObjectId.zeroId().getName();
  private final ReplicationStateListener stateLog;
  private final ShutdownState shutdownState;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceBatchUpdatedEvent> beforeStartupEventsQueue;
  private FetchApiClient.Factory fetchClientFactory;
  private Integer fetchCallsTimeout;
  private ExcludedRefsFilter refsFilter;
  private Provider<RevisionReader> revReaderProvider;
  private final ApplyObjectMetrics applyObjectMetrics;
  private final ReplicationQueueMetrics queueMetrics;
  private final String instanceId;
  private final boolean useBatchUpdateEvents;
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
      ReplicationQueueMetrics queueMetrics,
      @GerritInstanceId String instanceId,
      @GerritServerConfig Config gerritConfig,
      ApplyObjectsRefsFilter applyObjectsRefsFilter,
      ShutdownState shutdownState) {
    workQueue = wq;
    dispatcher = dis;
    sources = rd;
    stateLog = sl;
    this.shutdownState = shutdownState;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    this.fetchClientFactory = fetchClientFactory;
    this.refsFilter = refsFilter;
    this.revReaderProvider = revReaderProvider;
    this.applyObjectMetrics = applyObjectMetrics;
    this.queueMetrics = queueMetrics;
    this.instanceId = instanceId;
    this.useBatchUpdateEvents =
        gerritConfig.getBoolean("event", "stream-events", "enableBatchRefUpdatedEvents", false);
    this.applyObjectsRefsFilter = applyObjectsRefsFilter;
  }

  @Override
  public void start() {
    if (!running) {
      sources.get().startup(workQueue);
      queueMetrics.start(this);
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
    shutdownState.setIsShuttingDown(true);
    int discarded = sources.get().shutdown();
    if (discarded > 0) {
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
    queueMetrics.stop();
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
    if (!instanceId.equals(e.instanceId)) {
      return;
    }

    if (useBatchUpdateEvents) {
      if (e.type.equals(BATCH_REF_UPDATED_EVENT_TYPE)) {
        BatchRefUpdateEvent event = (BatchRefUpdateEvent) e;
        repLog.info(
            "Batch ref event received on project {} for refs: {}",
            event.getProjectNameKey().get(),
            String.join(",", event.getRefNames()));

        long eventCreatedOn = e.eventCreatedOn;
        List<ReferenceUpdatedEvent> refs =
            event.refUpdates.get().stream()
                .filter(u -> isRefToBeReplicated(u.refName))
                .map(
                    u -> {
                      repLog.info(
                          "Ref event received: {} on project {}:{} - {} => {}",
                          refUpdateType(u.oldRev, u.newRev),
                          event.getProjectNameKey().get(),
                          u.refName,
                          u.oldRev,
                          u.newRev);
                      return ReferenceUpdatedEvent.from(u, eventCreatedOn);
                    })
                .sorted(ReplicationQueue::sortByMetaRefAsLast)
                .collect(Collectors.toList());

        if (!refs.isEmpty()) {
          ReferenceBatchUpdatedEvent referenceBatchUpdatedEvent =
              ReferenceBatchUpdatedEvent.create(
                  event.getProjectNameKey().get(), refs, eventCreatedOn);
          fire(referenceBatchUpdatedEvent);
        }
      }
      return;
    }

    if (e.type.equals(REF_UDPATED_EVENT_TYPE)) {
      RefUpdatedEvent event = (RefUpdatedEvent) e;

      if (isRefToBeReplicated(event.getRefName())) {
        RefUpdateAttribute refUpdateAttribute = event.refUpdate.get();
        repLog.info(
            "Ref event received: {} on project {}:{} - {} => {}",
            refUpdateType(refUpdateAttribute.oldRev, refUpdateAttribute.newRev),
            event.getProjectNameKey().get(),
            refUpdateAttribute.refName,
            refUpdateAttribute.oldRev,
            refUpdateAttribute.newRev);

        ReferenceBatchUpdatedEvent referenceBatchUpdatedEvent =
            ReferenceBatchUpdatedEvent.create(
                event.getProjectNameKey().get(),
                List.of(ReferenceUpdatedEvent.from(refUpdateAttribute, e.eventCreatedOn)),
                e.eventCreatedOn);
        fire(referenceBatchUpdatedEvent);
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

  private static int sortByMetaRefAsLast(ReferenceUpdatedEvent a, ReferenceUpdatedEvent b) {
    repLog.debug("sortByMetaRefAsLast({} <=> {})", a.refName(), b.refName());
    return Boolean.compare(
        RefNames.isNoteDbMetaRef(a.refName()), RefNames.isNoteDbMetaRef(b.refName()));
  }

  private static String refUpdateType(String oldRev, String newRev) {
    if (ZEROS_OBJECTID.equals(oldRev)) {
      return "CREATE";
    } else if (ZEROS_OBJECTID.equals(newRev)) {
      return "DELETE";
    } else {
      return "UPDATE";
    }
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(ReferenceBatchUpdatedEvent event) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(event, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(ReferenceBatchUpdatedEvent event, ReplicationState state) {
    if (!running) {
      stateLog.warn(
          String.format(
              "Replication plugin did not finish startup before event, event replication is postponed"
                  + " for event %s",
              event),
          state);
      beforeStartupEventsQueue.add(event);

      queueMetrics.incrementQueuedBeforStartup();
      return;
    }
    ForkJoinPool fetchCallsPool = null;
    try {
      List<Source> allSources = sources.get().getAll();
      int numSources = allSources.size();
      if (numSources == 0) {
        repLog.debug("No replication sources configured -> skipping fetch");
        return;
      }
      fetchCallsPool = new ForkJoinPool(numSources);

      final Consumer<Source> callFunction =
          callFunction(
              Project.nameKey(event.projectName()), event.refs(), event.eventCreatedOn(), state);
      fetchCallsPool
          .submit(() -> allSources.parallelStream().forEach(callFunction))
          .get(fetchCallsTimeout, MILLISECONDS);
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
      List<ReferenceUpdatedEvent> refs,
      long eventCreatedOn,
      ReplicationState state) {
    CallFunction call = getCallFunction(project, refs, eventCreatedOn, state);

    return (source) -> {
      boolean callSuccessful;
      try {
        callSuccessful = call.call(source);
      } catch (Exception e) {
        repLog.warn(
            String.format(
                "Failed to batch apply object %s on project %s, falling back to git fetch",
                refs.stream()
                    .map(event -> String.format("%s:%s", event.refName(), event.objectId()))
                    .collect(Collectors.joining(",")),
                project),
            e);
        callSuccessful = false;
      }

      if (!callSuccessful) {
        if (source.enableBatchedRefs()) {
          callBatchFetch(source, project, refs, state);
        } else {
          callFetch(source, project, refs, state, FetchRestApiClient.FORCE_ASYNC);
        }
      }
    };
  }

  private CallFunction getCallFunction(
      NameKey project,
      List<ReferenceUpdatedEvent> refs,
      long eventCreatedOn,
      ReplicationState state) {

    try {
      List<BatchApplyObjectData> refsBatch =
          refs.stream()
              .map(ref -> toBatchApplyObject(project, ref, state))
              .collect(Collectors.toList());

      if (!containsLargeRef(refsBatch)) {
        return ((source) -> callBatchSendObject(source, project, refsBatch, eventCreatedOn, state));
      }
    } catch (UncheckedIOException e) {
      stateLog.error("Falling back to calling fetch", e, state);
    }
    return ((source) -> callBatchFetch(source, project, refs, state));
  }

  private BatchApplyObjectData toBatchApplyObject(
      NameKey project, ReferenceUpdatedEvent event, ReplicationState state) {
    if (event.isDelete()) {
      Optional<RevisionData> noRevisionData = Optional.empty();
      return BatchApplyObjectData.create(event.refName(), noRevisionData, event.isDelete());
    }
    try {
      Optional<RevisionData> maybeRevisionData =
          revReaderProvider.get().read(project, event.objectId(), event.refName(), 0);
      return BatchApplyObjectData.create(event.refName(), maybeRevisionData, event.isDelete());
    } catch (IOException e) {
      stateLog.error(
          String.format(
              "Exception during reading ref: %s, project:%s, message: %s",
              event.refName(), project.get(), e.getMessage()),
          e,
          state);
      throw new UncheckedIOException(e);
    }
  }

  private boolean containsLargeRef(List<BatchApplyObjectData> batchApplyObjectData) {
    return batchApplyObjectData.stream().anyMatch(e -> e.revisionData().isEmpty() && !e.isDelete());
  }

  private Optional<HttpResult> callSendObject(
      FetchApiClient fetchClient,
      String remoteName,
      URIish uri,
      NameKey project,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      List<RevisionData> revision)
      throws IOException {
    String revisionDataStr =
        Optional.ofNullable(revision).orElse(ImmutableList.of()).stream()
            .map(RevisionData::toString)
            .collect(Collectors.joining(","));
    repLog.info(
        "Pull replication REST API apply object to {} for {}:{} - {}",
        uri,
        project,
        refName,
        revisionDataStr);
    Context<String> apiTimer = applyObjectMetrics.startEnd2End(remoteName);
    HttpResult result =
        isDelete
            ? fetchClient.callSendObject(project, refName, eventCreatedOn, isDelete, null, uri)
            : fetchClient.callSendObjects(project, refName, eventCreatedOn, revision, uri);
    repLog.info(
        "Pull replication REST API apply object to {} COMPLETED for {}:{} - {}, HTTP Result:"
            + " {} - time:{} ms",
        uri,
        project,
        refName,
        revisionDataStr,
        result,
        apiTimer.stop() / 1000000.0);

    return Optional.of(result);
  }

  private boolean callBatchSendObject(
      Source source,
      NameKey project,
      List<BatchApplyObjectData> refsBatch,
      long eventCreatedOn,
      ReplicationState state)
      throws MissingParentObjectException {
    boolean batchResultSuccessful = true;

    List<BatchApplyObjectData> filteredRefsBatch =
        refsBatch.stream()
            .filter(r -> source.wouldFetchProject(project) && source.wouldFetchRef(r.refName()))
            .collect(Collectors.toList());

    String batchApplyObjectStr =
        filteredRefsBatch.stream()
            .map(BatchApplyObjectData::toString)
            .collect(Collectors.joining(","));
    FetchApiClient fetchClient = fetchClientFactory.create(source);
    String remoteName = source.getRemoteConfigName();

    for (String apiUrl : source.getApis()) {
      try {
        boolean resultSuccessful = true;
        Optional<HttpResult> result = Optional.empty();
        URIish uri = new URIish(apiUrl);
        if (source.enableBatchedRefs()) {
          repLog.info(
              "Pull replication REST API batch apply object to {} for {}:[{}]",
              apiUrl,
              project,
              batchApplyObjectStr);
          Context<String> apiTimer = applyObjectMetrics.startEnd2End(remoteName);
          result =
              Optional.of(
                  fetchClient.callBatchSendObject(project, filteredRefsBatch, eventCreatedOn, uri));
          resultSuccessful = HttpResultUtils.isSuccessful(result);
          repLog.info(
              "Pull replication REST API batch apply object to {} COMPLETED for {}:[{}], HTTP  Result:"
                  + " {} - time:{} ms",
              apiUrl,
              project,
              batchApplyObjectStr,
              HttpResultUtils.status(result),
              apiTimer.stop() / 1000000.0);
        } else {
          repLog.info(
              "REST API batch apply object not enabled for source {}, using REST API apply object to {} for {}:[{}]",
              remoteName,
              apiUrl,
              project,
              batchApplyObjectStr);
          for (BatchApplyObjectData batchApplyObject : filteredRefsBatch) {
            result =
                callSendObject(
                    fetchClient,
                    remoteName,
                    uri,
                    project,
                    batchApplyObject.refName(),
                    eventCreatedOn,
                    batchApplyObject.isDelete(),
                    batchApplyObject.revisionData().map(ImmutableList::of).orElse(null));

            resultSuccessful = HttpResultUtils.isSuccessful(result);
            if (!resultSuccessful) {
              break;
            }
          }
        }

        if (!resultSuccessful
            && HttpResultUtils.isProjectMissing(result, project)
            && source.isCreateMissingRepositories()) {
          result = initProject(project, uri, fetchClient, result);
          repLog.info(
              "Missing project {} created, HTTP Result:{}",
              project,
              HttpResultUtils.status(result));
        }

        if (!resultSuccessful && HttpResultUtils.isParentObjectMissing(result)) {
          resultSuccessful = true;
          for (BatchApplyObjectData batchApplyObject : filteredRefsBatch) {
            String refName = batchApplyObject.refName();
            if ((RefNames.isNoteDbMetaRef(refName) || applyObjectsRefsFilter.match(refName))
                && batchApplyObject.revisionData().isPresent()) {

              Optional<RevisionData> maybeRevisionData = batchApplyObject.revisionData();
              List<RevisionData> allRevisions =
                  fetchWholeMetaHistory(project, refName, maybeRevisionData.get());

              Optional<HttpResult> sendObjectResult =
                  callSendObject(
                      fetchClient,
                      remoteName,
                      uri,
                      project,
                      refName,
                      eventCreatedOn,
                      batchApplyObject.isDelete(),
                      allRevisions);
              resultSuccessful = HttpResultUtils.isSuccessful(sendObjectResult);
              if (!resultSuccessful) {
                break;
              }
            } else {
              throw new MissingParentObjectException(
                  project, refName, source.getRemoteConfigName());
            }
          }
        }

        batchResultSuccessful &= resultSuccessful;
      } catch (URISyntaxException e) {
        repLog.warn(
            "Pull replication REST API batch apply object to {} *FAILED* for {}:[{}]",
            apiUrl,
            project,
            batchApplyObjectStr,
            e);
        stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
        batchResultSuccessful = false;
      } catch (IOException | IllegalArgumentException e) {
        repLog.warn(
            "Pull replication REST API batch apply object to {} *FAILED* for {}:[{}]",
            apiUrl,
            project,
            batchApplyObjectStr,
            e);
        stateLog.error(
            String.format(
                "Exception during the pull replication fetch rest api call. Endpoint url:%s,"
                    + " message:%s",
                apiUrl, e.getMessage()),
            e,
            state);
        batchResultSuccessful = false;
      }
    }
    return batchResultSuccessful;
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

  private boolean callBatchFetch(
      Source source,
      Project.NameKey project,
      List<ReferenceUpdatedEvent> refs,
      ReplicationState state) {

    boolean resultIsSuccessful = true;

    List<RefInput> filteredRefs =
        refs.stream()
            .map(ref -> RefInput.create(ref.refName(), ref.isDelete()))
            .filter(ref -> source.wouldFetchProject(project) && source.wouldFetchRef(ref.refName()))
            .collect(Collectors.toList());

    String refsStr = filteredRefs.stream().map(RefInput::refName).collect(Collectors.joining(","));
    FetchApiClient fetchClient = fetchClientFactory.create(source);

    for (String apiUrl : source.getApis()) {
      try {
        URIish uri = new URIish(apiUrl);
        Optional<HttpResult> result = Optional.empty();
        repLog.info(
            "Pull replication REST API batch fetch to {} for {}:[{}]", apiUrl, project, refsStr);
        long startTime = System.currentTimeMillis();
        result = Optional.of(fetchClient.callBatchFetch(project, filteredRefs, uri));
        long endTime = System.currentTimeMillis();
        boolean resultSuccessful = HttpResultUtils.isSuccessful(result);
        repLog.info(
            "Pull replication REST API batch fetch to {} COMPLETED for {}:[{}], HTTP Result:"
                + " {} - time:{} ms",
            apiUrl,
            project,
            refsStr,
            HttpResultUtils.status(result),
            endTime - startTime);
        if (!resultSuccessful
            && HttpResultUtils.isProjectMissing(result, project)
            && source.isCreateMissingRepositories()) {
          result = initProject(project, uri, fetchClient, result);
          resultSuccessful = HttpResultUtils.isSuccessful(result);
        }
        if (!resultSuccessful) {
          stateLog.warn(
              String.format(
                  "Pull replication REST API batch fetch call failed. Endpoint url: %s, reason:%s",
                  apiUrl, HttpResultUtils.errorMsg(result)),
              state);
        }
        resultIsSuccessful &= resultSuccessful;
      } catch (URISyntaxException e) {
        stateLog.error(
            String.format("Cannot parse pull replication batch api url:%s", apiUrl), state);
        resultIsSuccessful = false;
      } catch (Exception e) {
        stateLog.error(
            String.format(
                "Exception during the pull replication batch fetch rest api call. Endpoint url:%s,"
                    + " message:%s",
                apiUrl, e.getMessage()),
            e,
            state);
        resultIsSuccessful = false;
      }
    }

    return resultIsSuccessful;
  }

  private boolean callFetch(
      Source source,
      Project.NameKey project,
      List<ReferenceUpdatedEvent> refs,
      ReplicationState state,
      boolean forceAsyncCall) {
    boolean resultIsSuccessful = true;
    for (ReferenceUpdatedEvent refEvent : refs) {
      String refName = refEvent.refName();
      boolean isDelete = refEvent.isDelete();
      if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
        for (String apiUrl : source.getApis()) {
          try {
            URIish uri = new URIish(apiUrl);
            FetchApiClient fetchClient = fetchClientFactory.create(source);
            repLog.info(
                "Pull replication REST API fetch to {} for {}:{}{}",
                apiUrl,
                project,
                refName,
                isDelete ? " (DELETE)" : "");
            long startTime = System.currentTimeMillis();
            Optional<HttpResult> result =
                Optional.of(
                    fetchClient.callFetch(
                        project,
                        refName,
                        isDelete,
                        uri,
                        MILLISECONDS.toNanos(System.currentTimeMillis()),
                        forceAsyncCall));
            long endTime = System.currentTimeMillis();
            boolean resultSuccessful = HttpResultUtils.isSuccessful(result);
            repLog.info(
                "Pull replication REST API fetch to {} COMPLETED for {}:{}{}, HTTP Result:"
                    + " {} - time: {} ms",
                apiUrl,
                project,
                refName,
                isDelete ? " (DELETE)" : "",
                HttpResultUtils.status(result),
                endTime - startTime);
            if (!resultSuccessful
                && HttpResultUtils.isProjectMissing(result, project)
                && source.isCreateMissingRepositories()) {
              result = initProject(project, uri, fetchClient, result);
            }
            if (!resultSuccessful) {
              stateLog.warn(
                  String.format(
                      "Pull replication rest api fetch call failed. Endpoint url: %s, reason:%s",
                      apiUrl, HttpResultUtils.errorMsg(result)),
                  state);
            }

            resultIsSuccessful &= HttpResultUtils.isSuccessful(result);
          } catch (URISyntaxException e) {
            stateLog.error(
                String.format("Cannot parse pull replication api url:%s", apiUrl), state);
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
    }

    return resultIsSuccessful;
  }

  public boolean retry(int attempt, int maxRetries) {
    return maxRetries == 0 || attempt < maxRetries;
  }

  private Optional<HttpResult> initProject(
      Project.NameKey project, URIish uri, FetchApiClient fetchClient, Optional<HttpResult> result)
      throws IOException {
    RevisionData refsMetaConfigRevisionData =
        revReaderProvider
            .get()
            .read(project, null, RefNames.REFS_CONFIG, 0)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        String.format(
                            "Project %s does not have %s", project, RefNames.REFS_CONFIG)));

    List<RevisionData> refsMetaConfigDataList =
        fetchWholeMetaHistory(project, RefNames.REFS_CONFIG, refsMetaConfigRevisionData);
    HttpResult initProjectResult =
        fetchClient.initProject(project, uri, System.currentTimeMillis(), refsMetaConfigDataList);
    if (initProjectResult.isSuccessful()) {
      result = Optional.of(fetchClient.callFetch(project, FetchOne.ALL_REFS, false, uri));
    } else {
      String errorMessage = initProjectResult.getMessage().map(e -> " - Error: " + e).orElse("");
      repLog.error("Cannot create project " + project + errorMessage);
    }
    return result;
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    ReferenceBatchUpdatedEvent event;
    while ((event = beforeStartupEventsQueue.peek()) != null) {
      String eventKey =
          String.format(
              "%s:%s",
              event.projectName(),
              event.refs().stream()
                  .map(ReferenceUpdatedEvent::refName)
                  .collect(Collectors.joining()));
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        fire(event);
        eventsReplayed.add(eventKey);
      }
      beforeStartupEventsQueue.remove(event);
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
  abstract static class ReferenceBatchUpdatedEvent {

    static ReferenceBatchUpdatedEvent create(
        String projectName, List<ReferenceUpdatedEvent> refs, long eventCreatedOn) {
      return new AutoValue_ReplicationQueue_ReferenceBatchUpdatedEvent(
          projectName, refs, eventCreatedOn);
    }

    public abstract String projectName();

    public abstract List<ReferenceUpdatedEvent> refs();

    public abstract long eventCreatedOn();
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

    static ReferenceUpdatedEvent from(RefUpdateAttribute refUpdateAttribute, long eventCreatedOn) {
      return ReferenceUpdatedEvent.create(
          refUpdateAttribute.project,
          refUpdateAttribute.refName,
          ObjectId.fromString(refUpdateAttribute.newRev),
          eventCreatedOn,
          ZEROS_OBJECTID.equals(refUpdateAttribute.newRev));
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
