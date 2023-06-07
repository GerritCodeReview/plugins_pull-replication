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
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;

import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gson.Gson;
import com.google.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.junit.Ignore;
import org.junit.Test;

public class UpdateHeadActionIT extends ActionITBase {
  private static final Gson gson = newGson();

  @Inject private ProjectOperations projectOperations;

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnUnauthorizedForUserWithoutPermissions() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createPutRequest(headInput("some/branch")),
            assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnBadRequestWhenInputIsEmpty() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequest(headInput(""))),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnOKWhenHeadIsUpdated() throws Exception {
    String testProjectName = project.get();
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequest(headInput(newBranch))),
            assertHttpResponseCode(HttpServletResponse.SC_OK));

    assertThat(gApi.projects().name(testProjectName).head()).isEqualTo(newBranch);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnBadRequestWhenInputIsEmptyInReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequest(headInput(""))),
            assertHttpResponseCode(HttpServletResponse.SC_BAD_REQUEST));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnOKWhenHeadIsUpdatedInReplica() throws Exception {
    String testProjectName = project.get();
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsAdmin(createPutRequest(headInput(newBranch))),
            assertHttpResponseCode(HttpServletResponse.SC_OK));

    assertThat(gApi.projects().name(testProjectName).head()).isEqualTo(newBranch);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnForbiddenWhenMissingPermissions() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createPutRequest(headInput("some/new/head"))),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  public void shouldReturnOKWhenRegisteredUserHasPermissions() throws Exception {
    String testProjectName = project.get();
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    HttpRequestBase put = withBasicAuthenticationAsUser(createPutRequest(headInput(newBranch)));
    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));

    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.OWNER).ref("refs/*").group(REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(put, assertHttpResponseCode(HttpServletResponse.SC_OK));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnForbiddenWhenMissingPermissionsInReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            withBasicAuthenticationAsUser(createPutRequest(headInput("some/new/head"))),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN));
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "true")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Ignore("Waiting for resolving: Issue 16332: Not able to update the HEAD from internal user")
  public void shouldReturnOKWhenHeadIsUpdatedInReplicaWithBearerToken() throws Exception {
    String testProjectName = project.get();
    url = getURLWithoutAuthenticationPrefix(testProjectName);
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(
                createPutRequest(headInput(newBranch)), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_OK));

    assertThat(gApi.projects().name(testProjectName).head()).isEqualTo(newBranch);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = "testInstanceId")
  @GerritConfig(name = "container.replica", value = "false")
  @GerritConfig(name = "auth.bearerToken", value = "some-bearer-token")
  @Ignore("Waiting for resolving: Issue 16332: Not able to update the HEAD from internal user")
  public void shouldReturnOKWhenHeadIsUpdatedInPrimaryWithBearerToken() throws Exception {
    String testProjectName = project.get();
    url = getURLWithoutAuthenticationPrefix(testProjectName);
    String newBranch = "refs/heads/mybranch";
    String master = "refs/heads/master";
    BranchInput input = new BranchInput();
    input.revision = master;
    gApi.projects().name(testProjectName).branch(newBranch).create(input);
    httpClientFactory
        .create(source)
        .execute(
            withBearerTokenAuthentication(
                createPutRequest(headInput(newBranch)), "some-bearer-token"),
            assertHttpResponseCode(HttpServletResponse.SC_OK));

    assertThat(gApi.projects().name(testProjectName).head()).isEqualTo(newBranch);
  }

  private String headInput(String ref) {
    HeadInput headInput = new HeadInput();
    headInput.ref = ref;
    return gson.toJson(headInput);
  }

  @Override
  protected String getURLWithAuthenticationPrefix(String projectName) {
    return String.format("%s/a/projects/%s/HEAD", adminRestSession.url(), projectName);
  }
}
