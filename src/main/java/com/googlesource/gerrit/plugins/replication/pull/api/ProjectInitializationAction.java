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

import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.checkAcceptHeader;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.setResponse;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
<<<<<<< Updated upstream
=======
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.api.util.PayloadSerDes;
>>>>>>> Stashed changes
import java.io.IOException;
import java.util.Optional;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class ProjectInitializationAction extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PROJECT_NAME = "project-name";

  private final GerritConfigOps gerritConfigOps;
  private final Provider<CurrentUser> userProvider;
  private final PermissionBackend permissionBackend;
  private final ProjectIndexer projectIndexer;

  @Inject
  ProjectInitializationAction(
      GerritConfigOps gerritConfigOps,
      Provider<CurrentUser> userProvider,
      PermissionBackend permissionBackend,
      ProjectIndexer projectIndexer) {
    this.gerritConfigOps = gerritConfigOps;
    this.userProvider = userProvider;
    this.permissionBackend = permissionBackend;
    this.projectIndexer = projectIndexer;
  }

  @Override
  protected void doPut(
      HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {

    if (!checkAcceptHeader(httpServletRequest, httpServletResponse)) {
      return;
    }

    String path = httpServletRequest.getRequestURI();
    String projectName = Url.decode(path.substring(path.lastIndexOf('/') + 1));
    try {
<<<<<<< Updated upstream
      if (initProject(projectName)) {
=======
      boolean result = true;
      if (httpServletRequest.getContentLength() == 0) {
        initProject(projectName);
      } else {
        initProjectWithoutIndex(projectName);
        RevisionsInput input = PayloadSerDes.parseRevisionsInput(httpServletRequest);
        // validate label and refName
        input.validate();
        applyObjectCommand.applyObjects(
            Project.nameKey(projectName),
            input.getRefName(),
            input.getRevisionsData(),
            input.getLabel(),
            input.getEventCreatedOn());

        projectCache.onCreateProject(Project.nameKey(projectName));
      }

      if (result) {
>>>>>>> Stashed changes
        setResponse(
            httpServletResponse,
            HttpServletResponse.SC_CREATED,
            "Project " + projectName + " initialized");
        return;
      }
    } catch (AuthException | PermissionBackendException e) {
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_FORBIDDEN,
          "User not authorized to create project " + projectName);
      return;
    }

    setResponse(
        httpServletResponse,
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        "Cannot initialize project " + projectName);
  }

<<<<<<< Updated upstream
  public boolean initProject(String projectName) throws AuthException, PermissionBackendException {
=======
  public boolean initProjectWithoutIndex(String projectName)
      throws AuthException, PermissionBackendException {
    return initProject(projectName, false);
  }

  public boolean initProject(String projectName) throws AuthException, PermissionBackendException {
    return initProject(projectName, true);
  }

  private boolean initProject(String projectName, boolean index)
      throws AuthException, PermissionBackendException {
>>>>>>> Stashed changes
    // When triggered internally(for example by consuming stream events) user is not provided
    // and internal user is returned. Project creation should be always allowed for internal user.
    if (!userProvider.get().isInternalUser()) {
      permissionBackend.user(userProvider.get()).check(GlobalPermission.CREATE_PROJECT);
    }
    Optional<URIish> maybeUri = gerritConfigOps.getGitRepositoryURI(projectName);
    if (!maybeUri.isPresent()) {
      logger.atSevere().log("Cannot initialize project '%s'", projectName);
      return false;
    }
    LocalFS localFS = new LocalFS(maybeUri.get());
    Project.NameKey projectNameKey = Project.NameKey.parse(projectName);
    if (localFS.createProject(projectNameKey, RefNames.HEAD)) {
      projectIndexer.index(projectNameKey);
      return true;
    }
    return false;
  }
}
