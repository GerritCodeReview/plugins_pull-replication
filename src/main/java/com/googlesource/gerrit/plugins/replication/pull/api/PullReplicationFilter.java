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

import static com.google.gerrit.httpd.restapi.RestApiServlet.SC_UNPROCESSABLE_ENTITY;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.checkAcceptHeader;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.setResponse;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.flogger.FluentLogger;
import com.google.common.reflect.TypeToken;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.httpd.restapi.RestApiServlet;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.project.ProjectState;
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
import com.googlesource.gerrit.plugins.replication.pull.api.exception.UnauthorizedAuthException;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class PullReplicationFilter extends AllRequestFilter implements PullReplicationEndpoints {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Pattern projectNameInGerritUrl = Pattern.compile(".*/projects/([^/]+)/.*");
  private static final Pattern projectNameInitProjectUrl =
      Pattern.compile(".*/init-project/([^/]+.git)");

  private FetchAction fetchAction;
  private ApplyObjectAction applyObjectAction;
  private ApplyObjectsAction applyObjectsAction;
  private BatchApplyObjectAction batchApplyObjectAction;
  private ProjectInitializationAction projectInitializationAction;
  private UpdateHeadAction updateHEADAction;
  private ProjectDeletionAction projectDeletionAction;
  private ProjectCache projectCache;
  private Gson gson;
  private String pluginName;
  private final Provider<CurrentUser> currentUserProvider;

  @Inject
  public PullReplicationFilter(
      FetchAction fetchAction,
      ApplyObjectAction applyObjectAction,
      ApplyObjectsAction applyObjectsAction,
      BatchApplyObjectAction batchApplyObjectAction,
      ProjectInitializationAction projectInitializationAction,
      UpdateHeadAction updateHEADAction,
      ProjectDeletionAction projectDeletionAction,
      ProjectCache projectCache,
      @PluginName String pluginName,
      Provider<CurrentUser> currentUserProvider) {
    this.fetchAction = fetchAction;
    this.applyObjectAction = applyObjectAction;
    this.applyObjectsAction = applyObjectsAction;
    this.batchApplyObjectAction = batchApplyObjectAction;
    this.projectInitializationAction = projectInitializationAction;
    this.updateHEADAction = updateHEADAction;
    this.projectDeletionAction = projectDeletionAction;
    this.projectCache = projectCache;
    this.pluginName = pluginName;
    this.gson = OutputFormat.JSON.newGsonBuilder().create();
    this.currentUserProvider = currentUserProvider;
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
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doFetch(httpRequest));
      } else if (isApplyObjectAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doApplyObject(httpRequest));
      } else if (isApplyObjectsAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doApplyObjects(httpRequest));
      } else if (isBatchApplyObjectsAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doBatchApplyObject(httpRequest));
      } else if (isInitProjectAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        if (!checkAcceptHeader(httpRequest, httpResponse)) {
          return;
        }
        doInitProject(httpRequest, httpResponse);
      } else if (isUpdateHEADAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doUpdateHEAD(httpRequest));
      } else if (isDeleteProjectAction(httpRequest)) {
        failIfcurrentUserIsAnonymous();
        writeResponse(httpResponse, doDeleteProject(httpRequest));
      } else {
        chain.doFilter(request, response);
      }

    } catch (UnauthorizedAuthException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_UNAUTHORIZED, e.getMessage(), e.caching(), e);
    } catch (AuthException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_FORBIDDEN, e.getMessage(), e.caching(), e);
    } catch (MalformedJsonException | JsonParseException | IllegalArgumentException e) {
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
    } catch (InitProjectException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_INTERNAL_SERVER_ERROR, e.getMessage(), e.caching(), e);
    } catch (ResourceNotFoundException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_NOT_FOUND, e.getMessage(), e.caching(), e);
    } catch (NoSuchElementException e) {
      RestApiServlet.replyError(
          httpRequest, httpResponse, SC_BAD_REQUEST, "Project name not present in the url", e);
    } catch (Exception e) {
      if (e instanceof IllegalArgumentException
          || e.getCause() instanceof IllegalArgumentException) {
        RestApiServlet.replyError(
            httpRequest, httpResponse, SC_BAD_REQUEST, "Invalid repository path in request", e);
      } else {
        throw new ServletException(e);
      }
    }
  }

  private void failIfcurrentUserIsAnonymous() throws UnauthorizedAuthException {
    CurrentUser currentUser = currentUserProvider.get();
    if (currentUser instanceof AnonymousUser) {
      throw new UnauthorizedAuthException();
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
  private Response<String> doApplyObject(HttpServletRequest httpRequest)
      throws RestApiException, IOException, PermissionBackendException {
    RevisionInput input = readJson(httpRequest, TypeLiteral.get(RevisionInput.class).getType());
    IdString id = getProjectName(httpRequest).get();

    return (Response<String>) applyObjectAction.apply(parseProjectResource(id), input);
  }

  @SuppressWarnings("unchecked")
  private Response<String> doApplyObjects(HttpServletRequest httpRequest)
      throws RestApiException, IOException, PermissionBackendException {
    RevisionsInput input = readJson(httpRequest, TypeLiteral.get(RevisionsInput.class).getType());
    IdString id = getProjectName(httpRequest).get();

    return (Response<String>) applyObjectsAction.apply(parseProjectResource(id), input);
  }

  @SuppressWarnings("unchecked")
  private Response<Map<String, Object>> doBatchApplyObject(HttpServletRequest httpRequest)
      throws RestApiException, IOException, PermissionBackendException {
    TypeToken<List<RevisionInput>> collectionType = new TypeToken<>() {};
    List<RevisionInput> inputs = readJson(httpRequest, collectionType.getType());
    IdString id = getProjectName(httpRequest).get();

    return (Response<Map<String, Object>>)
        batchApplyObjectAction.apply(parseProjectResource(id), inputs);
  }

  @SuppressWarnings("unchecked")
  private Response<String> doUpdateHEAD(HttpServletRequest httpRequest) throws Exception {
    HeadInput input = readJson(httpRequest, TypeLiteral.get(HeadInput.class).getType());
    IdString id = getProjectName(httpRequest).get();

    return (Response<String>) updateHEADAction.apply(parseProjectResource(id), input);
  }

  @SuppressWarnings("unchecked")
  private Response<String> doDeleteProject(HttpServletRequest httpRequest) throws Exception {
    IdString id = getProjectName(httpRequest).get();
    return (Response<String>)
        projectDeletionAction.apply(
            parseProjectResource(id), new ProjectDeletionAction.DeleteInput());
  }

  @SuppressWarnings("unchecked")
  private Response<Map<String, Object>> doFetch(HttpServletRequest httpRequest)
      throws IOException, RestApiException, PermissionBackendException {
    Input input = readJson(httpRequest, TypeLiteral.get(Input.class).getType());
    IdString id = getProjectName(httpRequest).get();

    return (Response<Map<String, Object>>) fetchAction.apply(parseProjectResource(id), input);
  }

  private ProjectResource parseProjectResource(IdString id) throws ResourceNotFoundException {
    Optional<ProjectState> project = projectCache.get(Project.nameKey(id.get()));
    if (project.isEmpty()) {
      throw new ResourceNotFoundException(id);
    }
    return new ProjectResource(project.get(), currentUserProvider.get());
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

  private <T> T readJson(HttpServletRequest httpRequest, Type typeToken)
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

        return gson.fromJson(json, typeToken);
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
    return httpRequest
        .getRequestURI()
        .endsWith(String.format("/%s~" + APPLY_OBJECT_API_ENDPOINT, pluginName));
  }

  private boolean isApplyObjectsAction(HttpServletRequest httpRequest) {
    return httpRequest
        .getRequestURI()
        .endsWith(String.format("/%s~" + APPLY_OBJECTS_API_ENDPOINT, pluginName));
  }

  private boolean isBatchApplyObjectsAction(HttpServletRequest httpRequest) {
    return httpRequest
        .getRequestURI()
        .endsWith(String.format("/%s~" + BATCH_APPLY_OBJECT_API_ENDPOINT, pluginName));
  }

  private boolean isFetchAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith(String.format("/%s~" + FETCH_ENDPOINT, pluginName));
  }

  private boolean isInitProjectAction(HttpServletRequest httpRequest) {
    return httpRequest
        .getRequestURI()
        .contains(String.format("/%s/" + INIT_PROJECT_ENDPOINT + "/", pluginName));
  }

  private boolean isUpdateHEADAction(HttpServletRequest httpRequest) {
    return httpRequest
            .getRequestURI()
            .matches(String.format(".*/projects/[^/]+/%s~HEAD", pluginName))
        && "PUT".equals(httpRequest.getMethod());
  }

  private boolean isDeleteProjectAction(HttpServletRequest httpRequest) {
    return httpRequest
            .getRequestURI()
            .endsWith(String.format("/%s~" + DELETE_PROJECT_ENDPOINT, pluginName))
        && "DELETE".equals(httpRequest.getMethod());
  }
}
