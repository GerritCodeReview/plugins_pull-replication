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

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationInternalUser;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BearerAuthenticationFilterTest {

  private final Optional<String> NO_QUERY_PARAMETERS = Optional.empty();
  private final Optional<String> GIT_UPLOAD_PACK_QUERY_PARAMETER =
      Optional.of("service=git-upload-pack");
  @Mock private DynamicItem<WebSession> session;
  @Mock private WebSession webSession;
  @Mock private Provider<ThreadLocalRequestContext> threadLocalRequestContextProvider;
  @Mock private PullReplicationInternalUser pluginUser;
  @Mock private ThreadLocalRequestContext threadLocalRequestContext;
  @Mock private HttpServletRequest httpServletRequest;
  @Mock private HttpServletResponse httpServletResponse;
  @Mock private FilterChain filterChain;
  private final String pluginName = "pull-replication";

  private void authenticateAndFilter(String method, String uri, Optional<String> queryStringMaybe)
      throws Exception {
    when(httpServletRequest.getMethod()).thenReturn(method);
    authenticateAndFilter(uri, queryStringMaybe);
  }

  private void authenticateAndFilter(String uri, Optional<String> queryStringMaybe)
      throws Exception {
    final String bearerToken = "some-bearer-token";
    when(httpServletRequest.getRequestURI()).thenReturn(uri);
    queryStringMaybe.ifPresent(qs -> when(httpServletRequest.getQueryString()).thenReturn(qs));
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", bearerToken));
    when(threadLocalRequestContextProvider.get()).thenReturn(threadLocalRequestContext);
    when(session.get()).thenReturn(webSession);
    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session, pluginName, pluginUser, threadLocalRequestContextProvider, bearerToken);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest, atMost(2)).getRequestURI();
    verify(httpServletRequest, atMost(1)).getQueryString();
    verify(httpServletRequest).getHeader("Authorization");
    verify(threadLocalRequestContextProvider).get();
    verify(session).get();
    verify(webSession).setAccessPathOk(AccessPath.REST_API, true);
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldAuthenticateWhenFetch() throws Exception {
    authenticateAndFilter("any-prefix/pull-replication~fetch", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenApplyObject() throws Exception {
    authenticateAndFilter("any-prefix/pull-replication~apply-object", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenApplyObjects() throws Exception {
    authenticateAndFilter("any-prefix/pull-replication~apply-objects", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenDeleteProject() throws Exception {
    authenticateAndFilter("any-prefix/pull-replication~delete-project", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenUpdateHead() throws Exception {
    authenticateAndFilter(
        "PUT", "any-prefix/projects/my-project/pull-replication~HEAD", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenInitProject() throws Exception {
    authenticateAndFilter(
        "any-prefix/pull-replication/init-project/my-project.git", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenGitUploadPack() throws Exception {
    authenticateAndFilter("any-prefix/git-upload-pack", NO_QUERY_PARAMETERS);
  }

  @Test
  public void shouldAuthenticateWhenGitUploadPackInQueryParameter() throws Exception {
    authenticateAndFilter("any-prefix", GIT_UPLOAD_PACK_QUERY_PARAMETER);
  }

  @Test
  public void shouldGoNextInChainWhenGitUploadPackWithoutAuthenticationHeader() throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/git-upload-pack");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getHeader("Authorization");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldGoNextInChainWhenGitUploadPackWithAuthenticationHeaderDifferentThanBearer()
      throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/git-upload-pack");
    when(httpServletRequest.getHeader("Authorization")).thenReturn("some-authorization");
    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getHeader("Authorization");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldBe401WhenBearerTokenDoesNotMatch() throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", "some-different-bearer-token"));

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldBe401WhenBearerTokenCannotBeExtracted() throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization")).thenReturn("bearer token");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldBe401WhenNoAuthorizationHeaderInRequest() throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch() throws Exception {
    when(httpServletRequest.getRequestURI()).thenReturn("any-url");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getHeader("Authorization");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldGoNextInChainWhenBasicAuthorizationIsRequired() throws Exception {
    when(httpServletRequest.getRequestURI())
        .thenReturn("/a/projects/my-project/pull-replication~fetch");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getHeader("Authorization");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }
}
