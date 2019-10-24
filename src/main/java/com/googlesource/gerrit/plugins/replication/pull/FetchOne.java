// Copyright (C) 2019 The Android Open Source Project
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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.base.Throwables;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.gerrit.entities.Project;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.ProjectRunnable;
import com.google.gerrit.server.git.WorkQueue.CanceledWhileRunning;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.util.IdGenerator;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.jcraft.jsch.JSchException;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.RemoteRepositoryException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.MDC;

/**
 * A pull from remote operation started by command-line.
 *
 * <p>Instance members are protected by the lock within FetchQueue. Callers must take that lock to
 * ensure they are working with a current view of the object.
 */
class FetchOne implements ProjectRunnable, CanceledWhileRunning {
  private final ReplicationStateListener stateLog;
  static final String ALL_REFS = "..all..";
  static final String ID_MDC_KEY = "fetchOneId";

  interface Factory {
    FetchOne create(Project.NameKey d, URIish u);
  }

  private final GitRepositoryManager gitManager;
  private final Source pool;
  private final RemoteConfig config;
  private final CredentialsProvider credentialsProvider;
  private final PerThreadRequestScope.Scoper threadScoper;

  private final Project.NameKey projectName;
  private final URIish uri;
  private final Set<String> delta = Sets.newHashSetWithExpectedSize(4);
  private boolean fetchAllRefs;
  private Repository git;
  private boolean retrying;
  private int retryCount;
  private final int maxRetries;
  private boolean canceled;
  private final ListMultimap<String, ReplicationState> stateMap = LinkedListMultimap.create();
  private final int maxLockRetries;
  private int lockRetryCount;
  private final int id;
  private final long createdAt;
  private final FetchReplicationMetrics metrics;
  private final AtomicBoolean canceledWhileRunning;

  @Inject
  FetchOne(
      GitRepositoryManager grm,
      Source s,
      RemoteConfig c,
      CredentialsFactory cpFactory,
      PerThreadRequestScope.Scoper ts,
      IdGenerator ig,
      ReplicationStateListeners sl,
      FetchReplicationMetrics m,
      @Assisted Project.NameKey d,
      @Assisted URIish u) {
    gitManager = grm;
    pool = s;
    config = c;
    credentialsProvider = cpFactory.create(c.getName());
    threadScoper = ts;
    projectName = d;
    uri = u;
    lockRetryCount = 0;
    maxLockRetries = pool.getLockErrorMaxRetries();
    id = ig.next();
    stateLog = sl;
    createdAt = System.nanoTime();
    metrics = m;
    canceledWhileRunning = new AtomicBoolean(false);
    maxRetries = s.getMaxRetries();
  }

  @Override
  public void cancel() {
    repLog.info("Replication {} was canceled", getURI());
    canceledByReplication();
    pool.fetchWasCanceled(this);
  }

  @Override
  public void setCanceledWhileRunning() {
    repLog.info("Replication {} was canceled while being executed", getURI());
    canceledWhileRunning.set(true);
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return projectName;
  }

  @Override
  public String getRemoteName() {
    return config.getName();
  }

  @Override
  public boolean hasCustomizedPrint() {
    return true;
  }

  @Override
  public String toString() {
    String print = "[" + HexFormat.fromInt(id) + "] fetch " + uri;

    if (retryCount > 0) {
      print = "(retry " + retryCount + ") " + print;
    }
    return print;
  }

  boolean isRetrying() {
    return retrying;
  }

  boolean setToRetry() {
    retrying = true;
    retryCount++;
    return maxRetries == 0 || retryCount <= maxRetries;
  }

  void canceledByReplication() {
    canceled = true;
  }

  boolean wasCanceled() {
    return canceled;
  }

  URIish getURI() {
    return uri;
  }

  void addRef(String ref) {
    if (ALL_REFS.equals(ref)) {
      delta.clear();
      fetchAllRefs = true;
      repLog.trace("Added all refs for replication from {}", uri);
    } else if (!fetchAllRefs) {
      delta.add(ref);
      repLog.trace("Added ref {} for replication from {}", ref, uri);
    }
  }

  Set<String> getRefs() {
    return fetchAllRefs ? Sets.newHashSet(ALL_REFS) : delta;
  }

  void addRefs(Set<String> refs) {
    if (!fetchAllRefs) {
      for (String ref : refs) {
        addRef(ref);
      }
    }
  }

