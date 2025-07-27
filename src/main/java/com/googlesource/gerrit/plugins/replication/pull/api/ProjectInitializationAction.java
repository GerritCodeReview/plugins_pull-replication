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
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.index.project.ProjectIndexer;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.extensions.events.AbstractNoNotifyEvent;
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
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingLatestPatchSetException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import com.googlesource.gerrit.plugins.replication.pull.api.util.PayloadSerDes;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
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
  private final DynamicSet<NewProjectCreatedListener> newProjectCreatedListeners;

  @Inject
  ProjectInitializationAction(
      GerritConfigOps gerritConfigOps,
      Provider<CurrentUser> userProvider,
      PermissionBackend permissionBackend,
      ProjectIndexer projectIndexer,
      ApplyObjectCommand applyObjectCommand,
      ProjectCache projectCache,
      DynamicSet<NewProjectCreatedListener> newProjectCreatedListeners) {
    this.gerritConfigOps = gerritConfigOps;
    this.userProvider = userProvider;
    this.permissionBackend = permissionBackend;
    this.projectIndexer = projectIndexer;
    this.applyObjectCommand = applyObjectCommand;
    this.projectCache = projectCache;
    this.newProjectCreatedListeners = newProjectCreatedListeners;
  }

  @Override
  protected void doPut(
      HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {

    if (!checkAcceptHeader(httpServletRequest, httpServletResponse)) {
      return;
    }

    String gitRepositoryName = getGitRepositoryName(httpServletRequest);
    String headName = getGitRepositoryHeadName(httpServletRequest);
    try {
      boolean initProjectStatus;
      String contentType = httpServletRequest.getContentType();
      if (checkContentType(contentType, MediaType.JSON_UTF_8)) {
        // init project request includes project configuration in JSON format.
        initProjectStatus =
            initProjectWithConfiguration(httpServletRequest, gitRepositoryName, headName);
      } else if (checkContentType(contentType, MediaType.PLAIN_TEXT_UTF_8)) {
        // init project request does not include project configuration.
        initProjectStatus = initProject(gitRepositoryName, RefNames.HEAD);
      } else {
        setResponse(
            httpServletResponse,
            SC_BAD_REQUEST,
            String.format(
                "Invalid Content Type. Only %s or %s is supported.",
                MediaType.JSON_UTF_8.toString(), MediaType.PLAIN_TEXT_UTF_8.toString()));
        return;
      }

      if (initProjectStatus) {
        setResponse(
            httpServletResponse,
            HttpServletResponse.SC_CREATED,
            "Project " + gitRepositoryName + " initialized");
        return;
      }

      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Cannot initialize project " + gitRepositoryName);
    } catch (BadRequestException | IllegalArgumentException e) {
      logExceptionAndUpdateResponse(httpServletResponse, e, SC_BAD_REQUEST, gitRepositoryName);
    } catch (RefUpdateException | MissingParentObjectException e) {
      logExceptionAndUpdateResponse(httpServletResponse, e, SC_CONFLICT, gitRepositoryName);
    } catch (ResourceNotFoundException e) {
      logExceptionAndUpdateResponse(
          httpServletResponse, e, SC_INTERNAL_SERVER_ERROR, gitRepositoryName);
    } catch (AuthException | PermissionBackendException e) {
      logExceptionAndUpdateResponse(httpServletResponse, e, SC_FORBIDDEN, gitRepositoryName);
    }
  }

  public boolean initProject(String gitRepositoryName, String headName)
      throws AuthException, PermissionBackendException, IOException {
    if (initProject(gitRepositoryName, headName, true)) {
      repLog.info("Init project API from {}", gitRepositoryName);
      return true;
    }
    return false;
  }

  private boolean initProjectWithConfiguration(
      HttpServletRequest httpServletRequest, String gitRepositoryName, String headName)
      throws AuthException,
          PermissionBackendException,
          IOException,
          BadRequestException,
          MissingParentObjectException,
          RefUpdateException,
          ResourceNotFoundException {

    RevisionsInput input = PayloadSerDes.parseRevisionsInput(httpServletRequest);
    validateInput(input);
    if (!initProject(gitRepositoryName, headName, false)) {
      return false;
    }

    String projectName = gitRepositoryName.replace(".git", "");
    if (input.getRevisionsData() != null && input.getRevisionsData().length > 0) {
      try {
        applyObjectCommand.applyObjects(
            Project.nameKey(projectName),
            input.getRefName(),
            input.getRevisionsData(),
            input.getLabel(),
            input.getEventCreatedOn());
      } catch (MissingLatestPatchSetException e) {
        repLog.error(
            "Init project API FAILED from {} for {} - configuration data cannot contain change meta"
                + " refs: {}:{}",
            input.getLabel(),
            projectName,
            input.getRefName(),
            Arrays.toString(input.getRevisionsData()),
            e);
        throw new BadRequestException("Configuration data cannot contain change meta refs", e);
      }
    }
    projectCache.onCreateProject(Project.nameKey(projectName));
    // In case pull-replication is used in conjunction with multi-site, by convention the remote
    // label is populated with the instanceId. That's why we are passing input.getLabel()
    // to the Event to notify
    notifyListenersOfNewProjectCreation(projectName, input.getLabel());
    repLog.info(
        "Init project API from {} for {}:{} - {}",
        input.getLabel(),
        projectName,
        input.getRefName(),
        Arrays.toString(input.getRevisionsData()));
    return true;
  }

  private boolean initProject(
      String gitRepositoryName, String headName, boolean needsProjectReindexing)
      throws AuthException, PermissionBackendException, IOException {
    // When triggered internally(for example by consuming stream events) user is not provided
    // and internal user is returned. Project creation should be always allowed for internal user.
    if (!userProvider.get().isInternalUser()) {
      permissionBackend.user(userProvider.get()).check(GlobalPermission.CREATE_PROJECT);
    }
    Optional<URIish> maybeUri = gerritConfigOps.getGitRepositoryURI(gitRepositoryName);
    if (!maybeUri.isPresent()) {
      logger.atSevere().log("Cannot initialize project '%s'", gitRepositoryName);
      return false;
    }
    LocalFS localFS = new LocalFS(maybeUri.get());
    Project.NameKey projectNameKey = Project.NameKey.parse(gitRepositoryName);
    if (localFS.createProject(projectNameKey, headName)) {
      if (needsProjectReindexing) {
        projectCache.onCreateProject(projectNameKey);
      }
      return true;
    }
    return false;
  }

  private void validateInput(RevisionsInput input) {

    if (Strings.isNullOrEmpty(input.getLabel())) {
      throw new IllegalArgumentException("Source label cannot be null or empty");
    }

    if (!Objects.equals(input.getRefName(), RefNames.REFS_CONFIG)) {
      throw new IllegalArgumentException(
          String.format("Ref-update refname should be %s", RefNames.REFS_CONFIG));
    }
    input.validate();
  }

  private String getGitRepositoryName(HttpServletRequest httpServletRequest) {
    String path = httpServletRequest.getRequestURI();
    return Url.decode(path.substring(path.lastIndexOf('/') + 1));
  }

  private String getGitRepositoryHeadName(HttpServletRequest httpServletRequest) {
    String headName = httpServletRequest.getParameter("headName");
    return MoreObjects.firstNonNull(headName, RefNames.HEAD);
  }

  private void logExceptionAndUpdateResponse(
      HttpServletResponse httpServletResponse,
      Exception e,
      int statusCode,
      String gitRepositoryName)
      throws IOException {
    repLog.error("Init Project API FAILED for {}", gitRepositoryName, e);
    setResponse(httpServletResponse, statusCode, e.getMessage());
  }

  private boolean checkContentType(String contentType, MediaType mediaType) {
    try {
      return MediaType.parse(contentType).is(mediaType);
    } catch (Exception e) {
      return false;
    }
  }

  private void notifyListenersOfNewProjectCreation(String projectName, String instanceId) {
    NewProjectCreatedListener.Event newProjectCreatedEvent =
        new Event(RefNames.REFS_CONFIG, projectName, instanceId);
    newProjectCreatedListeners.forEach(l -> l.onNewProjectCreated(newProjectCreatedEvent));
  }

  private static class Event extends AbstractNoNotifyEvent
      implements NewProjectCreatedListener.Event {
    private final String headName;
    private final String projectName;
    private final String instanceId;

    public Event(String headName, String projectName, String maybeInstanceId) {
      this.headName = headName;
      this.projectName = projectName;
      this.instanceId = maybeInstanceId;
    }

    @Override
    public String getHeadName() {
      return headName;
    }

    @Override
    public String getProjectName() {
      return projectName;
    }

    @Override
    public String getInstanceId() {
      return instanceId;
    }
  }
}
