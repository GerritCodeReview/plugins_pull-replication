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

import org.junit.Ignore;
import org.junit.Test;

public class ApplyObjectActionIT extends ActionITBase {

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldAcceptPayloadWithAsyncField() throws Exception {
    String payloadWithAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(payloadWithAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(201));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldAcceptPayloadWithoutAsyncField() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(201));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldAcceptPayloadWhenNodeIsAReplica() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(201));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldAcceptPayloadWhenNodeIsAReplicaAndProjectNameContainsSlash() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";
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
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(201));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnForbiddenWhenNodeIsAReplicaAndUSerIsAnonymous() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(createRequest(sendObjectPayload), assertHttpResponseCode(403));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnBadRequestCodeWhenMandatoryFieldLabelIsMissing() throws Exception {
    String payloadWithoutLabelFieldTemplate =
        "{\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutLabelFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(400));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnBadRequestCodeWhenPayloadIsNotAProperJSON() throws Exception {
    String wrongPayloadTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true,}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(wrongPayloadTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(400));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldAcceptPayloadWhenNodeIsAReplicaWithBearerToken() throws Exception {
    url = getURLWithoutAuthenticationPrefix(project.get());
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createRequest(sendObjectPayload), "some-bearer-token"),
            assertHttpResponseCode(201));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "false")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldAcceptPayloadWhenNodeIsAPrimaryWithBearerToken() throws Exception {
    url = getURLWithoutAuthenticationPrefix(project.get());
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"sha1\":\"%s\",\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createRequest(sendObjectPayload), "some-bearer-token"),
            assertHttpResponseCode(201));
  }

  private String createPayload(
      String wrongPayloadTemplate, String refName, RevisionData revisionData) {
    String sendObjectPayload =
        String.format(
            wrongPayloadTemplate,
            refName,
            revisionData.getCommitObject().getSha1(),
            encode(revisionData.getCommitObject().getContent()),
            encode(revisionData.getTreeObject().getContent()));
    return sendObjectPayload;
  }

  @Override
  protected String getURLWithAuthenticationPrefix(String projectName) {
    return String.format(
        "%s/a/projects/%s/pull-replication~apply-object",
        adminRestSession.url(), Url.encode(projectName));
  }
}
