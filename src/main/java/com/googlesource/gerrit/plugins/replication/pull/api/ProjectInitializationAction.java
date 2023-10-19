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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.checkAcceptHeader;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.setResponse;
import static javax.servlet.http.HttpServletResponse.*;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.api.util.PayloadSerDes;
import java.io.IOException;
import java.util.Arrays;
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
  private final ApplyObjectCommand applyObjectCommand;
  private final ProjectCache projectCache;

  @Inject
  ProjectInitializationAction(
      GerritConfigOps gerritConfigOps,
      Provider<CurrentUser> userProvider,
      PermissionBackend permissionBackend,
      ProjectIndexer projectIndexer,
      ApplyObjectCommand applyObjectCommand,
      ProjectCache projectCache) {
    this.gerritConfigOps = gerritConfigOps;
    this.userProvider = userProvider;
    this.permissionBackend = permissionBackend;
    this.projectIndexer = projectIndexer;
    this.applyObjectCommand = applyObjectCommand;
    this.projectCache = projectCache;
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
      boolean initProjectStatus =
          (httpServletRequest.getContentLength() == 0)
              ? processRequest(projectName)
              : processRequestWithConfiguration(httpServletRequest, projectName);

      if (initProjectStatus) {
        setResponse(httpServletResponse, SC_CREATED, "Project " + projectName + " initialized");
        return;
      }

      setResponse(
          httpServletResponse,
          SC_INTERNAL_SERVER_ERROR,
          "Cannot initialize project " + projectName);
    } catch (Exception e) {
      handleException(httpServletResponse, e, projectName);
    }
  }

  public boolean initProject(String projectName) throws AuthException, PermissionBackendException {
    return initProject(projectName, true);
  }

  private boolean processRequest(String projectName)
      throws AuthException, PermissionBackendException {
    if (initProject(projectName)) {
      repLog.info("Init project API from {}", projectName);
      return true;
    }
    return false;
  }

  private boolean processRequestWithConfiguration(
      HttpServletRequest httpServletRequest, String projectName)
      throws AuthException, PermissionBackendException, IOException, BadRequestException,
          MissingParentObjectException, RefUpdateException {
    if (!initProjectWithoutIndex(projectName)) {
      return false;
    }

    RevisionsInput input = PayloadSerDes.parseRevisionsInput(httpServletRequest);
    validateInput(input);
    applyObjectCommand.applyObjects(
        Project.nameKey(projectName),
        input.getRefName(),
        input.getRevisionsData(),
        input.getLabel(),
        input.getEventCreatedOn());
    projectCache.onCreateProject(Project.nameKey(projectName));
    repLog.info(
        "Init project API from {} for {}:{} - {}",
        input.getLabel(),
        projectName,
        input.getRefName(),
        Arrays.toString(input.getRevisionsData()));
    return true;
  }

  private boolean initProjectWithoutIndex(String projectName)
      throws AuthException, PermissionBackendException {
    return initProject(projectName, false);
  }

  private boolean initProject(String projectName, boolean index)
      throws AuthException, PermissionBackendException {
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

  private void validateInput(RevisionsInput input) {
    if (Strings.isNullOrEmpty(input.getLabel())) {
      throw new IllegalArgumentException("Source label cannot be null or empty");
    }

    if (Strings.isNullOrEmpty(input.getRefName())) {
      throw new IllegalArgumentException("Ref-update refname cannot be null or empty");
    }
    input.validate();
  }

  private void handleException(HttpServletResponse response, Exception e, String projectName)
      throws IOException {
    repLog.error("Init Project API FAILED from {}", projectName);
    logger.atSevere().withCause(e).log("Exception while creating project %s", projectName);

    int statusCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    String errorMessage = "Internal Server Error";

    if (e instanceof BadRequestException || e instanceof IllegalArgumentException) {
      statusCode = HttpServletResponse.SC_BAD_REQUEST;
      errorMessage = "Invalid request payload";
    } else if (e instanceof RefUpdateException || e instanceof MissingParentObjectException) {
      statusCode = HttpServletResponse.SC_CONFLICT;
      errorMessage = e.getMessage();
    } else if (e instanceof AuthException || e instanceof PermissionBackendException) {
      statusCode = HttpServletResponse.SC_FORBIDDEN;
      errorMessage = "User not authorized to create project " + projectName;
    }

    setResponse(response, statusCode, errorMessage);
  }
}
