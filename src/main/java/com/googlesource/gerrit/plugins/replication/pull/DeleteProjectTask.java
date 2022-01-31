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
import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;

public class DeleteProjectTask implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  interface Factory {
    DeleteProjectTask create(Source source, String uri, Project.NameKey project);
  }

  private final int id;
  private final Source source;
  private final String uri;
  private final Project.NameKey project;
  private final FetchApiClient.Factory fetchClientFactory;

  @Inject
  DeleteProjectTask(
      FetchApiClient.Factory fetchClientFactory,
      IdGenerator ig,
      @Assisted Source source,
      @Assisted String uri,
      @Assisted Project.NameKey project) {
    this.fetchClientFactory = fetchClientFactory;
    this.id = ig.next();
    this.uri = uri;
    this.source = source;
    this.project = project;
  }

  @Override
  public void run() {
    try {
      URIish urIish = new URIish(uri);
      Result result = fetchClientFactory.create(source).deleteProject(project, urIish);
      if (!result.isSuccessful()) {
        throw new IOException(result.message().orElse("Unknown"));
      }
      logger.atFine().log("Successfully deleted project {} on remote {}", project.get(), uri);
    } catch (URISyntaxException | IOException e) {
      String errorMessage =
          String.format("Cannot delete project %s on remote site %s.", project, uri);
      logger.atWarning().withCause(e).log(errorMessage);
      repLog.warn(errorMessage);
    }
  }

  @Override
  public String toString() {
    return String.format("[%s] delete-project %s at %s", HexFormat.fromInt(id), project.get(), uri);
  }
}
