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

  private void authenticateWith(String uri) throws ServletException, IOException {
    final String bearerTokenInRequest = "some-bearer-token";
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn(bearerTokenInRequest);
    when(httpServletRequest.getRequestURI()).thenReturn(uri);
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", bearerTokenInRequest));

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(httpServletRequest).getRequestURI();
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenFetch() throws ServletException, IOException {
    authenticateWith("any-prefix/pull-replication~fetch");
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenApplyObject() throws ServletException, IOException {
    authenticateWith("any-prefix/pull-replication~apply-object");
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenApplyObjects()
      throws ServletException, IOException {
    authenticateWith("any-prefix/pull-replication~apply-objects");
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenDeleteProject()
      throws ServletException, IOException {
    authenticateWith("any-prefix/pull-replication~delete-project");
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenUpdateHead() throws ServletException, IOException {
    authenticateWith("any-prefix/projects/my-project/HEAD");
  }

  @Test
  public void shouldAuthoriseWithBearerTokenWhenInitProject() throws ServletException, IOException {
    authenticateWith("any-prefix/pull-replication/init-project/my-project.git");
  }

  @Test
  public void shouldBe401WhenBearerTokenIsNotCorrect() throws ServletException, IOException {
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn("some-bearer-token");
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", "some-different-bearer-token"));

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse).setStatus(401);
    // check the string in the response
    verifyNoInteractions(filterChain);
  }

  @Test
  public void shouldBe401WhenNoAuthorizationHeaderInRequest() throws ServletException, IOException {
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn("some-bearer-token");
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse).setStatus(401);
    // check the string in the response
    verifyNoInteractions(filterChain);
  }

  @Test
  public void shouldBe500WhenBearerTokenIsNotPresentInServer()
      throws ServletException, IOException {
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn(null);
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", "some-different-bearer-token"));

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse).setStatus(500);
    // check the string in the response
    verifyNoInteractions(filterChain);
  }

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch() throws ServletException, IOException {
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn(null);
    when(httpServletRequest.getRequestURI()).thenReturn("any-url");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(httpServletRequest).getRequestURI();
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldGoNextInChainWhenBearerTokenAuthorizationNotRequired()
      throws ServletException, IOException {
    when(gerritConfig.getString("bearerToken", null, "auth")).thenReturn(null);
    when(httpServletRequest.getRequestURI())
        .thenReturn("any-prefix/a/projects/my-project/pull-replication~fetch");

    final BearerTokenAuthenticationFilter filter =
        new BearerTokenAuthenticationFilter(pluginName, gerritConfig);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(gerritConfig).getString("bearerToken", null, "auth");
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }
}
