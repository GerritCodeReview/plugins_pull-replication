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

import static com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gerrit.server.config.GerritInstanceId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.BearerTokenProvider;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import com.googlesource.gerrit.plugins.replication.pull.filter.SyncRefsFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;

public class FetchRestApiClient implements FetchApiClient, ResponseHandler<HttpResult> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  private static final Gson GSON =
      new GsonBuilder().setFieldNamingPolicy(LOWER_CASE_WITH_UNDERSCORES).create();

  private final CredentialsFactory credentials;
  private final SourceHttpClient.Factory httpClientFactory;
  private final Source source;
  private final String instanceId;
  private final String pluginName;
  private final SyncRefsFilter syncRefsFilter;
  private final BearerTokenProvider bearerTokenProvider;
  private final String urlAuthenticationPrefix;

  @Inject
  FetchRestApiClient(
      CredentialsFactory credentials,
      SourceHttpClient.Factory httpClientFactory,
      ReplicationConfig replicationConfig,
      SyncRefsFilter syncRefsFilter,
      @PluginName String pluginName,
      @Nullable @GerritInstanceId String instanceId,
      BearerTokenProvider bearerTokenProvider,
      @Assisted Source source) {
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

    this.bearerTokenProvider = bearerTokenProvider;
    this.urlAuthenticationPrefix = bearerTokenProvider.get().map(br -> "").orElse("a/");
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callFetch(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult callFetch(
      Project.NameKey project, String refName, URIish targetUri, long startTimeNanos)
      throws IOException {
    String url = formatUrl(targetUri.toString(), project, "fetch");
    Boolean callAsync = !syncRefsFilter.match(refName);
    HttpPost post = new HttpPost(url);
    post.setEntity(
        new StringEntity(
            String.format(
                "{\"label\":\"%s\", \"ref_name\": \"%s\", \"async\":%s}",
                instanceId, refName, callAsync),
            StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));
    post.addHeader(
        PullReplicationApiRequestMetrics.HTTP_HEADER_X_START_TIME_NANOS,
        Long.toString(startTimeNanos));
    return executeRequest(post, bearerTokenProvider.get(), targetUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#initProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult initProject(Project.NameKey project, URIish uri) throws IOException {
    String url = formatInitProjectUrl(uri.toString(), project);
    HttpPut put = new HttpPut(url);
    put.addHeader(new BasicHeader("Accept", MediaType.ANY_TEXT_TYPE.toString()));
    put.addHeader(new BasicHeader("Content-Type", MediaType.PLAIN_TEXT_UTF_8.toString()));
    return executeRequest(put, bearerTokenProvider.get(), uri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#deleteProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult deleteProject(Project.NameKey project, URIish apiUri) throws IOException {
    String url = formatUrl(apiUri.toASCIIString(), project, "delete-project");
    HttpDelete delete = new HttpDelete(url);
    return executeRequest(delete, bearerTokenProvider.get(), apiUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#updateHead(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult updateHead(Project.NameKey project, String newHead, URIish apiUri)
      throws IOException {
    logger.atFine().log("Updating head of %s on %s", project.get(), newHead);
    String url = formatUrl(apiUri.toASCIIString(), project, "HEAD");
    HttpPut req = new HttpPut(url);
    req.setEntity(
        new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), StandardCharsets.UTF_8));
    req.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    return executeRequest(req, bearerTokenProvider.get(), apiUri);
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callSendObject(com.google.gerrit.entities.Project.NameKey, java.lang.String, boolean, com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public HttpResult callSendObject(
      NameKey project,
      String refName,
      long eventCreatedOn,
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
    RevisionInput input = new RevisionInput(instanceId, refName, eventCreatedOn, revisionData);

    String url = formatUrl(targetUri.toString(), project, "apply-object");

    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(input)));
    post.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    return executeRequest(post, bearerTokenProvider.get(), targetUri);
  }

  @Override
  public HttpResult callBatchSendObject(
      NameKey project,
      List<BatchApplyObjectData> batchApplyObjects,
      long eventCreatedOn,
      URIish targetUri)
      throws ClientProtocolException, IOException {
    // TODO: ADD LOGIC FOR REF DELETE
    // TODO: Check if it is preserving the order of refs or replace revisionData with List
    List<RevisionInput> inputs =
        batchApplyObjects.stream()
            .map(
                batchApplyObject ->
                    new RevisionInput(
                        instanceId,
                        batchApplyObject.getRefName(),
                        eventCreatedOn,
                        batchApplyObject.getRevisionData().orElse(null)))
            .collect(Collectors.toList());

    String url = formatUrl(targetUri.toString(), project, "batch-apply-object");

    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(inputs)));
    post.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    return executeRequest(post, bearerTokenProvider.get(), targetUri);
  }

  @Override
  public HttpResult callSendObjects(
      NameKey project,
      String refName,
      long eventCreatedOn,
      List<RevisionData> revisionData,
      URIish targetUri)
      throws ClientProtocolException, IOException {
    if (revisionData.size() == 1) {
      return callSendObject(
          project, refName, eventCreatedOn, false, revisionData.get(0), targetUri);
    }

    RevisionData[] inputData = new RevisionData[revisionData.size()];
    RevisionsInput input =
        new RevisionsInput(instanceId, refName, eventCreatedOn, revisionData.toArray(inputData));

    String url = formatUrl(targetUri.toString(), project, "apply-objects");
    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(input)));
    post.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    return executeRequest(post, bearerTokenProvider.get(), targetUri);
  }

  private String formatUrl(String targetUri, Project.NameKey project, String api) {
    return String.format(
        "%s/%sprojects/%s/%s~%s",
        targetUri, urlAuthenticationPrefix, Url.encode(project.get()), pluginName, api);
  }

  private String formatInitProjectUrl(String targetUri, Project.NameKey project) {
    return String.format(
        "%s/%splugins/%s/init-project/%s.git",
        targetUri, urlAuthenticationPrefix, pluginName, Url.encode(project.get()));
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

  private HttpResult executeRequest(
      HttpRequestBase httpRequest, Optional<String> bearerToken, URIish targetUri)
      throws IOException {

    HttpRequestBase reqWithAuthentication =
        bearerToken.isPresent()
            ? withBearerTokenAuthentication(httpRequest, bearerToken.get())
            : withBasicAuthentication(targetUri, httpRequest);

    return httpClientFactory.create(source).execute(reqWithAuthentication, this);
  }

  private HttpRequestBase withBasicAuthentication(URIish targetUri, HttpRequestBase req) {
    org.eclipse.jgit.transport.CredentialsProvider cp =
        credentials.create(source.getRemoteConfigName());
    CredentialItem.Username user = new CredentialItem.Username();
    CredentialItem.Password pass = new CredentialItem.Password();
    if (cp.supports(user, pass) && cp.get(targetUri, user, pass)) {
      UsernamePasswordCredentials creds =
          new UsernamePasswordCredentials(user.getValue(), new String(pass.getValue()));
      try {
        req.addHeader(new BasicScheme().authenticate(creds, req, null));
      } catch (AuthenticationException e) {
        logger.atFine().log("Anonymous Basic Authentication for uri: %s", targetUri);
      }
    }
    return req;
  }

  private HttpRequestBase withBearerTokenAuthentication(HttpRequestBase req, String bearerToken) {
    req.addHeader(new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken));
    return req;
  }
}
