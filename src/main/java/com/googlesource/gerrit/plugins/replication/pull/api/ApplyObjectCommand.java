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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.ApplyObjectCacheModule.APPLY_OBJECTS_CACHE;
import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.googlesource.gerrit.plugins.replication.pull.*;
import com.googlesource.gerrit.plugins.replication.pull.ReplicationState.RefFetchResult;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;

public class ApplyObjectCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Cache<ApplyObjectsCacheKey, Long> refUpdatesSucceededCache;
  private static final Set<RefUpdate.Result> SUCCESSFUL_RESULTS =
      ImmutableSet.of(
          RefUpdate.Result.NEW,
          RefUpdate.Result.FORCED,
          RefUpdate.Result.NO_CHANGE,
          RefUpdate.Result.FAST_FORWARD);

  private final PullReplicationStateLogger fetchStateLog;
  private final ApplyObject applyObject;
  private final ApplyObjectMetrics metrics;
  private final DynamicItem<EventDispatcher> eventDispatcher;

  @Inject
  public ApplyObjectCommand(
      PullReplicationStateLogger fetchStateLog,
      ApplyObject applyObject,
      ApplyObjectMetrics metrics,
      DynamicItem<EventDispatcher> eventDispatcher,
      @Named(APPLY_OBJECTS_CACHE) Cache<ApplyObjectsCacheKey, Long> refUpdatesSucceededCache) {
    this.fetchStateLog = fetchStateLog;
    this.applyObject = applyObject;
    this.metrics = metrics;
    this.eventDispatcher = eventDispatcher;
    this.refUpdatesSucceededCache = refUpdatesSucceededCache;
  }

  public void applyObject(
      Project.NameKey name,
      String refName,
      RevisionData revisionsData,
      String sourceLabel,
      long eventCreatedOn)
      throws IOException, RefUpdateException, MissingParentObjectException {
    applyObjects(name, refName, new RevisionData[] {revisionsData}, sourceLabel, eventCreatedOn);
  }

  public void applyObjects(
      Project.NameKey name,
      String refName,
      RevisionData[] revisionsData,
      String sourceLabel,
      long eventCreatedOn)
      throws IOException, RefUpdateException, MissingParentObjectException {

    repLog.info(
        "Apply object from {} for {}:{} - {}",
        sourceLabel,
        name,
        refName,
        Arrays.toString(revisionsData));
    Timer1.Context<String> context = metrics.start(sourceLabel);

    RefUpdateState refUpdateState = applyObject.apply(name, new RefSpec(refName), revisionsData);
    Boolean isRefUpdateSuccessful = isSuccessful(refUpdateState.getResult());

    if (isRefUpdateSuccessful) {
      for (RevisionData revisionData : revisionsData) {
        RevisionObjectData commitObj = revisionData.getCommitObject();
        List<RevisionObjectData> blobs = revisionData.getBlobs();

        if (commitObj != null) {
          refUpdatesSucceededCache.put(
              ApplyObjectsCacheKey.create(
                  revisionData.getCommitObject().getSha1(), refName, name.get()),
              eventCreatedOn);
        } else if (blobs != null) {
          for (RevisionObjectData blob : blobs) {
            refUpdatesSucceededCache.put(
                ApplyObjectsCacheKey.create(blob.getSha1(), refName, name.get()), eventCreatedOn);
          }
        }
      }
    }
    long elapsed = NANOSECONDS.toMillis(context.stop());

    try {
      Context.setLocalEvent(true);
      eventDispatcher
          .get()
          .postEvent(
              new FetchRefReplicatedEvent(
                  name.get(),
                  refName,
                  sourceLabel,
                  getStatus(refUpdateState),
                  refUpdateState.getResult()));
    } catch (PermissionBackendException e) {
      logger.atSevere().withCause(e).log(
          "Cannot post event for ref '%s', project %s", refName, name);
    } finally {
      Context.unsetLocalEvent();
    }

    if (!isRefUpdateSuccessful) {
      String message =
          String.format(
              "RefUpdate failed with result %s for: sourceLcabel=%s, project=%s, refName=%s",
              refUpdateState.getResult().name(), sourceLabel, name, refName);
      fetchStateLog.error(message);
      throw new RefUpdateException(message);
    }
    repLog.info(
        "Apply object from {} for project {}, ref name {} completed in {}ms",
        sourceLabel,
        name,
        refName,
        elapsed);
  }

  private RefFetchResult getStatus(RefUpdateState refUpdateState) {
    return isSuccessful(refUpdateState.getResult())
        ? ReplicationState.RefFetchResult.SUCCEEDED
        : ReplicationState.RefFetchResult.FAILED;
  }

  private Boolean isSuccessful(RefUpdate.Result result) {
    return SUCCESSFUL_RESULTS.contains(result);
  }
}