  void addState(String ref, ReplicationState state) {
    stateMap.put(ref, state);
  }

  ListMultimap<String, ReplicationState> getStates() {
    return stateMap;
  }

  ReplicationState[] getStatesAsArray() {
    Set<ReplicationState> statesSet = new HashSet<>();
    statesSet.addAll(stateMap.values());
    return statesSet.toArray(new ReplicationState[statesSet.size()]);
  }

  ReplicationState[] getStatesByRef(String ref) {
    Collection<ReplicationState> states = stateMap.get(ref);
    return states.toArray(new ReplicationState[states.size()]);
  }

  void addStates(ListMultimap<String, ReplicationState> states) {
    stateMap.putAll(states);
  }

  void removeStates() {
    stateMap.clear();
  }

  private void statesCleanUp() {
    if (!stateMap.isEmpty() && !isRetrying()) {
      for (Map.Entry<String, ReplicationState> entry : stateMap.entries()) {
        entry
            .getValue()
            .notifyRefReplicated(
                projectName.get(),
                entry.getKey(),
                uri,
                ReplicationState.RefFetchResult.FAILED,
                null);
      }
    }
  }

  @Override
  public void run() {
    try {
      threadScoper
          .scope(
              new Callable<Void>() {
                @Override
                public Void call() {
                  runFetchOperation();
                  return null;
                }
              })
          .call();
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw new RuntimeException(e);
    } finally {
      statesCleanUp();
    }
  }

  private void runFetchOperation() {
    // Lock the queue, and remove ourselves, so we can't be modified once
    // we start replication (instead a new instance, with the same URI, is
    // created and scheduled for a future point in time.)
    //
    MDC.put(ID_MDC_KEY, HexFormat.fromInt(id));
    if (!pool.requestRunway(this)) {
      if (!canceled) {
        repLog.info(
            "Rescheduling replication to {} to avoid collision with an in-flight fetch.", uri);
        pool.reschedule(this, Source.RetryReason.COLLISION);
      }
      return;
    }

    repLog.info("Replication from {} started...", uri);
    Timer1.Context<String> context = metrics.start(config.getName());
    try {
      long startedAt = context.getStartTime();
      long delay = NANOSECONDS.toMillis(startedAt - createdAt);
      metrics.record(config.getName(), delay, retryCount);
      git = gitManager.openRepository(projectName);
      runImpl();
      long elapsed = NANOSECONDS.toMillis(context.stop());
      repLog.info(
          "Replication from {} completed in {}ms, {}ms delay, {} retries",
          uri,
          elapsed,
          delay,
          retryCount);
    } catch (RepositoryNotFoundException e) {
      stateLog.error(
          "Cannot replicate " + projectName + "; Local repository error: " + e.getMessage(),
          getStatesAsArray());

    } catch (NoRemoteRepositoryException | RemoteRepositoryException e) {
      // Tried to replicate to a remote via anonymous git:// but the repository
      // does not exist.  In this case NoRemoteRepositoryException is not
      // raised.
      String msg = e.getMessage();
      repLog.error("Cannot replicate {}; Remote repository error: {}", projectName, msg);
    } catch (NotSupportedException e) {
      stateLog.error("Cannot replicate from " + uri, e, getStatesAsArray());
    } catch (TransportException e) {
      Throwable cause = e.getCause();
      if (cause instanceof JSchException && cause.getMessage().startsWith("UnknownHostKey:")) {
        repLog.error("Cannot replicate from {}: {}", uri, cause.getMessage());
      } else if (e instanceof LockFailureException) {
        lockRetryCount++;
        // The LockFailureException message contains both URI and reason
        // for this failure.
        repLog.error("Cannot replicate from {}: {}", uri, e.getMessage());

        // The remote fetch operation should be retried.
        if (lockRetryCount <= maxLockRetries) {
          if (canceledWhileRunning.get()) {
            logCanceledWhileRunningException(e);
          } else {
            pool.reschedule(this, Source.RetryReason.TRANSPORT_ERROR);
          }
        } else {
          repLog.error(
              "Giving up after {} occurrences of this error: {} during replication from {}",
              lockRetryCount,
              e.getMessage(),
              uri);
        }
      } else {
        if (canceledWhileRunning.get()) {
          logCanceledWhileRunningException(e);
        } else {
          repLog.error("Cannot replicate from {}", uri, e);
          // The remote fetch operation should be retried.
          pool.reschedule(this, Source.RetryReason.TRANSPORT_ERROR);
        }
      }
    } catch (IOException e) {
      stateLog.error("Cannot replicate from " + uri, e, getStatesAsArray());
    } catch (RuntimeException | Error e) {
      stateLog.error("Unexpected error during replication from " + uri, e, getStatesAsArray());
    } finally {
      if (git != null) {
        git.close();
      }
      pool.notifyFinished(this);
    }
  }

