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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.metrics.Timer1;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.ApplyObjectMetrics;
import com.googlesource.gerrit.plugins.replication.pull.Command;
import com.googlesource.gerrit.plugins.replication.pull.PullReplicationStateLogger;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.ApplyObject;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Set;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.RefSpec;

public class ApplyObjectCommand implements Command {

  private static final Set<RefUpdate.Result> SUCCESSFUL_RESULTS =
      ImmutableSet.of(
          RefUpdate.Result.NEW,
          RefUpdate.Result.FORCED,
          RefUpdate.Result.NO_CHANGE,
          RefUpdate.Result.FAST_FORWARD);

  private final PullReplicationStateLogger fetchStateLog;
  private final ApplyObject applyObject;
  private final ApplyObjectMetrics metrics;

  @Inject
  public ApplyObjectCommand(
      PullReplicationStateLogger fetchStateLog,
      ApplyObject applyObject,
      ApplyObjectMetrics metrics) {
    this.fetchStateLog = fetchStateLog;
    this.applyObject = applyObject;
    this.metrics = metrics;
  }

  public void applyObject(Project.NameKey name, String refName, String object, String sourceLabel)
      throws IOException, RefUpdateException {

    repLog.info("Apply object from {} for project {}, ref name {}", sourceLabel, name, refName);
    Timer1.Context<String> context = metrics.start(sourceLabel);
    RefUpdateState refUpdateState = applyObject.apply(name, new RefSpec(refName), object);
    long elapsed = NANOSECONDS.toMillis(context.stop());

    if (!isSuccessful(refUpdateState.getResult())) {
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

  private Boolean isSuccessful(RefUpdate.Result result) {
    return SUCCESSFUL_RESULTS.contains(result);
  }

  @Override
  public void writeStdOutSync(String message) {}

  @Override
  public void writeStdErrSync(String message) {}
}
