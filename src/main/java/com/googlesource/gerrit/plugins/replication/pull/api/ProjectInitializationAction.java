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

import static com.googlesource.gerrit.plugins.replication.pull.api.FetchApiCapability.CALL_FETCH_ACTION;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.checkAcceptHeader;
import static com.googlesource.gerrit.plugins.replication.pull.api.HttpServletOps.setResponse;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.LocalFS;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class ProjectInitializationAction extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PROJECT_NAME = "project-name";

  private final SitePaths sitePath;
  private final Config gerritConfig;
  private final Provider<CurrentUser> userProvider;

  @Inject
  ProjectInitializationAction(
      @GerritServerConfig Config cfg, SitePaths sitePath, Provider<CurrentUser> userProvider) {
    this.sitePath = sitePath;
    this.gerritConfig = cfg;
    this.userProvider = userProvider;
  }

  @Override
  protected void doPut(
      HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
      throws ServletException, IOException {

    if (!checkAcceptHeader(httpServletRequest, httpServletResponse)) {
      return;
    }

    if (!userProvider.get().isIdentifiedUser()) {
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_UNAUTHORIZED,
          "Unauthorized user. '" + CALL_FETCH_ACTION + "' capability needed.");
      return;
    }

    Optional<String> maybeProjectName =
        Optional.ofNullable(httpServletRequest.getParameter(PROJECT_NAME));

    if (!maybeProjectName.isPresent()) {
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_BAD_REQUEST,
          "Missing parameter 'project-name' in request");

      return;
    }

    if (initProject(maybeProjectName.get())) {
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_OK,
          "Project " + maybeProjectName.get() + " initialized");
      return;
    }

    setResponse(
        httpServletResponse,
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        "Cannot initialize project " + maybeProjectName.get());
  }

  protected boolean initProject(String projectName) {
    Optional<URIish> maybeUri = getGitRepositoryURI(projectName);
    if (!maybeUri.isPresent()) {
      return false;
    }
    LocalFS localFS = new LocalFS(maybeUri.get());
    Project.NameKey projectNameKey = Project.NameKey.parse(projectName);
    return localFS.createProject(projectNameKey, RefNames.HEAD);
  }

  protected Optional<URIish> getGitRepositoryURI(String projectName) {
    Path basePath = sitePath.resolve(gerritConfig.getString("gerrit", null, "basePath"));
    URIish uri;

    try {
      uri = new URIish("file://" + basePath + "/" + projectName);
      return Optional.of(uri);
    } catch (URISyntaxException e) {
      logger.atSevere().withCause(e).log("Unsupported URI for project " + projectName);
    }

    return Optional.empty();
  }

  public static String getProjectInitializationUrl(String pluginName, String projectName) {
    return String.format(
        "a/plugins/%s/init-project?project-name=%s", pluginName, projectName + ".git");
  }
}
