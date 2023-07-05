// Copyright (C) 2012 The Android Open Source Project
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

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;

public class ReplicationState {

  public interface Factory {
    ReplicationState create(FetchResultProcessing processing);
  }

  private boolean allScheduled;
  private final FetchResultProcessing fetchResultProcessing;

  private final Lock countingLock = new ReentrantLock();
  private final CountDownLatch allFetchTasksFinished = new CountDownLatch(1);

  private static class RefReplicationStatus {
    private final String project;
    private final String ref;
    private int projectsToReplicateCount;
    private int replicatedNodesCount;

    RefReplicationStatus(String project, String ref) {
      this.project = project;
      this.ref = ref;
    }

    public boolean allDone() {
      return replicatedNodesCount == projectsToReplicateCount;
    }
  }

  private final Table<String, String, RefReplicationStatus> statusByProjectRef;
  private int totalFetchTasksCount;
  private int finishedFetchTasksCount;

  @AssistedInject
  ReplicationState(@Assisted FetchResultProcessing processing) {
    fetchResultProcessing = processing;
    statusByProjectRef = HashBasedTable.create();
  }

  public void increaseFetchTaskCount(String project, String ref) {
    countingLock.lock();
    try {
      getRefStatus(project, ref).projectsToReplicateCount++;
      totalFetchTasksCount++;
    } finally {
      countingLock.unlock();
    }
  }

  // my stuff
  public void increaseFetchTaskCount(String project, Set<String> refs) {
    countingLock.lock();
    try {
      for (String ref : refs) {
        getRefStatus(project, ref).projectsToReplicateCount++;
      }
      // TODO is the below in the right place, or should we move it inside the for loop above.
      totalFetchTasksCount++;
    } finally {
      countingLock.unlock();
    }
  }

  public boolean hasFetchTask() {
    return totalFetchTasksCount != 0;
  }

  public void notifyRefReplicated(
      String project,
      String ref,
      URIish uri,
      RefFetchResult status,
      RefUpdate.Result refUpdateResult) {
    fetchResultProcessing.onOneProjectReplicationDone(project, ref, uri, status, refUpdateResult);

    RefReplicationStatus completedRefStatus = null;
    boolean allFetchTasksCompleted = false;
    countingLock.lock();
    try {
      RefReplicationStatus refStatus = getRefStatus(project, ref);
      refStatus.replicatedNodesCount++;
      finishedFetchTasksCount++;

      if (allScheduled) {
        if (refStatus.allDone()) {
          completedRefStatus = statusByProjectRef.remove(project, ref);
        }
        allFetchTasksCompleted = finishedFetchTasksCount == totalFetchTasksCount;
      }
    } finally {
      countingLock.unlock();
    }

    if (completedRefStatus != null) {
      doRefFetchTasksCompleted(completedRefStatus);
    }

    if (allFetchTasksCompleted) {
      doAllFetchTasksCompleted();
    }
  }

  public void markAllFetchTasksScheduled() {
    countingLock.lock();
    try {
      allScheduled = true;
      if (finishedFetchTasksCount < totalFetchTasksCount) {
        return;
      }
    } finally {
      countingLock.unlock();
    }

    doAllFetchTasksCompleted();
  }

  private void doAllFetchTasksCompleted() {
    fireRemainingOnRefReplicatedFromAllNodes();
    fetchResultProcessing.onAllRefsReplicatedFromAllNodes(totalFetchTasksCount);
    allFetchTasksFinished.countDown();
  }

  /**
   * Some could be remaining if replication of a ref is completed before all tasks are scheduled.
   */
  private void fireRemainingOnRefReplicatedFromAllNodes() {
    for (RefReplicationStatus refStatus : statusByProjectRef.values()) {
      doRefFetchTasksCompleted(refStatus);
    }
  }

  private void doRefFetchTasksCompleted(RefReplicationStatus refStatus) {
    fetchResultProcessing.onRefReplicatedFromAllNodes(
        refStatus.project, refStatus.ref, refStatus.projectsToReplicateCount);
  }

  private RefReplicationStatus getRefStatus(String project, String ref) {
    if (!statusByProjectRef.contains(project, ref)) {
      RefReplicationStatus refStatus = new RefReplicationStatus(project, ref);
      statusByProjectRef.put(project, ref, refStatus);
      return refStatus;
    }
    return statusByProjectRef.get(project, ref);
  }

  public void waitForReplication() throws InterruptedException {
    allFetchTasksFinished.await();
  }

  public void waitForReplication(long timeout) throws InterruptedException {
    allFetchTasksFinished.await(timeout, TimeUnit.SECONDS);
  }

  public void writeStdOut(String message) {
    fetchResultProcessing.writeStdOut(message);
  }

  public void writeStdErr(String message) {
    fetchResultProcessing.writeStdErr(message);
  }

  public enum RefFetchResult {
    /** The ref was not successfully replicated. */
    FAILED,

    /** The ref is not configured to be replicated. */
    NOT_ATTEMPTED,

    /** The ref was successfully replicated. */
    SUCCEEDED;

    @Override
    public String toString() {
      return name().toLowerCase().replace("_", "-");
    }
  }
}
