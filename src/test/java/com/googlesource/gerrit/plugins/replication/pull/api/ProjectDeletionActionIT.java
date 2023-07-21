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

import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.junit.Test;

public class ProjectDeletionActionIT extends ActionITBase {
  public static final String INVALID_TEST_PROJECT_NAME = "\0";
  public static final String DELETE_PROJECT_PERMISSION = "delete-project-deleteProject";

  @Inject private ProjectOperations projectOperations;

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnUnauthorizedForUserWithoutPermissions() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(), assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldDeleteRepositoryWhenUserHasProjectDeletionCapabilities() throws Exception {
    String testProjectName = project.get();
    url = getURLWithAuthenticationPrefix(testProjectName);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(allowCapability(DELETE_PROJECT_PERMISSION).group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnOKWhenProjectIsDeleted() throws Exception {
    String testProjectName = project.get();
    url = getURLWithAuthenticationPrefix(testProjectName);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnBadRequestWhenDeletingAnInvalidProjectName() throws Exception {
    url = getURLWithAuthenticationPrefix(INVALID_TEST_PROJECT_NAME);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnUnauthorizedForUserWithoutPermissionsOnReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(), assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnOKWhenProjectIsDeletedOnReplica() throws Exception {
    String testProjectName = project.get();
    url = getURLWithAuthenticationPrefix(testProjectName);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldDeleteRepositoryWhenUserHasProjectDeletionCapabilitiesAndNodeIsAReplica()
      throws Exception {
    String testProjectName = project.get();
    url = getURLWithAuthenticationPrefix(testProjectName);
    HttpRequestBase deleteRequest = withBasicAuthenticationAsUser(createDeleteRequest());

    httpClientFactory
        .create(source)
        .execute(deleteRequest, assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(allowCapability(DELETE_PROJECT_PERMISSION).group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(deleteRequest, assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnBadRequestWhenDeletingAnInvalidProjectNameWhenNodeIsAReplica()
      throws Exception {
    url = getURLWithAuthenticationPrefix(INVALID_TEST_PROJECT_NAME);

    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createDeleteRequest()),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldReturnOKWhenProjectIsDeletedOnReplicaWithBearerToken() throws Exception {
    String testProjectName = project.get();
    url = getURLWithoutAuthenticationPrefix(testProjectName);

    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createDeleteRequest(), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "false")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  public void shouldReturnOKWhenProjectIsDeletedOnPrimaryWithBearerToken() throws Exception {
    String testProjectName = project.get();
    url = getURLWithoutAuthenticationPrefix(testProjectName);

    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(createDeleteRequest(), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Override
  protected String getURLWithAuthenticationPrefix(String projectName) {
    return String.format(
        "%s/a/projects/%s/pull-replication~delete-project",
        adminRestSession.url(), Url.encode(projectName));
  }
}