  private void logCanceledWhileRunningException(TransportException e) {
    repLog.info("Cannot replicate from {}. It was canceled while running", uri, e);
  }

  private void runImpl() throws IOException {
    FetchResult res;
    try (Transport tn = Transport.open(git, uri)) {
      res = fetchVia(tn);
    }
    updateStates(res.getTrackingRefUpdates());
  }

  private FetchResult fetchVia(Transport tn) throws IOException {
    tn.applyConfig(config);
    tn.setCredentialsProvider(credentialsProvider);

    repLog.info("Fetch references {} from {}", config.getFetchRefSpecs(), uri);

    return tn.fetch(NullProgressMonitor.INSTANCE, config.getFetchRefSpecs());
  }

  private void updateStates(Collection<TrackingRefUpdate> refUpdates) throws IOException {
    Set<String> doneRefs = new HashSet<>();
    boolean anyRefFailed = false;
    RefUpdate.Result lastRefUpdateResult = RefUpdate.Result.NO_CHANGE;

    for (TrackingRefUpdate u : refUpdates) {
      ReplicationState.RefFetchResult fetchStatus = ReplicationState.RefFetchResult.SUCCEEDED;
      Set<ReplicationState> logStates = new HashSet<>();
      lastRefUpdateResult = u.getResult();

      logStates.addAll(stateMap.get(u.getRemoteName()));
      logStates.addAll(stateMap.get(ALL_REFS));
      ReplicationState[] logStatesArray = logStates.toArray(new ReplicationState[logStates.size()]);

      doneRefs.add(u.getRemoteName());
      switch (u.getResult()) {
        case NO_CHANGE:
        case NEW:
        case FORCED:
        case RENAMED:
        case FAST_FORWARD:
          break;
        case NOT_ATTEMPTED:
        case REJECTED:
        case REJECTED_CURRENT_BRANCH:
        case REJECTED_MISSING_OBJECT:
          stateLog.error(
              String.format(
                  "Failed replicate %s from %s: result %s", uri, u.getRemoteName(), u.getResult()),
              logStatesArray);
          fetchStatus = ReplicationState.RefFetchResult.FAILED;
          anyRefFailed = true;
          break;

        case LOCK_FAILURE:
          throw new LockFailureException(uri, u.toString());
        case IO_FAILURE:
          throw new IOException(u.toString());

        case REJECTED_OTHER_REASON:
          stateLog.error(
              String.format(
                  "Failed replicate %s from %s, reason: %s", uri, u.getRemoteName(), u.toString()),
              logStatesArray);

          fetchStatus = ReplicationState.RefFetchResult.FAILED;
          anyRefFailed = true;
          break;
      }

      for (ReplicationState rs : getStatesByRef(u.getRemoteName())) {
        rs.notifyRefReplicated(
            projectName.get(), u.getRemoteName(), uri, fetchStatus, u.getResult());
      }
    }

    doneRefs.add(ALL_REFS);
    for (ReplicationState rs : getStatesByRef(ALL_REFS)) {
      rs.notifyRefReplicated(
          projectName.get(),
          ALL_REFS,
          uri,
          anyRefFailed
              ? ReplicationState.RefFetchResult.FAILED
              : ReplicationState.RefFetchResult.SUCCEEDED,
          lastRefUpdateResult);
    }
    for (Map.Entry<String, ReplicationState> entry : stateMap.entries()) {
      if (!doneRefs.contains(entry.getKey())) {
        entry
            .getValue()
            .notifyRefReplicated(
                projectName.get(),
                entry.getKey(),
                uri,
                ReplicationState.RefFetchResult.NOT_ATTEMPTED,
                null);
      }
    }
    stateMap.clear();
  }

  public static class LockFailureException extends TransportException {
    private static final long serialVersionUID = 1L;

    LockFailureException(URIish uri, String message) {
      super(uri, message);
    }
  }
}
