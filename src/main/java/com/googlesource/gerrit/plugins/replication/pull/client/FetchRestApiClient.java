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

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.Url;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResponseHandler.HttpResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;

public class FetchRestApiClient {

  static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  public interface Factory {
    FetchRestApiClient create(Source source);
  }

  private final CredentialsFactory credentials;
  private final CloseableHttpClient httpClient;
  private final Source source;
  private final URIish sourceUri;

  @Inject
  FetchRestApiClient(
      CredentialsFactory credentials,
      CloseableHttpClient httpClient,
      ReplicationFileBasedConfig replicationConfig,
      @Assisted Source source)
      throws URISyntaxException {
    this.credentials = credentials;
    this.httpClient = httpClient;
    this.source = source;
    this.sourceUri =
        new URIish(replicationConfig.getConfig().getString("replication", null, "sourceUrl"));
  }

  public HttpResult callFetch(Project.NameKey project, ObjectId objectId, URIish targetUri)
      throws ClientProtocolException, IOException {
    String url =
        String.format(
            "%s/a/projects/%s/pull-replication~fetch",
            toHttpUri(targetUri), Url.encode(project.get()));

    HttpPost post = new HttpPost(url);
    post.setEntity(
        new StringEntity(
            String.format(
                "{\"url\":\"%s\", \"object_id\": \"%s\"}",
                toHttpUri(sourceUri), objectId.getName()),
            StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));
    return httpClient.execute(post, new HttpResponseHandler(), getContext(targetUri));
  }

  public HttpResult createProject(Project.NameKey project, URIish uri)
      throws ClientProtocolException, IOException {
    String url = String.format("%s/a/projects/%s", toHttpUri(uri), Url.encode(project.get()));
    return httpClient.execute(new HttpPut(url), new HttpResponseHandler(), getContext(uri));
  }

  private static String toHttpUri(URIish uri) {
    String u = uri.toString();
    if (u.startsWith(GERRIT_ADMIN_PROTOCOL_PREFIX)) {
      u = u.substring(GERRIT_ADMIN_PROTOCOL_PREFIX.length());
    }
    if (u.endsWith("/")) {
      return u.substring(0, u.length() - 1);
    }
    return u;
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
}
