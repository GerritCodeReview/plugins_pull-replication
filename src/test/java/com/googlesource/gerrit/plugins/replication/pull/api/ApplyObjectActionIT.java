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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.restapi.Url;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import java.util.Optional;
import org.apache.http.client.methods.HttpPost;
import org.junit.Test;

public class ApplyObjectActionIT extends ActionITBase {

  @Test
  public void shouldAcceptPayloadWithAsyncField() throws Exception {
    String payloadWithAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(payloadWithAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  public void shouldAcceptPayloadWithoutAsyncField() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldAcceptPayloadWhenNodeIsAReplica() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldAcceptPayloadWhenNodeIsAReplicaAndProjectNameContainsSlash() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";
    NameKey projectName = Project.nameKey("test/repo");
    String refName = createRef(projectName);
    Optional<RevisionData> revisionDataOption = createRevisionData(projectName, refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);
    url =
        String.format(
            "%s/a/projects/%s/pull-replication~apply-object",
            adminRestSession.url(), Url.encode(projectName.get()));
    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnUnauthorizedWhenNodeIsAReplicaAndUSerIsAnonymous() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory
        .create(source)
        .execute(post, assertHttpResponseCode(401), getAnonymousContext());
  }

  @Test
  public void shouldReturnBadRequestCodeWhenMandatoryFieldLabelIsMissing() throws Exception {
    String payloadWithoutLabelFieldTemplate =
        "{\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutLabelFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(400), getContext());
  }

  @Test
  public void shouldReturnBadRequestCodeWhenPayloadIsNotAProperJSON() throws Exception {
    String wrongPayloadTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true,}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(wrongPayloadTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(400), getContext());
  }

  private String createPayload(
      String wrongPayloadTemplate, String refName, RevisionData revisionData) {
    String sendObjectPayload =
        String.format(
            wrongPayloadTemplate,
            refName,
            encode(revisionData.getCommitObject().getContent()),
            encode(revisionData.getTreeObject().getContent()));
    return sendObjectPayload;
  }

  @Override
  protected String getURL(String projectName) {
    return String.format(
        "%s/a/projects/%s/pull-replication~apply-object",
        adminRestSession.url(), Url.encode(project.get()));
  }
}
