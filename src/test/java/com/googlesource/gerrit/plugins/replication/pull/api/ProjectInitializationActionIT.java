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

import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allowCapability;

import com.google.common.net.MediaType;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicHeader;
import org.junit.Test;

public class ProjectInitializationActionIT extends ActionITBase {
  public static final String INVALID_TEST_PROJECT_NAME = "\0";
  @Inject private ProjectOperations projectOperations;

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnUnauthorizedForUserWithoutPermissions() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createPutRequestWithHeaders(),
            assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnBadRequestIfContentNotSet() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequestWithoutHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldCreateRepository() throws Exception {
    String newProjectName = "new/newProjectForPrimary";
    url = getURLWithAuthenticationPrefix(newProjectName);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequestWithHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_CREATED));

    HttpRequestBase getNewProjectRequest =
        withBasicAuthenticationAsAdmin(
            new HttpGet(userRestSession.url() + "/a/projects/" + Url.encode(newProjectName)));

    httpClientFactory
        .create(source)
        .execute(getNewProjectRequest, assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldCreateRepositoryWhenUserHasProjectCreationCapabilities() throws Exception {
    String newProjectName = "new/newProjectForUserWithCapabilities";
    url = getURLWithAuthenticationPrefix(newProjectName);
    HttpRequestBase put = withBasicAuthenticationAsUser(createPutRequestWithHeaders());
    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(
            allowCapability(GlobalCapability.CREATE_PROJECT)
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_CREATED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnForbiddenIfUserNotAuthorized() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createPutRequestWithHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldCreateRepositoryWhenNodeIsAReplica() throws Exception {
    String newProjectName = "new/newProjectForReplica";
    url = getURLWithAuthenticationPrefix(newProjectName);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequestWithHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_CREATED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnForbiddenIfUserNotAuthorizedAndNodeIsAReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createPutRequestWithHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldCreateRepositoryWhenUserHasProjectCreationCapabilitiesAndNodeIsAReplica()
      throws Exception {
    String newProjectName = "new/newProjectForUserWithCapabilitiesReplica";
    url = getURLWithAuthenticationPrefix(newProjectName);
    HttpRequestBase put = withBasicAuthenticationAsUser(createPutRequestWithHeaders());
    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(
            allowCapability(GlobalCapability.CREATE_PROJECT)
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_CREATED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnBadRequestIfProjectNameIsInvalidAndCannotBeCreatedWhenNodeIsAReplica()
      throws Exception {
    url = getURLWithAuthenticationPrefix(INVALID_TEST_PROJECT_NAME);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequestWithHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnBadRequestIfContentNotSetWhenNodeIsAReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequestWithoutHeaders()),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnForbiddenForUserWithoutPermissionsWhenNodeIsAReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createPutRequestWithHeaders(),
            assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldCreateRepositoryWhenNodeIsAReplicaWithBearerToken() throws Exception {
    String newProjectName = "new/newProjectForReplica";
    url = getURLWithoutAuthenticationPrefix(newProjectName);
    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createPutRequestWithHeaders(), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_CREATED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "false")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldCreateRepositoryWhenNodeIsAPrimaryWithBearerToken() throws Exception {
    String newProjectName = "new/newProjectForReplica";
    url = getURLWithoutAuthenticationPrefix(newProjectName);
    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createPutRequestWithHeaders(), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_CREATED));
  }

  @Override
  protected String getURLWithAuthenticationPrefix(String projectName) {
    return userRestSession.url()
        + String.format("/a/plugins/pull-replication/init-project/%s.git", Url.encode(projectName));
  }

  protected HttpPut createPutRequestWithHeaders() {
    HttpPut put = createPutRequestWithoutHeaders();
    put.addHeader(new BasicHeader("Accept", MediaType.ANY_TEXT_TYPE.toString()));
    put.addHeader(new BasicHeader("Content-Type", MediaType.PLAIN_TEXT_UTF_8.toString()));
    return put;
  }

  protected HttpPut createPutRequestWithoutHeaders() {
    HttpPut put = new HttpPut(url);
    return put;
  }
}
