package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
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
 * <p>* @see <a href="https://www.rfc-editor.org/rfc/rfc6750">RFC 6750</a>
 */
public class BearerAuthenticationFilter extends AllRequestFilter {

  private final DynamicItem<WebSession> session;
  private final String pluginName;
  private final Provider<PluginUser> pluginUserProvider;
  private final Provider<ThreadLocalRequestContext> threadLocalRequestContext;
  private final String bearerToken;
  private final Pattern bearerTokenRegex = Pattern.compile("^Bearer\\s(.+)$");

  @Inject
  BearerAuthenticationFilter(
      DynamicItem<WebSession> session,
      @PluginName String pluginName,
      Provider<PluginUser> pluginUserProvider,
      Provider<ThreadLocalRequestContext> threadLocalRequestContext,
      @Named("BearerToken") String bearerToken) {
    this.session = session;
    this.pluginName = pluginName;
    this.pluginUserProvider = pluginUserProvider;
    this.threadLocalRequestContext = threadLocalRequestContext;
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
        if (isAuthenticated(authorizationHeader, bearerToken))
          try (ManualRequestContext ctx =
              new ManualRequestContext(pluginUserProvider.get(), threadLocalRequestContext.get())) {
            final WebSession ws = session.get();
            ws.setAccessPathOk(AccessPath.REST_API, true);
            filterChain.doFilter(servletRequest, servletResponse);
          }
        else httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Bearer token");
      }
    } else {
      filterChain.doFilter(servletRequest, servletResponse);
    }
  }

  private boolean isAuthenticated(Optional<String> authorizationHeader, String bearerToken) {
    return authorizationHeader
        .flatMap(ah -> extractBearerToken(ah))
        .map(bt -> bt.equals(bearerToken))
        .orElse(false);
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
