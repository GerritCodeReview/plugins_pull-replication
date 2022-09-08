package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.inject.Inject;
import com.google.inject.name.Named;
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

/**
 * Authenticates the current user by HTTP bearer token authentication.
 *
 *  * @see <a href="https://www.rfc-editor.org/rfc/rfc6750">RFC 6750</a>
 */
public class BearerAuthenticationFilter extends AllRequestFilter {

  private final String pluginName;
  private final String bearerToken;
  private final Pattern bearerTokenRegex = Pattern.compile("^Bearer\\s(.+)$");

  @Inject
  BearerAuthenticationFilter(
      @PluginName String pluginName, @Named("BearerToken") String bearerToken) {
    this.pluginName = pluginName;
    this.bearerToken = bearerToken;
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

    if (isBasicAuthenticationRequest(requestURI)) {
      filterChain.doFilter(servletRequest, servletResponse);
    } else if (isPullReplicationAPiRequest(requestURI)) {
      final Optional<String> authorizationHeader =
          Optional.ofNullable(httpRequest.getHeader("Authorization"));
      if (authorizationHeader.isEmpty())
        httpResponse.sendError(
            HttpServletResponse.SC_UNAUTHORIZED,
            "Bearer token authentication not provided in the request");
      else {
        final boolean isAuthenticated =
            authorizationHeader
                .flatMap(ah -> extractBearerToken(ah))
                .map(bt -> bt.equals(bearerToken))
                .orElse(false);

        if (isAuthenticated) filterChain.doFilter(servletRequest, servletResponse);
        else httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Bearer token");
      }
    } else {
      filterChain.doFilter(servletRequest, servletResponse);
    }
  }

  private boolean isBasicAuthenticationRequest(String requestURI) {
    return requestURI.contains("/a/");
  }

  private boolean isPullReplicationAPiRequest(String requestURI) {
    return requestURI.endsWith(String.format("/%s~apply-object", pluginName))
        || requestURI.endsWith(String.format("/%s~apply-objects", pluginName))
        || requestURI.endsWith(String.format("/%s~fetch", pluginName))
        || requestURI.endsWith(String.format("/%s~delete-project", pluginName))
        || requestURI.contains(String.format("/%s/init-project/", pluginName))
        || requestURI.matches(".*/projects/[^/]+/HEAD");
  }

  private Optional<String> extractBearerToken(String authorizationHeader) {
    final Matcher projectGroupMatcher = bearerTokenRegex.matcher(authorizationHeader);

    if (projectGroupMatcher.find()) {
      return Optional.ofNullable(projectGroupMatcher.group(1));
    }
    return Optional.empty();
  }
}
