package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.gerrit.httpd.restapi.RestApiServlet.SC_UNPROCESSABLE_ENTITY;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.atLeastOnce;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import com.google.common.net.MediaType;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.util.Providers;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PullReplicationFilterTest {

  @Mock HttpServletRequest request;
  @Mock HttpServletResponse response;
  @Mock FilterChain filterChain;
  @Mock private FetchAction fetchAction;
  @Mock private ApplyObjectAction applyObjectAction;
  @Mock private ApplyObjectsAction applyObjectsAction;
  @Mock private ProjectInitializationAction projectInitializationAction;
  @Mock private UpdateHeadAction updateHEADAction;
  @Mock private ProjectDeletionAction projectDeletionAction;
  @Mock private ProjectCache projectCache;
  @Mock private ProjectState projectState;
  @Mock private ServletOutputStream outputStream;
  @Mock private PrintWriter printWriter;
  @Mock private IdentifiedUser identifiedUserMock;
  @Mock private AnonymousUser anonymousUserMock;
  private final String PLUGIN_NAME = "pull-replication";
  private final String PROJECT_NAME = "some-project";
  private final String PROJECT_NAME_GIT = "some-project.git";
  private final String FETCH_URI =
      String.format("any-prefix/projects/%s/%s~fetch", PROJECT_NAME, PLUGIN_NAME);
  private final String APPLY_OBJECT_URI =
      String.format("any-prefix/projects/%s/%s~apply-object", PROJECT_NAME, PLUGIN_NAME);
  private final String APPLY_OBJECTS_URI =
      String.format("any-prefix/projects/%s/%s~apply-objects", PROJECT_NAME, PLUGIN_NAME);
  private final String HEAD_URI =
      String.format("any-prefix/projects/%s/%s~HEAD", PROJECT_NAME, PLUGIN_NAME);
  private final String DELETE_PROJECT_URI =
      String.format("any-prefix/projects/%s/%s~delete-project", PROJECT_NAME, PLUGIN_NAME);
  private final String INIT_PROJECT_URI =
      String.format("any-prefix/%s/init-project/%s", PLUGIN_NAME, PROJECT_NAME_GIT);

  private final Response OK_RESPONSE = Response.ok();

  private PullReplicationFilter createPullReplicationFilter() {
    return createPullReplicationFilter(identifiedUserMock);
  }

  private PullReplicationFilter createPullReplicationFilter(CurrentUser currentUser) {
    return new PullReplicationFilter(
        fetchAction,
        applyObjectAction,
        applyObjectsAction,
        projectInitializationAction,
        updateHEADAction,
        projectDeletionAction,
        projectCache,
        PLUGIN_NAME,
        Providers.of(currentUser));
  }

  private void defineBehaviours(byte[] payload, String uri) throws Exception {
    when(request.getRequestURI()).thenReturn(uri);
    InputStream is = new ByteArrayInputStream(payload);
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
    when(request.getReader()).thenReturn(bufferedReader);
    when(projectCache.get(Project.nameKey(PROJECT_NAME))).thenReturn(Optional.of(projectState));
    when(response.getWriter()).thenReturn(printWriter);
  }

  private void verifyBehaviours() throws Exception {
    verify(request, atLeastOnce()).getRequestURI();
    verify(request).getReader();
    verify(projectCache).get(Project.nameKey(PROJECT_NAME));
    verify(response).getWriter();
    verify(response).setContentType("application/json");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  public void shouldFilterFetchAction() throws Exception {
    byte[] payloadFetch =
        ("{"
                + "\"label\":\"Replication\", "
                + "\"ref_name\": \"refs/heads/master\", "
                + "\"async\":false"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadFetch, FETCH_URI);
    when(fetchAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours();
    verify(fetchAction).apply(any(ProjectResource.class), any());
  }

  @Test
  public void shouldFilterApplyObjectAction() throws Exception {

    byte[] payloadApplyObject =
        ("{\"label\":\"Replication\",\"ref_name\":\"refs/heads/master\","
                + "\"revision_data\":{"
                + "\"commit_object\":{\"type\":1,\"content\":\"some-content\"},"
                + "\"tree_object\":{\"type\":2,\"content\":\"some-content\"},"
                + "\"blobs\":[]}"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadApplyObject, APPLY_OBJECT_URI);

    when(applyObjectAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours();
    verify(applyObjectAction).apply(any(ProjectResource.class), any());
  }

  @Test
  public void shouldFilterApplyObjectsAction() throws Exception {

    byte[] payloadApplyObjects =
        ("{\"label\":\"Replication\",\"ref_name\":\"refs/heads/master\","
                + "\"revisions_data\":[{"
                + "\"commit_object\":{\"type\":1,\"content\":\"some-content\"},"
                + "\"tree_object\":{\"type\":2,\"content\":\"some-content\"},"
                + "\"blobs\":[]}]}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadApplyObjects, APPLY_OBJECTS_URI);

    when(applyObjectsAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours();
    verify(applyObjectsAction).apply(any(ProjectResource.class), any());
  }

  @Test
  public void shouldFilterProjectInitializationAction() throws Exception {

    when(request.getRequestURI()).thenReturn(INIT_PROJECT_URI);
    when(request.getHeader(ACCEPT)).thenReturn(MediaType.PLAIN_TEXT_UTF_8.toString());

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(request, atLeastOnce()).getRequestURI();
    verify(projectInitializationAction).service(any(), any());
  }

  @Test
  public void shouldFilterUpdateHEADAction() throws Exception {

    byte[] payloadUpdateHead = "{\"ref\":\"some-ref\"}".getBytes(StandardCharsets.UTF_8);
    defineBehaviours(payloadUpdateHead, HEAD_URI);
    when(request.getMethod()).thenReturn("PUT");
    when(updateHEADAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours();
    verify(updateHEADAction).apply(any(ProjectResource.class), any());
  }

  @Test
  public void shouldFilterProjectDeletionAction() throws Exception {
    when(request.getRequestURI()).thenReturn(DELETE_PROJECT_URI);
    when(request.getMethod()).thenReturn("DELETE");
    when(projectCache.get(Project.nameKey(PROJECT_NAME))).thenReturn(Optional.of(projectState));
    when(projectDeletionAction.apply(any(), any())).thenReturn(OK_RESPONSE);
    when(response.getWriter()).thenReturn(printWriter);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(request, times(7)).getRequestURI();
    verify(projectCache).get(Project.nameKey(PROJECT_NAME));
    verify(projectDeletionAction).apply(any(ProjectResource.class), any());
    verify(response).getWriter();
    verify(response).setContentType("application/json");
    verify(response).setStatus(OK_RESPONSE.statusCode());
  }

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch() throws Exception {
    when(request.getRequestURI()).thenReturn("any-url");
    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  public void shouldGoNextInChainWhenAnonymousRequestUriDoesNotMatch() throws Exception {
    when(request.getRequestURI()).thenReturn("any-url");
    lenient().when(response.getOutputStream()).thenReturn(outputStream);

    final PullReplicationFilter pullReplicationFilter =
        createPullReplicationFilter(anonymousUserMock);
    pullReplicationFilter.doFilter(request, response, filterChain);
    verify(filterChain).doFilter(request, response);
  }

  @Test
  public void shouldBe404WhenJsonIsMalformed() throws Exception {
    byte[] payloadMalformedJson = "some-json-malformed".getBytes(StandardCharsets.UTF_8);
    InputStream is = new ByteArrayInputStream(payloadMalformedJson);
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
    when(request.getRequestURI()).thenReturn(FETCH_URI);
    when(request.getReader()).thenReturn(bufferedReader);
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  public void shouldBe500WhenResourceNotFound() throws Exception {
    when(request.getRequestURI()).thenReturn(DELETE_PROJECT_URI);
    when(request.getMethod()).thenReturn("DELETE");
    when(projectCache.get(Project.nameKey(PROJECT_NAME))).thenReturn(Optional.of(projectState));
    when(projectDeletionAction.apply(any(), any()))
        .thenThrow(new ResourceNotFoundException("resource not found"));
    when(response.getOutputStream()).thenReturn(outputStream);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  public void shouldBe403WhenUserIsNotAuthorised() throws Exception {
    byte[] payloadFetchAction =
        ("{"
                + "\"label\":\"Replication\", "
                + "\"ref_name\": \"refs/heads/master\", "
                + "\"async\":false"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadFetchAction, FETCH_URI);
    when(fetchAction.apply(any(), any()))
        .thenThrow(new AuthException("The user is not authorised"));
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  public void shouldBe401WhenUserIsAnonymous() throws Exception {
    byte[] payloadFetchAction = "{}".getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadFetchAction, FETCH_URI);
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter(anonymousUserMock);
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  public void shouldBe422WhenEntityCannotBeProcessed() throws Exception {
    byte[] payloadFetchAction =
        ("{"
                + "\"label\":\"Replication\", "
                + "\"ref_name\": \"refs/heads/master\", "
                + "\"async\":false"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadFetchAction, FETCH_URI);
    when(fetchAction.apply(any(), any()))
        .thenThrow(new UnprocessableEntityException("Entity cannot be processed"));
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldBe409WhenThereIsResourceConflict() throws Exception {
    when(request.getRequestURI()).thenReturn(DELETE_PROJECT_URI);
    when(request.getMethod()).thenReturn("DELETE");
    when(projectCache.get(Project.nameKey(PROJECT_NAME))).thenReturn(Optional.of(projectState));

    when(projectDeletionAction.apply(any(), any()))
        .thenThrow(new ResourceConflictException("Resource conflict"));
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(SC_CONFLICT);
  }

  @Test
  public void shouldBe400WhenProjectNameIsNotPresentInURL() throws Exception {
    when(request.getRequestURI())
        .thenReturn(String.format("any-prefix/projects/%s~delete-project", PLUGIN_NAME));
    when(request.getMethod()).thenReturn("DELETE");
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
  }
}
