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
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;

public class ProjectDeletionActionIT extends ActionITBase {
  @Inject private ProjectOperations projectOperations;

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnUnauthorizedForUserWithoutPermissionsOnReplica() throws Exception {
    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(),
            assertHttpResponseCode(HttpServletResponse.SC_UNAUTHORIZED),
            getAnonymousContext());
  }

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldReturnOKWhenProjectIsDeletedOnReplica() throws Exception {
    String testProjectName = project.get();
    url = getURL(testProjectName);

    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(), assertHttpResponseCode(HttpServletResponse.SC_OK), getContext());
  }

  @Test
  @GerritConfig(name = "container.replica", value = "true")
  public void shouldDeleteRepositoryWhenUserHasProjectDeletionCapabilitiesAndNodeIsAReplica()
      throws Exception {
    String testProjectName = project.get();
    url = getURL(testProjectName);
    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(),
            assertHttpResponseCode(HttpServletResponse.SC_FORBIDDEN),
            getUserContext());

    projectOperations
        .project(allProjects)
        .forUpdate()
        .add(
            allowCapability(GlobalCapability.CREATE_PROJECT)
                .group(SystemGroupBackend.REGISTERED_USERS))
        .update();

    httpClientFactory
        .create(source)
        .execute(
            createDeleteRequest(),
            assertHttpResponseCode(HttpServletResponse.SC_CREATED),
            getUserContext());
  }

  @Override
  protected String getURL(String projectName) {
    return String.format("%s/a/projects/%s", adminRestSession.url(), projectName);
  }
}
