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

package com.googlesource.gerrit.plugins.replication.pull.client;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import java.io.IOException;
import java.util.List;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.transport.URIish;

public interface FetchApiClient {

  public interface Factory {
    FetchApiClient create(Source source);
  }

  HttpResult callFetch(
      Project.NameKey project, String refName, URIish targetUri, long startTimeNanos)
      throws ClientProtocolException, IOException;

  default HttpResult callFetch(Project.NameKey project, String refName, URIish targetUri)
      throws ClientProtocolException, IOException {
    return callFetch(project, refName, targetUri, MILLISECONDS.toNanos(System.currentTimeMillis()));
  }

  HttpResult initProject(Project.NameKey project, URIish uri) throws IOException;

  HttpResult deleteProject(Project.NameKey project, URIish apiUri) throws IOException;

  HttpResult updateHead(Project.NameKey project, String newHead, URIish apiUri) throws IOException;

  HttpResult callSendObject(
      NameKey project,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      RevisionData revisionData,
      URIish targetUri)
      throws ClientProtocolException, IOException;

  HttpResult callBatchSendObject(
      NameKey project,
      List<BatchApplyObjectData> batchApplyObjects,
      long eventCreatedOn,
      URIish targetUri)
      throws IllegalArgumentException, ClientProtocolException, IOException;

  HttpResult callSendObjects(
      NameKey project,
      String refName,
      long eventCreatedOn,
      List<RevisionData> revisionData,
      URIish targetUri)
      throws ClientProtocolException, IOException;
}
