package com.googlesource.gerrit.plugins.replication.pull.api;

import static org.mockito.Mockito.*;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BearerTokenAuthenticationFilterTest {

  @Mock private Config gerritConfig;
  @Mock private HttpServletRequest httpServletRequest;
  @Mock private HttpServletResponse httpServletResponse;
  @Mock private FilterChain filterChain;
  private final String pluginName = "pull-replication";

  private void authenticateWithURI(String uri) throws ServletException, IOException {
    final String bearerTokenInRequest = "some-bearer-token";
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn(bearerTokenInRequest);
    when(httpServletRequest.getRequestURI()).thenReturn(uri);
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", bearerTokenInRequest));

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenFetch() throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~fetch");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~apply-object");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObjects()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~apply-objects");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenDeleteProject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~delete-project");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenUpdateHead()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/projects/my-project/HEAD");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenInitProject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication/init-project/my-project.git");
  }

  @Test
  public void shouldBe401WhenBearerTokenDoesNotMatch() throws ServletException, IOException {
    String bearerTokenInRequest = "some-different-bearer-token";
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn("some-bearer-token");
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", bearerTokenInRequest));

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(401, "Invalid Bearer token");
  }

  @Test
  public void shouldBe401WhenBearerTokenIsMalformed() throws ServletException, IOException {
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn("some-bearer-token");
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn("some malformed authorization header");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(401, "Invalid Bearer token");
  }

  @Test
  public void shouldBe401WhenNoAuthorizationHeaderInRequest() throws ServletException, IOException {
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn("some-bearer-token");
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse)
        .sendError(401, "Bearer token authentication not provided in the request");
  }

  @Test
  public void shouldBe500WhenBearerTokenIsNotPresentInServer()
      throws ServletException, IOException {
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn(null);
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse).sendError(500, "Bearer token not provided by the server");
  }

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch() throws ServletException, IOException {
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn(null);
    when(httpServletRequest.getRequestURI()).thenReturn("any-url");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(httpServletRequest).getRequestURI();
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldGoNextInChainWhenBasicAuthorizationIsRequired()
      throws ServletException, IOException {
    when(gerritConfig.getString("auth", null, "bearerToken")).thenReturn(null);
    when(httpServletRequest.getRequestURI())
        .thenReturn("any-prefix/a/projects/my-project/pull-replication~fetch");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(gerritConfig).getString("auth", null, "bearerToken");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }
}
