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
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.RefInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.transport.URIish;

public interface FetchApiClient {

  public interface Factory {
    FetchApiClient create(Source source);
  }

  HttpResult callFetch(
      Project.NameKey project,
      String refName,
      boolean isDelete,
      URIish targetUri,
      long startTimeNanos,
      boolean forceAsyncFetch)
      throws IOException;

  default HttpResult callFetch(
      Project.NameKey project, String refName, boolean isDelete, URIish targetUri)
      throws IOException {
    return callFetch(
        project,
        refName,
        isDelete,
        targetUri,
        MILLISECONDS.toNanos(System.currentTimeMillis()),
        false);
  }

  HttpResult callBatchFetch(
      Project.NameKey project, List<RefInput> refsInBatch, URIish targetUri, long startTimeNanos)
      throws IOException;

  default HttpResult callBatchFetch(
      Project.NameKey project, List<RefInput> refsInBatch, URIish targetUri) throws IOException {
    return callBatchFetch(
        project, refsInBatch, targetUri, MILLISECONDS.toNanos(System.currentTimeMillis()));
  }

  /**
   * Replicates the creation of a project, including the configuration stored in refs/meta/config.
   *
   * @param project The unique name of the project.
   * @param uri The destination URI where the project and its configuration should be replicated to.
   * @param eventCreatedOn The timestamp indicating when the init project event occurred.
   * @param refsMetaConfigRevisionData A history of revisions for the refs/meta/config ref.
   * @return An HTTP result object providing information about the replication process.
   * @throws IOException If an I/O error occurs during the replication.
   */
  HttpResult initProject(
      Project.NameKey project,
      URIish uri,
      long eventCreatedOn,
      List<RevisionData> refsMetaConfigRevisionData)
      throws IOException;

  HttpResult deleteProject(Project.NameKey project, URIish apiUri) throws IOException;

  HttpResult updateHead(Project.NameKey project, String newHead, URIish apiUri) throws IOException;

  HttpResult callSendObject(
      NameKey project,
      String refName,
      long eventCreatedOn,
      boolean isDelete,
      RevisionData revisionData,
      URIish targetUri)
      throws IOException;

  HttpResult callBatchSendObject(
      NameKey project,
      List<BatchApplyObjectData> batchApplyObjects,
      long eventCreatedOn,
      URIish targetUri)
      throws IOException;

  HttpResult callSendObjects(
      NameKey project,
      String refName,
      long eventCreatedOn,
      List<RevisionData> revisionData,
      URIish targetUri)
      throws IOException;
}
