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
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1.Context;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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

public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitBatchRefUpdateListener,
        ProjectDeletedListener,
        HeadUpdatedListener {

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
  private FetchApiClient.Factory fetchClientFactory;
  private Integer fetchCallsTimeout;
  private ExcludedRefsFilter refsFilter;
  private Provider<RevisionReader> revReaderProvider;
  private final ApplyObjectMetrics applyObjectMetrics;
  private final FetchReplicationMetrics fetchMetrics;
  private ThreadPoolExecutor batchUpdatePool;
  private final BatchUpdateConfiguration cfg;
  private final AtomicLong inflightBatch = new AtomicLong(0);
  private final AtomicLong inflightFetch = new AtomicLong(0);

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
      ReplicationConfig replicationConfig,
      FetchReplicationMetrics fetchMetrics) {
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

    cfg = new BatchUpdateConfiguration(replicationConfig.getConfig());
    repLog.info("custom batch config inject, useAsync:{}, corePoolSize:{}, maxPoolSize:{}, aliveTime:{}, queueSize:{}",
        cfg.useAsync(),
        cfg.getBatchUpdateCorePoolSize(),
        cfg.getBatchUpdateMaxPoolSize(),
        cfg.getBatchUpdateAliveTime(),
        cfg.getBatchUpdateQueueSize());
    if(!cfg.useAsync()){
      return;
    }
    batchUpdatePool = new ThreadPoolExecutor(
            cfg.getBatchUpdateCorePoolSize(),
            cfg.getBatchUpdateMaxPoolSize(),
            cfg.getBatchUpdateAliveTime(),
            TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>(cfg.getBatchUpdateQueueSize()),
            new ThreadFactoryBuilder().setNameFormat("BatchUpdatePool-%d").build(),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );
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
    if(batchUpdatePool != null){
      repLog.warn("ShutDown BatchUpdatePool");
      batchUpdatePool.shutdown();
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
  public void onGitBatchRefUpdate(GitBatchRefUpdateListener.Event event) {
    Map<Integer, List<UpdatedRef>> changeIdRefsMap = event
            .getUpdatedRefs()
            .stream()
            .collect(Collectors.groupingBy(ReplicationQueue::groupByTypeAndChangeId, LinkedHashMap::new, Collectors.toList()));

    List<Integer>changeIds =new ArrayList<>(changeIdRefsMap.keySet());
    Collections.sort(changeIds);

    List<GitBatchRefUpdateListener.UpdatedRef>sortedRefs = new ArrayList<>();
    for(Integer changeId : changeIds){
      sortedRefs.addAll(changeIdRefsMap.get(changeId).stream().sorted(ReplicationQueue::sortByPatchIdAndMetaRefAsLast).collect(Collectors.toList()));
    }

    if(cfg.useAsync()){
      List<String>refs = sortedRefs.stream().map(r-> r.getRefName()+" "+refUpdateType(r)).collect(Collectors.toList());

      batchUpdatePool.submit(()->{
        inflightBatch.incrementAndGet();
        repLog.info("Batch event received, project:{}, ref-pkg:{}, thread:{}", event.getProjectName(), Arrays.toString(refs.toArray()), Thread.currentThread().getName());
        sortedRefs.stream().forEachOrdered(
                updateRef -> {
                  inflightFetch.incrementAndGet();
                  String refName = updateRef.getRefName();

                  if (isRefToBeReplicated(refName)) {
                    repLog.info(
                        "Ref event received: {} on project {}:{} - {} => {}",
                        refUpdateType(updateRef),
                        event.getProjectName(),
                        refName,
                        updateRef.getOldObjectId(),
                        updateRef.getNewObjectId());
                    fire(ReferenceUpdatedEvent.from(event.getProjectName(), updateRef));
                  }
                  inflightFetch.decrementAndGet();
                });
        inflightBatch.decrementAndGet();
      });

      int cpuRatio = batchUpdatePool.getActiveCount()*100 / cfg.getBatchUpdateMaxPoolSize();
      long queueRatio = inflightBatch.get()*100 / (cfg.getBatchUpdateMaxPoolSize() + cfg.getBatchUpdateMaxPoolSize());

      if( cpuRatio > 90 || queueRatio > 90){
        repLog.warn("Batch update pool #need more resource#, activeCount:{}, inflightBatch:{}, inflightFetch:{}, totalCount:{}", batchUpdatePool.getActiveCount(), inflightBatch.get(), inflightFetch.get(), batchUpdatePool.getTaskCount());
      }else{
        repLog.info("Batch update pool, activeCount:{}, inflightBatch:{}, inflightFetch:{}, totalCount:{}", batchUpdatePool.getActiveCount(), inflightBatch.get(), inflightFetch.get(), batchUpdatePool.getTaskCount());
      }
    }else{
      sortedRefs.stream().forEachOrdered(
              updateRef -> {
                String refName = updateRef.getRefName();
                if (isRefToBeReplicated(refName)) {
                  repLog.info(
                      "Ref event received: {} on project {}:{} - {} => {}",
                      refUpdateType(updateRef),
                      event.getProjectName(),
                      refName,
                      updateRef.getOldObjectId(),
                      updateRef.getNewObjectId());
                  fire(ReferenceUpdatedEvent.from(event.getProjectName(), updateRef));
                }
              });
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

  private static int sortByMetaRefAsLast(UpdatedRef a, @SuppressWarnings("unused") UpdatedRef b) {
    repLog.info("sortByMetaRefAsLast(" + a.getRefName() + " <=> " + b.getRefName());
    return Boolean.compare(
        RefNames.isNoteDbMetaRef(a.getRefName()), RefNames.isNoteDbMetaRef(b.getRefName()));
  }

  /**
   * 如果不是refs/changes且不是refs/changes/meta，分为一组
   * 相同changeId的refs/changes和refs/changes/meta分为一组
   */
  private static int groupByTypeAndChangeId(UpdatedRef ref) {
    if(RefNames.isRefsChanges(ref.getRefName())) {
      return getChangeIdFromRefsChanges(ref.getRefName());
    }

    return Integer.MAX_VALUE;
  }

  private static int sortByPatchIdAndMetaRefAsLast(UpdatedRef a, @SuppressWarnings("unused") UpdatedRef b) {
    boolean isAChangeMetaRef = isChangeMetaRef(a.getRefName());
    boolean isBChangeMetaRef = isChangeMetaRef(b.getRefName());
    if(isAChangeMetaRef || isBChangeMetaRef){
      return Boolean.compare(isAChangeMetaRef, isBChangeMetaRef);
    }

    boolean isARefsChanges = RefNames.isRefsChanges(a.getRefName());
    boolean isBRefsChanges = RefNames.isRefsChanges(b.getRefName());
    if(isARefsChanges && isBRefsChanges){
      return Integer.compare(RefNames.parseRefSuffix(a.getRefName()), RefNames.parseRefSuffix(b.getRefName()));
    }

    return Boolean.compare(isBRefsChanges, isARefsChanges);
  }

  private static String refUpdateType(UpdatedRef updateRef) {
    String forcedPrefix = updateRef.isNonFastForward() ? "FORCED " : " ";
    if (updateRef.isCreate()) {
      return forcedPrefix + "CREATE";
    } else if (updateRef.isDelete()) {
      return forcedPrefix + "DELETE";
    } else {
      return forcedPrefix + "UPDATE";
    }
  }

  private static boolean isChangeMetaRef(String ref){
    return ref.startsWith("refs/changes/") && ref.endsWith("/meta");
  }

  /**
   * 从refs/changes/xx/yyyxx/01 或者 refs/changes/xx/yyyxx/meta形式的refs获取changeId
   */
  private static int getChangeIdFromRefsChanges(String ref){
    int i;
    for(i = ref.length()-1; i >= 0; --i) {
      if (ref.charAt(i) == '/') {
        break;
      }
    }
    return RefNames.parseRefSuffix(ref.substring(0, i));
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(ReferenceUpdatedEvent event) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(event, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(ReferenceUpdatedEvent event, ReplicationState state) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(event);
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
              Project.nameKey(event.projectName()),
              event.objectId(),
              event.refName(),
              event.isDelete(),
              state);
      fetchCallsPool
          .submit(() -> allSources.parallelStream().forEach(callFunction))
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
      boolean isDelete,
      ReplicationState state) {
    CallFunction call = getCallFunction(project, objectId, refName, isDelete, state);

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
      boolean isDelete,
      ReplicationState state) {
    if (isDelete) {
      return ((source) -> callSendObject(source, project, refName, isDelete, null, state));
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
                source, project, refName, isDelete, Arrays.asList(revisionData.get()), state));
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
      Project.NameKey project,
      String refName,
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
                  ? fetchClient.callSendObject(project, refName, isDelete, null, uri)
                  : fetchClient.callSendObjects(project, refName, revision, uri);
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

              if (RefNames.isNoteDbMetaRef(refName) && revision.size() == 1) {
                List<RevisionData> allRevisions =
                    fetchWholeMetaHistory(project, refName, revision.get(0));
                repLog.info(
                    "Pull replication REST API apply object to {} for {}:{} - {}",
                    apiUrl,
                    project,
                    refName,
                    allRevisions);
                return callSendObject(source, project, refName, isDelete, allRevisions, state);
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
        fire(event);
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

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(
        String projectName, String refName, ObjectId objectId, boolean isDelete) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(
          projectName, refName, objectId, isDelete);
    }

    static ReferenceUpdatedEvent from(String projectName, UpdatedRef updateRef) {
      return ReferenceUpdatedEvent.create(
          projectName,
          updateRef.getRefName(),
          ObjectId.fromString(updateRef.getNewObjectId()),
          updateRef.isDelete());
    }

    public abstract String projectName();

    public abstract String refName();

    public abstract ObjectId objectId();

    public abstract boolean isDelete();
  }

  @FunctionalInterface
  private interface CallFunction {
    boolean call(Source source) throws MissingParentObjectException;
  }
}
