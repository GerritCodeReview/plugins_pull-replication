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

import static com.googlesource.gerrit.plugins.replication.pull.ReplicationQueue.repLog;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.ioutil.HexFormat;
import com.google.gerrit.server.util.IdGenerator;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.Result;
import java.io.IOException;
import org.eclipse.jgit.transport.URIish;

public class UpdateHeadTask implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final FetchApiClient.Factory fetchClientFactory;
  private final Source source;
  private final URIish apiURI;
  private final Project.NameKey project;
  private final String newHead;
  private final int id;

  interface Factory {
    UpdateHeadTask create(Source source, URIish apiURI, Project.NameKey project, String newHead);
  }

  @Inject
  UpdateHeadTask(
      FetchApiClient.Factory fetchClientFactory,
      IdGenerator ig,
      @Assisted Source source,
      @Assisted URIish apiURI,
      @Assisted Project.NameKey project,
      @Assisted String newHead) {
    this.fetchClientFactory = fetchClientFactory;
    this.id = ig.next();
    this.source = source;
    this.apiURI = apiURI;
    this.project = project;
    this.newHead = newHead;
  }

  @Override
  public void run() {
    try {
      Result result = fetchClientFactory.create(source).updateHead(project, newHead, apiURI);
      if (!result.isSuccessful()) {
        throw new IOException(result.message().orElse("Unknown"));
      }
      logger.atFine().log(
          "Successfully updated HEAD of project {} on remote {}",
          project.get(),
          apiURI.toASCIIString());
    } catch (IOException e) {
      String errorMessage =
          String.format(
              "Cannot update HEAD of project %s remote site %s",
              project.get(), apiURI.toASCIIString());
      logger.atWarning().withCause(e).log(errorMessage);
      repLog.warn(errorMessage);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "[%s] update-head %s at %s to %s",
        HexFormat.fromInt(id), project.get(), apiURI.toASCIIString(), newHead);
  }
}
