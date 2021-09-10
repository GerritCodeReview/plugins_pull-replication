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
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.TopLevelResource;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
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
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class PullReplicationFilter extends AllRequestFilter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private FetchAction fetchAction;
  private ApplyObjectAction applyObjectAction;
  private ProjectsCollection projectsCollection;
  private Gson gson;
  private Provider<CurrentUser> userProvider;

  @Inject
  public PullReplicationFilter(
      FetchAction fetchAction,
      ApplyObjectAction applyObjectAction,
      ProjectsCollection projectsCollection,
      Provider<CurrentUser> userProvider) {
    this.fetchAction = fetchAction;
    this.applyObjectAction = applyObjectAction;
    this.projectsCollection = projectsCollection;
    this.userProvider = userProvider;
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
        if (userProvider.get().isIdentifiedUser()) {
          doFetch(httpRequest, httpResponse);
        } else {
          httpResponse.sendError(SC_UNAUTHORIZED);
        }
      }

      if (isApplyObjectAction(httpRequest)) {
        if (userProvider.get().isIdentifiedUser()) {
          doApplyObject(httpRequest, httpResponse);
        } else {
          httpResponse.sendError(SC_UNAUTHORIZED);
        }
      }

      chain.doFilter(request, response);

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
    } catch (Exception e) {
      throw new ServletException(e);
    }
  }

  private void doApplyObject(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
      throws RestApiException, IOException, PermissionBackendException {
    RevisionInput input = readJson(httpRequest, TypeLiteral.get(RevisionInput.class));
    IdString id = getProjectName(httpRequest);
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    @SuppressWarnings("unchecked")
    Response<Map<String, Object>> response =
        (Response<Map<String, Object>>) applyObjectAction.apply(projectResource, input);
    String responseJson = gson.toJson(response);
    writeResponse(httpResponse, response, responseJson);
  }

  private void doFetch(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
      throws IOException, RestApiException, PermissionBackendException {
    Input input = readJson(httpRequest, TypeLiteral.get(Input.class));
    IdString id = getProjectName(httpRequest);
    ProjectResource projectResource = projectsCollection.parse(TopLevelResource.INSTANCE, id);

    @SuppressWarnings("unchecked")
    Response<Map<String, Object>> response =
        (Response<Map<String, Object>>) fetchAction.apply(projectResource, input);
    String responseJson = gson.toJson(response);
    writeResponse(httpResponse, response, responseJson);
  }

  private IdString getProjectName(HttpServletRequest httpRequest) {
    return splitPath(httpRequest).get(3);
  }

  private void writeResponse(
      HttpServletResponse httpResponse, Response<Map<String, Object>> response, String responseJson)
      throws IOException {
    if (response.statusCode() == HttpServletResponse.SC_OK
        || response.statusCode() == HttpServletResponse.SC_CREATED) {
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
        }
      }
    }
  }

  private static List<IdString> splitPath(HttpServletRequest req) {
    String path = req.getRequestURI();
    if (Strings.isNullOrEmpty(path)) {
      return Collections.emptyList();
    }
    List<IdString> out = new ArrayList<>();
    for (String p : Splitter.on('/').split(path)) {
      out.add(IdString.fromUrl(p));
    }
    if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
      out.remove(out.size() - 1);
    }
    return out;
  }

  private boolean isApplyObjectAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith("pull-replication~apply-object");
  }

  private boolean isFetchAction(HttpServletRequest httpRequest) {
    return httpRequest.getRequestURI().endsWith("pull-replication~fetch");
  }
}
