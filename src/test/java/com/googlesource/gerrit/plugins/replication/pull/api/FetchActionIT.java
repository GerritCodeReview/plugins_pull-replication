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

import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.restapi.Url;
import org.junit.Ignore;
import org.junit.Test;

public class FetchActionIT extends ActionITBase {

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldFetchRefWhenNodeIsAReplica() throws Exception {
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createRequest(sendObjectPayload)),
            assertHttpResponseCode(201));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldFetchRefWhenNodeIsAReplicaAndProjectNameContainsSlash() throws Exception {
    NameKey projectName = Project.nameKey("test/repo");
    String refName = createRef(projectName);
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";
    url =
        String.format(
            "%s/a/projects/%s/pull-replication~fetch",
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
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    httpClientFactory
        .create(source)
        .execute(createRequest(sendObjectPayload), assertHttpResponseCode(403));
  }

  @Ignore
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldFetchRefWhenNodeIsAReplicaWithBearerToken() throws Exception {
    String refName = createRef();
    url = getURLWithoutAuthenticationPrefix(project.get());
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

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
  public void shouldFetchRefWhenNodeIsAPrimaryWithBearerToken() throws Exception {
    String refName = createRef();
    url = getURLWithoutAuthenticationPrefix(project.get());
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createRequest(sendObjectPayload), "some-bearer-token"),
            assertHttpResponseCode(201));
  }

  @Override
  protected String getURLWithAuthenticationPrefix(String projectName) {
    return String.format(
        "%s/a/projects/%s/pull-replication~fetch", adminRestSession.url(), Url.encode(projectName));
  }
}
