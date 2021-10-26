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
public class ProjectInitializationServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String PROJECT_NAME = "project-name";

  private final SitePaths sitePath;
  private final Config gerritConfig;
  private final Provider<CurrentUser> userProvider;

  @Inject
  ProjectInitializationServlet(
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

    Optional<URIish> maybeUri = getGitRepositoryURI(maybeProjectName.get());
    if (!maybeUri.isPresent()) {
      setResponse(
          httpServletResponse, HttpServletResponse.SC_BAD_REQUEST, "Invalid Git repository path");

      return;
    }

    LocalFS localFS = new LocalFS(maybeUri.get());

    Project.NameKey projectNameKey = Project.NameKey.parse(maybeProjectName.get());
    if (localFS.createProject(projectNameKey, RefNames.HEAD)) {
      setResponse(
          httpServletResponse,
          HttpServletResponse.SC_OK,
          "Project " + maybeProjectName.get() + " initialized");
    }
  }

  private Optional<URIish> getGitRepositoryURI(String projectName) {
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
        "/a/plugins/%s/init-project?project-name=%s", pluginName, projectName + ".git");
  }
}
