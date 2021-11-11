// Copyright (C) 2021 The Android Open Source Project
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
import com.google.gerrit.entities.Project;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.GerritRestApi;
import org.eclipse.jgit.transport.URIish;

public class RestApiUpdateHeadTask implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final GerritRestApi.Factory gerritRestApiFactory;
  private final URIish replicateURI;
  private final Project.NameKey project;
  private final String newHead;

  interface Factory {
    RestApiUpdateHeadTask create(URIish uri, Project.NameKey project, String newHead);
  }

  @Inject
  RestApiUpdateHeadTask(
      GerritRestApi.Factory gerritRestApiFactory,
      @Assisted URIish replicateURI,
      @Assisted Project.NameKey project,
      @Assisted String newHead) {
    this.gerritRestApiFactory = gerritRestApiFactory;
    this.replicateURI = replicateURI;
    this.project = project;
    this.newHead = newHead;
  }

  @Override
  public void run() {
    if (gerritRestApiFactory.create(replicateURI).updateHead(project, newHead)) {
      logger.atFine().log(
          "Successfully updated HEAD of project {} on remote {}",
          project.get(),
          replicateURI.toASCIIString());
    } else {
      logger.atWarning().log(
          "Cannot update HEAD of project {} remote site {}.",
          project.get(),
          replicateURI.toASCIIString());
    }
  }

  @Override
  public String toString() {
    return String.format(
        "[%s] update-head at %s to %s", project.get(), replicateURI.toASCIIString(), newHead);
  }
}
