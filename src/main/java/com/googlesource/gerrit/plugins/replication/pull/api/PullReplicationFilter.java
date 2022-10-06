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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.httpd.restapi.RestApiServlet;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.restapi.project.ProjectsCollection;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.Input;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.InitProjectException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.gerrit.httpd.restapi.RestApiServlet.SC_UNPROCESSABLE_ENTITY;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.checkAcceptHeader;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.setResponse;
import static javax.servlet.http.HttpServletResponse.*;

public class PullReplicationFilter extends AllRequestFilter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Pattern projectNameInGerritUrl = Pattern.compile(".*/projects/([^/]+)/.*");
  private static final Pattern projectNameInitProjectUrl =
      Pattern.compile(".*/init-project/([^/]+.git)");

  private FetchAction fetchAction;
  private ApplyObjectAction applyObjectAction;
  private ApplyObjectsAction applyObjectsAction;
  private ProjectInitializationAction projectInitializationAction;
  private UpdateHeadAction updateHEADAction;
  private ProjectDeletionAction projectDeletionAction;
  private ProjectsCollection projectsCollection;
  private Gson gson;
  private String pluginName;

  @Inject
  public PullReplicationFilter(
      FetchAction fetchAction,
      ApplyObjectAction applyObjectAction,
      ApplyObjectsAction applyObjectsAction,
      ProjectInitializationAction projectInitializationAction,
      UpdateHeadAction updateHEADAction,
      ProjectDeletionAction projectDeletionAction,
      ProjectsCollection projectsCollection,
      @PluginName String pluginName) {
    this.fetchAction = fetchAction;
    this.applyObjectAction = applyObjectAction;
    this.applyObjectsAction = applyObjectsAction;
    this.projectInitializationAction = projectInitializationAction;
    this.updateHEADAction = updateHEADAction;
    this.projectDeletionAction = projectDeletionAction;
    this.projectsCollection = projectsCollection;
    this.pluginName = pluginName;
    this.gson = OutputFormat.JSON.newGsonBuilder().create();
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }

    HttpServletResponse httpResponse = (HttpServletResponse) response;
    HttpServletRequest httpRequest = (HttpServletRequest) request;
    try {
      if (isFetchAction(httpRequest)) {
        writeResponse(httpResponse, doFetch(httpRequest));
      } else if (isApplyObjectAction(httpRequest)) {
        writeResponse(httpResponse, doApplyObject(httpRequest));
      } else if (isApplyObjectsAction(httpRequest)) {
        writeResponse(httpResponse, doApplyObjects(httpRequest));
      } else if (isInitProjectAction(httpRequest)) {
        if (!checkAcceptHeader(httpRequest, httpResponse)) {
          return;
        }
        doInitProject(httpRequest, httpResponse);
      } else if (isUpdateHEADAction(httpRequest)) {
        writeResponse(httpResponse, doUpdateHEAD(httpRequest));
      } else if (isDeleteProjectAction(httpRequest)) {
        writeResponse(httpResponse, doDeleteProject(httpRequest));
      } else {
        chain.doFilter(request, response);
      }

    } catch (AuthException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_FORBIDDEN, e.getMessage(), e.caching(), e);
    } catch (MalformedJsonException | JsonParseException e) {
      logger.atFine().withCause(e).log("REST call failed on JSON parsing");
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_BAD_REQUEST, "Invalid json in request", e);
    } catch (BadRequestException e) {
      RestApiServlet.replyError(httpRequest, httpResponse, SC_BAD_REQUEST, e.getMessage(), e);
    } catch (UnprocessableEntityException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_UNPROCESSABLE_ENTITY, e.getMessage(), e.caching(), e);
    } catch (ResourceConflictException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_CONFLICT, e.getMessage(), e.caching(), e);
    } catch (InitProjectException | ResourceNotFoundException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_INTERNAL_SERVER_ERROR, e.getMessage(), e.caching(), e);
    } catch (NoSuchElementException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_BAD_REQUEST, "Project name not present in the url", e);
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  private void doInitProject(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
      throws RestApiException, IOException, PermissionBackendException {

    IdString id = getInitProjectName(httpRequest).get();
    String projectName = id.get();
    if (projectInitializationAction.initProject(projectName)) {
      setResponse(
          httpResponse, HttpServletResponse.SC_CREATED, "Project " + projectName + " initialized");
      return;
    }
    throw new InitProjectException(projectName);
  }

  @SuppressWarnings("unchecked")
  private Response<Map<String, Object>> doApplyObject(HttpServletRequest httpRequest)
      throws RestApiException, IOException, PermissionBackendException {
    RevisionInput input = readJson(httpRequest, TypeLiteral.get(RevisionInput.class));
    IdString id = getProjectName(httpRequest).get();
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    return (Response<Map<String, Object>>) applyObjectAction.apply(projectResource, input);
  }

  @SuppressWarnings("unchecked")
  private Response<Map<String, Object>> doApplyObjects(HttpServletRequest httpRequest)
      throws RestApiException, IOException, PermissionBackendException {
    RevisionsInput input = readJson(httpRequest, TypeLiteral.get(RevisionsInput.class));
    IdString id = getProjectName(httpRequest).get();
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    return (Response<Map<String, Object>>) applyObjectsAction.apply(projectResource, input);
  }

  @SuppressWarnings("unchecked")
  private Response<String> doUpdateHEAD(HttpServletRequest httpRequest) throws Exception {
    HeadInput input = readJson(httpRequest, TypeLiteral.get(HeadInput.class));
    IdString id = getProjectName(httpRequest).get();
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    return (Response<String>) updateHEADAction.apply(projectResource, input);
  }

  @SuppressWarnings("unchecked")
  private Response<String> doDeleteProject(HttpServletRequest httpRequest) throws Exception {
    IdString id = getProjectName(httpRequest).get();
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);
    return (Response<String>)
        projectDeletionAction.apply(projectResource, new ProjectDeletionAction.DeleteInput());
  }

  @SuppressWarnings("unchecked")
  private Response<Map<String, Object>> doFetch(HttpServletRequest httpRequest)
      throws IOException, RestApiException, PermissionBackendException {
    Input input = readJson(httpRequest, TypeLiteral.get(Input.class));
    IdString id = getProjectName(httpRequest).get();
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    return (Response<Map<String, Object>>) fetchAction.apply(projectResource, input);
  }

  private <T> void writeResponse(HttpServletResponse httpResponse, Response<T> response)
      throws IOException {
    String responseJson = gson.toJson(response);
    if (response.statusCode() == SC_OK || response.statusCode() == SC_CREATED) {

      httpResponse.setContentType("application/json");
      httpResponse.setStatus(response.statusCode());
      PrintWriter writer = httpResponse.getWriter();
      writer.print(new String(RestApiServlet.JSON_MAGIC));
      writer.print(responseJson);
    } else {
      httpResponse.sendError(response.statusCode(), responseJson);
    }
  }

  private <T> T readJson(HttpServletRequest httpRequest, TypeLiteral<T> typeLiteral)
      throws IOException, BadRequestException {

    try (BufferedReader br = httpRequest.getReader();
        JsonReader json = new JsonReader(br)) {
      try {
        json.setLenient(true);

        try {
          json.peek();
        } catch (EOFException e) {
          throw new BadRequestException("Expected JSON object", e);
        }

        return gson.fromJson(json, typeLiteral.getType());
      } finally {
        try {
          // Reader.close won't consume the rest of the input. Explicitly consume the request
          // body.
          br.skip(Long.MAX_VALUE);
        } catch (Exception e) {
          // ignore, e.g. trying to consume the rest of the input may fail if the request was
          // cancelled
          logger.atFine().withCause(e).log("Exception during the parsing of the request json");
        }
      }
    }
  }

  /**
   * Return project name from request URI. Request URI format:
   * /a/projects/<project_name>/pull-replication~apply-object
   *
   * @param req
   * @return project name
   */
  private Optional<IdString> getInitProjectName(HttpServletRequest req) {
    return extractProjectName(req, projectNameInitProjectUrl);
  }

  private Optional<IdString> getProjectName(HttpServletRequest req) {
    return extractProjectName(req, projectNameInGerritUrl);
  }

  private Optional<IdString> extractProjectName(HttpServletRequest req, Pattern urlPattern) {
    String path = req.getRequestURI();
    Matcher projectGroupMatcher = urlPattern.matcher(path);

    if (projectGroupMatcher.find()) {
      return Optional.of(IdString.fromUrl(projectGroupMatcher.group(1)));
    }

    return Optional.empty();
  }

  private boolean isApplyObjectAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith(String.format("/%s~apply-object", pluginName));
  }

  private boolean isApplyObjectsAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith(String.format("/%s~apply-objects", pluginName));
  }

  private boolean isFetchAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith(String.format("/%s~fetch", pluginName));
  }

  private boolean isInitProjectAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().contains(String.format("/%s/init-project/", pluginName));
  }

  private boolean isUpdateHEADAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().matches(".*/projects/[^/]+/HEAD")
        && "PUT".equals(httpRequest.getMethod());
  }

  private boolean isDeleteProjectAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith(String.format("/%s~delete-project", pluginName))
        && "DELETE".equals(httpRequest.getMethod());
  }
}
