package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.gerrit.httpd.restapi.RestApiServlet.SC_UNPROCESSABLE_ENTITY;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import com.google.common.net.MediaType;
import com.google.gerrit.extensions.restapi.*;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.project.ProjectResource;
import com.google.gerrit.server.restapi.project.ProjectsCollection;
import com.google.inject.Provider;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
  @Mock private ProjectsCollection projectsCollection;
  @Mock private CurrentUser currentUser;
  @Mock private Provider<CurrentUser> userProvider;
  @Mock private ProjectResource projectResource;
  @Mock private ServletOutputStream outputStream;
  @Mock private PrintWriter printWriter;
  private final String pluginName = "pull-replication";
  private final Response OK_RESPONSE = Response.ok();

  private PullReplicationFilter createPullReplicationFilter() {
    return new PullReplicationFilter(
        fetchAction,
        applyObjectAction,
        applyObjectsAction,
        projectInitializationAction,
        updateHEADAction,
        projectDeletionAction,
        projectsCollection,
        userProvider,
        pluginName);
  }

  private void defineBehaviours(byte[] payload, String operation) throws Exception {
    when(request.getRequestURI())
        .thenReturn(String.format("any-prefix/projects/some-project/%s", operation));
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    InputStream is = new ByteArrayInputStream(payload);
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
    when(request.getReader()).thenReturn(bufferedReader);
    when(projectsCollection.parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project")))
        .thenReturn(projectResource);
    when(response.getWriter()).thenReturn(printWriter);
  }

  private void verifyBehaviours(Integer timesRequestURI) throws Exception {
    verify(request, times(timesRequestURI)).getRequestURI();
    verify(userProvider).get();
    verify(currentUser).isIdentifiedUser();
    verify(request).getReader();
    verify(projectsCollection)
        .parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project"));
    verify(response).getWriter();
    verify(response).setContentType("application/json");
    verify(response).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  public void shouldFilterFetchAction() throws Exception {
    byte[] payloadFetchAction =
        ("{"
                + "\"label\":\"Replication\", "
                + "\"ref_name\": \"refs/heads/master\", "
                + "\"async\":false"
                + "}")
            .getBytes(StandardCharsets.UTF_8);

    defineBehaviours(payloadFetchAction, "pull-replication~fetch");
    when(fetchAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours(2);
    verify(fetchAction).apply(eq(projectResource), any());
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

    defineBehaviours(payloadApplyObject, "pull-replication~apply-object");

    when(applyObjectAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours(3);
    verify(applyObjectAction).apply(eq(projectResource), any());
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

    defineBehaviours(payloadApplyObjects, "pull-replication~apply-objects");

    when(applyObjectsAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours(4);
    verify(applyObjectsAction).apply(eq(projectResource), any());
  }

  @Test
  public void shouldFilterProjectInitializationAction() throws Exception {

    when(request.getRequestURI())
        .thenReturn("any-prefix/pull-replication/init-project/some-project.git");
    when(request.getHeader(ACCEPT)).thenReturn(MediaType.PLAIN_TEXT_UTF_8.toString());
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(projectInitializationAction.initProject("some-project.git")).thenReturn(true);
    when(response.getWriter()).thenReturn(printWriter);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(request, times(5)).getRequestURI();
    verify(userProvider).get();
    verify(currentUser).isIdentifiedUser();
    verify(projectInitializationAction).initProject(eq("some-project.git"));
    verify(response).getWriter();
  }

  @Test
  public void shouldFilterUpdateHEADAction() throws Exception {

    byte[] payloadHead = "{\"ref\":\"some-ref\"}".getBytes(StandardCharsets.UTF_8);
    defineBehaviours(payloadHead, "HEAD");
    when(request.getMethod()).thenReturn("PUT");
    when(updateHEADAction.apply(any(), any())).thenReturn(OK_RESPONSE);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verifyBehaviours(6);
    verify(updateHEADAction).apply(eq(projectResource), any());
  }

  @Test
  public void shouldFilterProjectDeletionAction() throws Exception {
    when(request.getRequestURI())
        .thenReturn("any-prefix/projects/some-project/pull-replication~delete-project");
    when(request.getMethod()).thenReturn("DELETE");
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(projectsCollection.parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project")))
        .thenReturn(projectResource);
    when(projectDeletionAction.apply(any(), any())).thenReturn(OK_RESPONSE);
    when(response.getWriter()).thenReturn(printWriter);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(request, times(7)).getRequestURI();
    verify(userProvider).get();
    verify(currentUser).isIdentifiedUser();
    verify(projectsCollection)
        .parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project"));
    verify(projectDeletionAction).apply(eq(projectResource), any());
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
  public void shouldBe404WhenJsonIsMalformed() throws Exception {
    byte[] payloadMalformedJson = "some-json-malformed".getBytes(StandardCharsets.UTF_8);
    InputStream is = new ByteArrayInputStream(payloadMalformedJson);
    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
    when(request.getRequestURI())
        .thenReturn("any-prefix/projects/some-project/pull-replication~fetch");
    when(request.getReader()).thenReturn(bufferedReader);
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  public void shouldBe500WhenProjectCannotBeInitiated() throws Exception {
    when(request.getRequestURI())
        .thenReturn("any-prefix/pull-replication/init-project/some-project.git");
    when(request.getHeader(ACCEPT)).thenReturn(MediaType.PLAIN_TEXT_UTF_8.toString());
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(projectInitializationAction.initProject("some-project.git")).thenReturn(false);
    when(response.getOutputStream()).thenReturn(outputStream);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  @Test
  public void shouldBe500WhenResourceNotFound() throws Exception {
    when(request.getRequestURI())
        .thenReturn("any-prefix/projects/some-project/pull-replication~delete-project");
    when(request.getMethod()).thenReturn("DELETE");
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(projectsCollection.parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project")))
        .thenReturn(projectResource);
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

    defineBehaviours(payloadFetchAction, "pull-replication~fetch");
    when(fetchAction.apply(any(), any()))
        .thenThrow(new AuthException("The user is not authorised"));
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
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

    defineBehaviours(payloadFetchAction, "pull-replication~fetch");
    when(fetchAction.apply(any(), any()))
        .thenThrow(new UnprocessableEntityException("Entity cannot be processed"));
    when(response.getOutputStream()).thenReturn(outputStream);

    PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(SC_UNPROCESSABLE_ENTITY);
  }

  @Test
  public void shouldBe409WhenThereIsResourceConflict() throws Exception {
    when(request.getRequestURI())
        .thenReturn("any-prefix/projects/some-project/pull-replication~delete-project");
    when(request.getMethod()).thenReturn("DELETE");
    when(userProvider.get()).thenReturn(currentUser);
    when(currentUser.isIdentifiedUser()).thenReturn(true);
    when(projectsCollection.parse(TopLevelResource.INSTANCE, IdString.fromDecoded("some-project")))
        .thenReturn(projectResource);

    when(projectDeletionAction.apply(any(), any()))
        .thenThrow(new ResourceConflictException("Resource conflict"));
    when(response.getOutputStream()).thenReturn(outputStream);

    final PullReplicationFilter pullReplicationFilter = createPullReplicationFilter();
    pullReplicationFilter.doFilter(request, response, filterChain);

    verify(response).setStatus(SC_CONFLICT);
  }
}
