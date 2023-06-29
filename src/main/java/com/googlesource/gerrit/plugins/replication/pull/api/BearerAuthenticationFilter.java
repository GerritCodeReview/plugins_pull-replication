// Copyright (C) 2022 The Android Open Source Project
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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationInternalUser;
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

  private static final String BEARER_TOKEN = "BearerToken";
  private static final String BEARER_TOKEN_PREFIX = "Bearer";
  private final DynamicItem<WebSession> session;
  private final String pluginName;
  private final PullReplicationInternalUser pluginUser;
  private final Provider<ThreadLocalRequestContext> threadLocalRequestContext;
  private final String bearerToken;
  private final Pattern bearerTokenRegex = Pattern.compile("^Bearer\\s(.+)$");

  @Inject
  BearerAuthenticationFilter(
      DynamicItem<WebSession> session,
      @PluginName String pluginName,
      PullReplicationInternalUser pluginUser,
      Provider<ThreadLocalRequestContext> threadLocalRequestContext,
      @Named(BEARER_TOKEN) String bearerToken) {
    this.session = session;
    this.pluginName = pluginName;
    this.pluginUser = pluginUser;
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

    HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
    HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
    String requestURI = httpRequest.getRequestURI();
    Optional<String> authorizationHeader =
        Optional.ofNullable(httpRequest.getHeader("Authorization"));

    if (isBasicAuthenticationRequest(requestURI)) {
      filterChain.doFilter(servletRequest, servletResponse);
    } else if (isPullReplicationApiRequest(requestURI)
        || (isGitUploadPackRequest(httpRequest)
            && isAuthenticationHeaderWithBearerToken(authorizationHeader))) {
      if (isBearerTokenAuthenticated(authorizationHeader, bearerToken))
        try (ManualRequestContext ctx =
            new ManualRequestContext(pluginUser, threadLocalRequestContext.get())) {
          WebSession ws = session.get();
          ws.setAccessPathOk(AccessPath.REST_API, true);
          filterChain.doFilter(servletRequest, servletResponse);
        }
      else httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);

    } else {
      filterChain.doFilter(servletRequest, servletResponse);
    }
  }

  private boolean isGitUploadPackRequest(HttpServletRequest requestURI) {
    return requestURI.getRequestURI().contains("git-upload-pack")
        || Optional.ofNullable(requestURI.getQueryString())
            .map(q -> q.contains("git-upload-pack"))
            .orElse(false);
  }

  private boolean isBearerTokenAuthenticated(
      Optional<String> authorizationHeader, String bearerToken) {
    return authorizationHeader
        .flatMap(this::extractBearerToken)
        .map(bt -> bt.equals(bearerToken))
        .orElse(false);
  }

  private boolean isBasicAuthenticationRequest(String requestURI) {
    return requestURI.startsWith("/a/");
  }

  private boolean isPullReplicationApiRequest(String requestURI) {
    return (requestURI.contains(pluginName)
            && (requestURI.endsWith(String.format("/%s~apply-object", pluginName))
                || requestURI.endsWith(String.format("/%s~apply-objects", pluginName))
                || requestURI.endsWith(String.format("/%s~batch-apply-object", pluginName))
                || requestURI.endsWith(String.format("/%s~fetch", pluginName))
                || requestURI.endsWith(String.format("/%s~batch-fetch", pluginName))
                || requestURI.endsWith(String.format("/%s~delete-project", pluginName))
                || requestURI.contains(String.format("/%s/init-project/", pluginName))))
        || requestURI.matches(".*/projects/[^/]+/HEAD");
  }

  private Optional<String> extractBearerToken(String authorizationHeader) {
    Matcher projectGroupMatcher = bearerTokenRegex.matcher(authorizationHeader);

    if (projectGroupMatcher.find()) {
      return Optional.of(projectGroupMatcher.group(1));
    }
    return Optional.empty();
  }

  private boolean isAuthenticationHeaderWithBearerToken(Optional<String> authorizationHeader) {
    return authorizationHeader.map(h -> h.startsWith(BEARER_TOKEN_PREFIX)).orElse(false);
  }
}
