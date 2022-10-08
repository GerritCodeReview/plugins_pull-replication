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

import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.extensions.restapi.Url;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.message.BasicHeader;
import org.junit.Ignore;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule",
    httpModule = "com.googlesource.gerrit.plugins.replication.pull.api.HttpModule")
public class BearerAuthenticationIT extends ActionITBase {

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Ignore
  public void shouldFetchRef() throws Exception {
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    final HttpPost httpPost = createRequest(sendObjectPayload);
    httpPost.addHeader(new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "some-bearer-token"));
    httpClientFactory
        .create(source)
        .execute(httpPost, assertHttpResponseCode(201));
  }

  @Test
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Ignore
  public void shouldFetchRefInMaster() {}

  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Test
  public void shouldBe401WhenBearerTokenDoesNotMatch() throws Exception {
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    final HttpPost httpPost = createRequest(sendObjectPayload);
    httpPost.addHeader(
        new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + "different-bearer-token"));
    httpClientFactory
        .create(source)
        .execute(httpPost, assertHttpResponseCode(401));
  }

  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Test
  public void shouldBe401WhenBearerTokenCannotBeExtracted() throws Exception {
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    final HttpPost httpPost = createRequest(sendObjectPayload);
    httpPost.addHeader(
        new BasicHeader(HttpHeaders.AUTHORIZATION, "some-value-cannot-be-extracted"));
    httpClientFactory
        .create(source)
        .execute(httpPost, assertHttpResponseCode(401));
  }

  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Test
  public void shouldBe401WhenAuthorizationHeaderIsNotPresent() throws Exception {
    String refName = createRef();
    String sendObjectPayload =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\", \"ref_name\": \""
            + refName
            + "\", \"async\":false}";

    final HttpPost httpPost = createRequest(sendObjectPayload);
    httpClientFactory
        .create(source)
        .execute(httpPost, assertHttpResponseCode(401));
  }

  @Override
  protected String getURL(String projectName) {
    return String.format(
        "%s/projects/%s/pull-replication~fetch", adminRestSession.url(), Url.encode(projectName));
  }
}
