package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Config;

public class BearerTokenAuthenticationFilter extends AllRequestFilter {

  private final String pluginName;
  private final Optional<String> bearerToken;

  private final Pattern bearerTokenInAuthorizationHeader = Pattern.compile("^Bearer\\s(.+)$");

  @Inject
  BearerTokenAuthenticationFilter(
      @PluginName String pluginName, @GerritServerConfig Config gerritConfig) {
    this.pluginName = pluginName;
    this.bearerToken = Optional.ofNullable(gerritConfig.getString("bearerToken", null, "auth"));
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {

    if (!(servletRequest instanceof HttpServletRequest)
        || !(servletResponse instanceof HttpServletResponse)) {
      filterChain.doFilter(servletRequest, servletResponse);
      return;
    }

    final HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
    final HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
    final String requestURI = httpRequest.getRequestURI();

    if (requestURI.contains("/a/")) {
      filterChain.doFilter(servletRequest, servletResponse);
    } else if (requestURI.endsWith(String.format("/%s~apply-object", pluginName))
        || requestURI.endsWith(String.format("/%s~apply-objects", pluginName))
        || requestURI.endsWith(String.format("/%s~fetch", pluginName))
        || requestURI.endsWith(String.format("/%s~delete-project", pluginName))
        || requestURI.contains(String.format("/%s/init-project/", pluginName))
        || requestURI.matches(".*/projects/[^/]+/HEAD")) {

      if (bearerToken.isEmpty()) {
        httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        // todo set the body
      } else {
        final boolean isAuthenticated =
            Optional.ofNullable(httpRequest.getHeader("Authorization"))
                .map(bearerTokenHeader -> extractBearerTokenFromHeader(bearerTokenHeader))
                .map(bth -> bth.equals(bearerToken))
                .orElse(false);

        if (isAuthenticated) filterChain.doFilter(servletRequest, servletResponse);
        else httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // todo set the body
      }
    } else {
      filterChain.doFilter(servletRequest, servletResponse);
    }
  }

  private Optional<String> extractBearerTokenFromHeader(String bearerTokenHeader) {
    final Matcher projectGroupMatcher = bearerTokenInAuthorizationHeader.matcher(bearerTokenHeader);

    if (projectGroupMatcher.find()) {
      return Optional.ofNullable(projectGroupMatcher.group(1));
    }
    return Optional.empty();
  }
}
