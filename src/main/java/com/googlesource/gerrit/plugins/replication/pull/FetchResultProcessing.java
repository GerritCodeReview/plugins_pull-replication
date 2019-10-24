// Copyright (C) 2013 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.events.RefEvent;
import com.google.gerrit.server.permissions.PermissionBackendException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.URIish;

public abstract class FetchResultProcessing {

  abstract void onOneProjectReplicationDone(
      String project,
      String ref,
      URIish uri,
      ReplicationState.RefFetchResult status,
      RefUpdate.Result refUpdateResult);

  abstract void onRefReplicatedFromAllNodes(String project, String ref, int nodesCount);

  abstract void onAllRefsReplicatedFromAllNodes(int totalFetchTasksCount);

  /**
   * Write message to standard out.
   *
   * @param message message text.
   */
  void writeStdOut(String message) {
    // Default doing nothing
  }

  /**
   * Write message to standard error.
   *
   * @param message message text.
   */
  void writeStdErr(String message) {
    // Default doing nothing
  }

  public static String resolveNodeName(URIish uri) {
    StringBuilder sb = new StringBuilder();
    if (uri.isRemote()) {
      sb.append(uri.getHost());
      if (uri.getPort() != -1) {
        sb.append(":");
        sb.append(uri.getPort());
      }
    } else {
      sb.append(uri.getPath());
    }
    return sb.toString();
  }

  public static class CommandProcessing extends FetchResultProcessing {
    private WeakReference<StartFetchCommand> sshCommand;
    private AtomicBoolean hasError = new AtomicBoolean();

    CommandProcessing(StartFetchCommand sshCommand) {
      this.sshCommand = new WeakReference<>(sshCommand);
    }

    @Override
    void onOneProjectReplicationDone(
        String project,
        String ref,
        URIish uri,
        ReplicationState.RefFetchResult status,
        RefUpdate.Result refUpdateResult) {
      StringBuilder sb = new StringBuilder();
      sb.append("Replicate ");
      sb.append(project);
      sb.append(" ref ");
      sb.append(ref);
      sb.append(" from ");
      sb.append(resolveNodeName(uri));
      sb.append(", ");
      switch (status) {
        case SUCCEEDED:
          sb.append("Succeeded!");
          break;
        case FAILED:
          sb.append("FAILED!");
          hasError.compareAndSet(false, true);
          break;
        case NOT_ATTEMPTED:
          sb.append("NOT ATTEMPTED!");
          break;
        default:
          sb.append("UNKNOWN RESULT!");
          break;
      }
      sb.append(" (");
      sb.append(refUpdateResult.toString());
      sb.append(")");
      writeStdOut(sb.toString());
    }

    @Override
    void onRefReplicatedFromAllNodes(String project, String ref, int nodesCount) {
      StringBuilder sb = new StringBuilder();
      sb.append("Replication of ");
      sb.append(project);
      sb.append(" ref ");
      sb.append(ref);
      sb.append(" completed from ");
      sb.append(nodesCount);
      sb.append(" nodes, ");
      writeStdOut(sb.toString());
    }

    @Override
    void onAllRefsReplicatedFromAllNodes(int totalFetchTasksCount) {
      if (totalFetchTasksCount == 0) {
        return;
      }
      writeStdOut("----------------------------------------------");
      if (hasError.get()) {
        writeStdOut("Replication completed with some errors!");
      } else {
        writeStdOut("Replication completed successfully!");
      }
    }

    @Override
    void writeStdOut(String message) {
      StartFetchCommand command = sshCommand.get();
      if (command != null) {
        command.writeStdOutSync(message);
      }
    }

    @Override
    void writeStdErr(String message) {
      StartFetchCommand command = sshCommand.get();
      if (command != null) {
        command.writeStdErrSync(message);
      }
    }
  }

  public static class GitUpdateProcessing extends FetchResultProcessing {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private final EventDispatcher dispatcher;

    public GitUpdateProcessing(EventDispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    void onOneProjectReplicationDone(
        String project,
        String ref,
        URIish uri,
        ReplicationState.RefFetchResult result,
        RefUpdate.Result refUpdateResult) {
      postEvent(
          new FetchRefReplicatedEvent(project, ref, resolveNodeName(uri), result, refUpdateResult));
    }

    @Override
    void onRefReplicatedFromAllNodes(String project, String ref, int nodesCount) {
      postEvent(new FetchRefReplicationDoneEvent(project, ref, nodesCount));
    }

    @Override
    void onAllRefsReplicatedFromAllNodes(int totalFetchTasksCount) {}

    private void postEvent(RefEvent event) {
      try {
        dispatcher.postEvent(event);
      } catch (PermissionBackendException e) {
        logger.atSevere().withCause(e).log("Cannot post event");
      }
    }
  }
}
