// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.client;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.filter.SyncRefsFilter;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES;
import static com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction.getProjectInitializationUrl;
import static java.util.Objects.requireNonNull;

public class FetchRestApiClient implements FetchApiClient, ResponseHandler<HttpResult> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static String BEARER_TOKEN_KEY = "bearerToken";

  static String BEARER_TOKEN_SECTION = "auth";
  private static final Gson GSON =
      new GsonBuilder().setFieldNamingPolicy(LOWER_CASE_WITH_UNDERSCORES).create();

  private final CredentialsFactory credentials;
  private final SourceHttpClient.Factory httpClientFactory;
  private final Source source;
  private final String instanceId;
  private final String pluginName;
  private final SyncRefsFilter syncRefsFilter;

  private final Optional<String> bearerToken;

  @Inject
  FetchRestApiClient(
      CredentialsFactory credentials,
      SourceHttpClient.Factory httpClientFactory,
      ReplicationConfig replicationConfig,
      SyncRefsFilter syncRefsFilter,
      @PluginName String pluginName,
      @Nullable @GerritInstanceId String instanceId,
      @Assisted Source source,
      @GerritServerConfig Config gerritConfig) {
    this.credentials = credentials;
    this.httpClientFactory = httpClientFactory;
    this.source = source;
    this.pluginName = pluginName;
    this.syncRefsFilter = syncRefsFilter;
    this.instanceId =
        Optional.ofNullable(
                replicationConfig.getConfig().getString("replication", null, "instanceLabel"))
            .orElse(instanceId)
            .trim();

    requireNonNull(
        Strings.emptyToNull(this.instanceId),
        "gerrit.instanceId or replication.instanceLabel must be set");

    this.bearerToken =
        Optional.ofNullable(gerritConfig.getString(BEARER_TOKEN_SECTION, null, BEARER_TOKEN_KEY));
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callFetch(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult callFetch(
      Project.NameKey project, String refName, URIish targetUri, long startTimeNanos)
      throws ClientProtocolException, IOException {
    String url = formatUrl(project, targetUri.toString(), "fetch");
    Boolean callAsync = !syncRefsFilter.match(refName);
    HttpPost post = new HttpPost(url);
    post.setEntity(
        new StringEntity(
            String.format(
                "{\"label\":\"%s\", \"ref_name\": \"%s\", \"async\":%s}",
                instanceId, refName, callAsync),
            StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json"));
    post.addHeader(
        PullReplicationApiRequestMetrics.HTTP_HEADER_X_START_TIME_NANOS,
        Long.toString(startTimeNanos));
    return executeHttpReqWithAuthentication(post, bearerToken, targetUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#initProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult initProject(Project.NameKey project, URIish uri) throws IOException {
    String url =
        String.format(
            "%s/%s", uri.toString(), getProjectInitializationUrl(pluginName, project.get(), bearerToken.isEmpty()));
    HttpPut put = new HttpPut(url);
    put.addHeader(new BasicHeader(HttpHeaders.ACCEPT, MediaType.ANY_TEXT_TYPE.toString()));
    put.addHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8.toString()));
    return executeHttpReqWithAuthentication(put, bearerToken, uri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#deleteProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult deleteProject(Project.NameKey project, URIish apiUri) throws IOException {
    String url = formatUrl(project, apiUri.toASCIIString(), "delete-project");
    HttpDelete delete = new HttpDelete(url);
    return executeHttpReqWithAuthentication(delete, bearerToken, apiUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#updateHead(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult updateHead(Project.NameKey project, String newHead, URIish apiUri)
      throws IOException {
    logger.atFine().log("Updating head of %s on %s", project.get(), newHead);
    String url = formatUrl(project, apiUri.toASCIIString(), "HEAD");
    HttpPut put = new HttpPut(url);
    put.setEntity(
        new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), StandardCharsets.UTF_8));
    put.addHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString()));
    return executeHttpReqWithAuthentication(put, bearerToken, apiUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callSendObject(com.google.gerrit.entities.Project.NameKey, java.lang.String, boolean, com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult callSendObject(
      Project.NameKey project,
      String refName,
      boolean isDelete,
      @Nullable RevisionData revisionData,
      URIish targetUri)
      throws ClientProtocolException, IOException {

    if (!isDelete) {
      requireNonNull(
          revisionData, "RevisionData MUST not be null when the ref-update is not a DELETE");
    } else {
      requireNull(revisionData, "DELETE ref-updates cannot be associated with a RevisionData");
    }
    RevisionInput input = new RevisionInput(instanceId, refName, revisionData);

    String url = formatUrl(project, targetUri.toString(), "apply-object");

    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(input)));
    post.addHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString()));
    return executeHttpReqWithAuthentication(post, bearerToken, targetUri);
  }

  @Override
  public HttpResult callSendObjects(
      NameKey project, String refName, List<RevisionData> revisionData, URIish targetUri)
      throws ClientProtocolException, IOException {
    if (revisionData.size() == 1) {
      return callSendObject(project, refName, false, revisionData.get(0), targetUri);
    }

    RevisionData[] inputData = new RevisionData[revisionData.size()];
    RevisionsInput input = new RevisionsInput(instanceId, refName, revisionData.toArray(inputData));

    String url = formatUrl(project, targetUri.toString(), "apply-objects");
    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(input)));
    post.addHeader(new BasicHeader(HttpHeaders.CONTENT_TYPE, MediaType.JSON_UTF_8.toString()));
    return executeHttpReqWithAuthentication(post, bearerToken, targetUri);
  }

  private String formatUrl(Project.NameKey project, String targetUri, String api) {
    if (bearerToken.isPresent())
      return String.format(
          "%s/projects/%s/%s~%s", targetUri, Url.encode(project.get()), pluginName, api);
    else
      return String.format(
          "%s/a/projects/%s/%s~%s", targetUri, Url.encode(project.get()), pluginName, api);
  }

  private void requireNull(Object object, String string) {
    if (object != null) {
      throw new IllegalArgumentException(string);
    }
  }

  @Override
  public HttpResult handleResponse(HttpResponse response) {

    Optional<String> responseBody =
        Optional.ofNullable(response.getEntity())
            .flatMap(
                body -> {
                  try {
                    return Optional.of(EntityUtils.toString(body));
                  } catch (ParseException | IOException e) {
                    logger.atSevere().withCause(e).log(
                        "Unable get response body from %s", response.toString());
                    return Optional.empty();
                  }
                });

    return new HttpResult(response.getStatusLine().getStatusCode(), responseBody);
  }

  private HttpClientContext getContext(URIish targetUri) {
    HttpClientContext ctx = HttpClientContext.create();
    ctx.setCredentialsProvider(adapt(credentials.create(source.getRemoteConfigName()), targetUri));
    return ctx;
  }

  private CredentialsProvider adapt(org.eclipse.jgit.transport.CredentialsProvider cp, URIish uri) {
    CredentialItem.Username user = new CredentialItem.Username();
    CredentialItem.Password pass = new CredentialItem.Password();
    if (cp.supports(user, pass) && cp.get(uri, user, pass)) {
      CredentialsProvider adapted = new BasicCredentialsProvider();
      adapted.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(user.getValue(), new String(pass.getValue())));
      return adapted;
    }
    return null;
  }

  private HttpResult executeHttpReqWithAuthentication(
      HttpRequestBase httpRequest, Optional<String> bearerToken, URIish targetUri)
      throws IOException {
    if (bearerToken.isPresent()) {
      httpRequest.addHeader(
          new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken.get()));
      return httpClientFactory
          .create(source)
          .execute(httpRequest, this, HttpClientContext.create());
    } else {
      return httpClientFactory.create(source).execute(httpRequest, this, getContext(targetUri));
    }
  }
}
