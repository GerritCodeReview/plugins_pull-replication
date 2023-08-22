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

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
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
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import com.googlesource.gerrit.plugins.replication.pull.GerritConfigOps;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
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

  // HTTP PUT preserved for compatibility with non-patched pull-replication clients
  // and replaced with HTTP POST with the initial project config Git objects in the payload
  @Override
  @Deprecated
  protected void doPut(
      HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {

    if (!checkAcceptHeader(httpServletRequest, httpServletResponse)) {
      return;
    }

    String path = httpServletRequest.getRequestURI();
    String projectName = Url.decode(path.substring(path.lastIndexOf('/') + 1));
    try {
      if (initProject(projectName, true)) {
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

  @Override
  protected void doPost(
      HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {
    String path = httpServletRequest.getRequestURI();
    String projectName = Url.decode(path.substring(path.lastIndexOf('/') + 1));

    try {
      if (!initProject(projectName, false)) {
        setResponse(
            httpServletResponse,
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Cannot initialize project " + projectName);
      }

      RevisionsInput input =
          PullReplicationFilter.readJson(httpServletRequest, TypeLiteral.get(RevisionsInput.class));

      if (Strings.isNullOrEmpty(input.getLabel())) {
        throw new BadRequestException("Source label cannot be null or empty");
      }

      if (Strings.isNullOrEmpty(input.getRefName())) {
        throw new BadRequestException("Ref-update refname cannot be null or empty");
      }

      repLog.info(
          "Init project API from {} for {}:{} - {}",
          input.getLabel(),
          projectName,
          input.getRefName(),
          Arrays.toString(input.getRevisionsData()));

      try {
        input.validate();
      } catch (IllegalArgumentException e) {
        BadRequestException bre =
            new BadRequestException("Ref-update with invalid input: " + e.getMessage(), e);
        repLog.error(
            "Init project API *FAILED* from {} for {}:{} - {}",
            input.getLabel(),
            projectName,
            input.getRefName(),
            Arrays.toString(input.getRevisionsData()),
            bre);
        throw bre;
      }

      try {
        applyObjectCommand.applyObjects(
            Project.nameKey(projectName),
            input.getRefName(),
            input.getRevisionsData(),
            input.getLabel(),
            input.getEventCreatedOn());

        projectCache.onCreateProject(Project.nameKey(projectName));

        setResponse(
            httpServletResponse,
            HttpServletResponse.SC_CREATED,
            "Project " + projectName + " initialized");
      } catch (MissingParentObjectException e) {
        repLog.error(
            "Init project API *FAILED* from {} for {}:{} - {}",
            input.getLabel(),
            projectName,
            input.getRefName(),
            Arrays.toString(input.getRevisionsData()),
            e);
        throw new ResourceConflictException(e.getMessage(), e);
      } catch (RefUpdateException e) {
        repLog.error(
            "Apply object API *FAILED* from {} for {}:{} - {}",
            input.getLabel(),
            projectName,
            input.getRefName(),
            Arrays.toString(input.getRevisionsData()),
            e);
        throw new UnprocessableEntityException(e.getMessage());
      }
    } catch (AuthException | PermissionBackendException e) {
      logger.atSevere().withCause(e).log("User not authorized to create project %s", projectName);
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_FORBIDDEN,
          "User not authorized to create project " + projectName);
      return;
    } catch (BadRequestException e) {
      logger.atSevere().withCause(e).log(
          "Invalid request payload whilst creating project %s", projectName);
      setResponse(
          httpServletResponse, HttpServletResponse.SC_BAD_REQUEST, "Invalid request payload");
    } catch (ResourceConflictException | UnprocessableEntityException e) {
      setResponse(httpServletResponse, HttpServletResponse.SC_CONFLICT, e.getMessage());
    }
  }

  public boolean initProject(String projectName, boolean reindex)
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
      if (reindex) {
        projectIndexer.index(projectNameKey);
      }
      return true;
    }
    return false;
  }
}
