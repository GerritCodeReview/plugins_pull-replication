// Copyright (C) 2022 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.api.BatchFetchAction.Inputs;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RemoteConfigurationMissingException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class FetchJob implements Runnable {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();

  public interface Factory {
    FetchJob create(
        Project.NameKey project, Inputs inputs, PullReplicationApiRequestMetrics metrics);
  }

  private FetchCommand command;
  private Project.NameKey project;
  private Inputs inputs;
  private final PullReplicationApiRequestMetrics metrics;

  @Inject
  public FetchJob(
      FetchCommand command,
      @Assisted Project.NameKey project,
      @Assisted Inputs inputs,
      @Assisted PullReplicationApiRequestMetrics metrics) {
    this.command = command;
    this.project = project;
    this.inputs = inputs;
    this.metrics = metrics;
  }

  @Override
  public void run() {
    try {
      command.fetchAsync(project, inputs, metrics);
      //      command.fetchAsync(project, input.label, input.refName, metrics);
    } catch (InterruptedException
        | ExecutionException
        | RemoteConfigurationMissingException
        | TimeoutException e) {
      log.atSevere().withCause(e).log(
          "Exception during the async fetch call for project %s, label %s and ref names: [%s]",
          project.get(), inputs.label, String.join(",", inputs.refNames));
    }
  }
}
