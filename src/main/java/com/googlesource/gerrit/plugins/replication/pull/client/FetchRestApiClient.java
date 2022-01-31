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
import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;
import static com.googlesource.gerrit.plugins.replication.pull.api.ProjectInitializationAction.getProjectInitializationUrl;
import static java.util.Objects.requireNonNull;
import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.restapi.Url;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.filter.SyncRefsFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
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
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;

public class FetchRestApiClient implements FetchApiClient, ResponseHandler<Result> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  private static final Gson GSON =
      new GsonBuilder().setFieldNamingPolicy(LOWER_CASE_WITH_UNDERSCORES).create();

  private final CredentialsFactory credentials;
  private final SourceHttpClient.Factory httpClientFactory;
  private final Source source;
  private final String instanceLabel;
  private final String pluginName;
  private final SyncRefsFilter syncRefsFilter;

  @Inject
  FetchRestApiClient(
      CredentialsFactory credentials,
      SourceHttpClient.Factory httpClientFactory,
      ReplicationConfig replicationConfig,
      SyncRefsFilter syncRefsFilter,
      @PluginName String pluginName,
      @Assisted Source source) {
    this.credentials = credentials;
    this.httpClientFactory = httpClientFactory;
    this.source = source;
    this.pluginName = pluginName;
    this.syncRefsFilter = syncRefsFilter;
    this.instanceLabel =
        Strings.nullToEmpty(
                replicationConfig.getConfig().getString("replication", null, "instanceLabel"))
            .trim();
    requireNonNull(
        Strings.emptyToNull(instanceLabel), "replication.instanceLabel cannot be null or empty");
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callFetch(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public Result callFetch(Project.NameKey project, String refName, URIish targetUri)
      throws ClientProtocolException, IOException {
    String url =
        String.format(
            "%s/a/projects/%s/pull-replication~fetch",
            targetUri.toString(), Url.encode(project.get()));
    Boolean callAsync = !syncRefsFilter.match(refName);
    HttpPost post = new HttpPost(url);
    post.setEntity(
        new StringEntity(
            String.format(
                "{\"label\":\"%s\", \"ref_name\": \"%s\", \"async\":%s}",
                instanceLabel, refName, callAsync),
            StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));

    Result result = httpClientFactory.create(source).execute(post, this, getContext(targetUri));

    if (isProjectMissing(result, project) && source.isCreateMissingRepositories()) {
      result = initProjectAndFetch(project, targetUri);
    }

    return result;
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#initProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public Result initProject(Project.NameKey project, URIish uri) throws IOException {
    String url =
        String.format(
            "%s/%s", uri.toString(), getProjectInitializationUrl(pluginName, project.get()));
    HttpPut put = new HttpPut(url);
    put.addHeader(new BasicHeader("Accept", MediaType.ANY_TEXT_TYPE.toString()));
    put.addHeader(new BasicHeader("Content-Type", MediaType.PLAIN_TEXT_UTF_8.toString()));
    return httpClientFactory.create(source).execute(put, this, getContext(uri));
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#deleteProject(com.google.gerrit.entities.Project.NameKey, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public Result deleteProject(Project.NameKey project, URIish apiUri) throws IOException {
    String url =
        String.format("%s/%s", apiUri.toASCIIString(), getProjectDeletionUrl(project.get()));
    HttpDelete delete = new HttpDelete(url);
    return httpClientFactory.create(source).execute(delete, this, getContext(apiUri));
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#updateHead(com.google.gerrit.entities.Project.NameKey, java.lang.String, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public Result updateHead(Project.NameKey project, String newHead, URIish apiUri)
      throws IOException {
    logger.atFine().log("Updating head of %s on %s", project.get(), newHead);
    String url =
        String.format("%s/%s", apiUri.toASCIIString(), getProjectUpdateHeadUrl(project.get()));
    HttpPut req = new HttpPut(url);
    req.setEntity(
        new StringEntity(String.format("{\"ref\": \"%s\"}", newHead), StandardCharsets.UTF_8));
    req.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    return httpClientFactory.create(source).execute(req, this, getContext(apiUri));
  }

  /* (non-Javadoc)
   * @see com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient#callSendObject(com.google.gerrit.entities.Project.NameKey, java.lang.String, boolean, com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData, org.eclipse.jgit.transport.URIish)
   */
  @Override
  public Result callSendObject(
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
    RevisionInput input = new RevisionInput(instanceLabel, refName, revisionData);

    String url =
        String.format(
            "%s/a/projects/%s/%s~apply-object",
            targetUri.toString(), Url.encode(project.get()), pluginName);

    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(GSON.toJson(input)));
    post.addHeader(new BasicHeader("Content-Type", MediaType.JSON_UTF_8.toString()));
    Result result = httpClientFactory.create(source).execute(post, this, getContext(targetUri));
    if (isProjectMissing(result, project) && source.isCreateMissingRepositories()) {
      result = initProjectAndFetch(project, targetUri);
    }
    return result;
  }

  private Result initProjectAndFetch(Project.NameKey project, URIish targetUri)
      throws IOException, ClientProtocolException {
    Result result = initProject(project, targetUri);
    if (result.isSuccessful()) {
      result = callFetch(project, "refs/*", targetUri);
    } else {
      String errorMessage = result.message().map(e -> " - Error: " + e).orElse("");
      repLog.error("Cannot create project " + project + errorMessage);
    }
    return result;
  }

  private Boolean isProjectMissing(Result result, Project.NameKey project) {
    String projectMissingMessage = String.format("Not found: %s", project.get());
    return !result.isSuccessful()
        && result.message().map(msg -> msg.contains(projectMissingMessage)).orElse(false);
  }

  private void requireNull(Object object, String string) {
    if (object != null) {
      throw new IllegalArgumentException(string);
    }
  }

  @Override
  public Result handleResponse(HttpResponse response) {
    Optional<String> responseBody = Optional.empty();

    try {
      responseBody = Optional.ofNullable(EntityUtils.toString(response.getEntity()));
    } catch (ParseException | IOException e) {
      logger.atSevere().withCause(e).log("Unable get response body from %s", response.toString());
    }
    int responseCode = response.getStatusLine().getStatusCode();
    return Result.builder()
        .setMessage(responseBody)
        .setIsSuccessful(isSuccessful(responseCode))
        .build();
  }

  private boolean isSuccessful(int responseCode) {
    return responseCode == SC_CREATED || responseCode == SC_NO_CONTENT || responseCode == SC_OK;
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

  String getProjectDeletionUrl(String projectName) {
    return String.format("a/projects/%s/%s~delete-project", Url.encode(projectName), pluginName);
  }

  String getProjectUpdateHeadUrl(String projectName) {
    return String.format("a/projects/%s/%s~HEAD", Url.encode(projectName), pluginName);
  }
}
