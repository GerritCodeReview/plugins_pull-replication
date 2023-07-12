// Copyright (C) 2021 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.pull.RevisionReader;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.client.SourceHttpClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule",
    httpModule = "com.googlesource.gerrit.plugins.replication.pull.api.HttpModule")
public abstract class ActionITBase extends LightweightPluginDaemonTest {
  protected static final int TEST_CONNECTION_TIMEOUT = 600000;
  protected static final int TEST_IDLE_TIMEOUT = 600000;
  protected static final Optional<String> ALL_PROJECTS = Optional.empty();
  protected static final int TEST_REPLICATION_DELAY = 60;
  protected static final String TEST_REPLICATION_SUFFIX = "suffix1";
  protected static final String TEST_REPLICATION_REMOTE = "remote1";

  protected Path gitPath;
  protected FileBasedConfig config;
  protected FileBasedConfig secureConfig;
  protected RevisionReader revisionReader;

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  CredentialsFactory credentials;
  Source source;
  SourceHttpClient.Factory httpClientFactory;
  String url;

  protected abstract String getURLWithAuthenticationPrefix(String projectName);

  protected String getURLWithoutAuthenticationPrefix(String projectName) {
    return getURLWithAuthenticationPrefix(projectName).replace("a/", "");
  }

  @Override
  public void setUpTestPlugin() throws Exception {
    gitPath = sitePaths.site_path.resolve("git");

    config =
        new FileBasedConfig(sitePaths.etc_dir.resolve("replication.config").toFile(), FS.DETECTED);
    setReplicationSource(
        TEST_REPLICATION_REMOTE,
        TEST_REPLICATION_SUFFIX,
        ALL_PROJECTS); // Simulates a full replication.config initialization
    config.save();

    secureConfig =
        new FileBasedConfig(sitePaths.etc_dir.resolve("secure.config").toFile(), FS.DETECTED);
    setReplicationCredentials(TEST_REPLICATION_REMOTE, admin.username(), admin.httpPassword());
    secureConfig.save();

    super.setUpTestPlugin();

    httpClientFactory = plugin.getSysInjector().getInstance(SourceHttpClient.Factory.class);
    credentials = plugin.getSysInjector().getInstance(CredentialsFactory.class);
    revisionReader = plugin.getSysInjector().getInstance(RevisionReader.class);
    source = plugin.getSysInjector().getInstance(SourcesCollection.class).getAll().get(0);

    url = getURLWithAuthenticationPrefix(project.get());
  }

  protected HttpPost createRequest(String sendObjectPayload) {
    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(sendObjectPayload, StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));
    return post;
  }

  protected HttpPut createPutRequest(String sendObjectPayload) {
    HttpPut put = new HttpPut(url);
    put.setEntity(new StringEntity(sendObjectPayload, StandardCharsets.UTF_8));
    put.addHeader(new BasicHeader("Content-Type", "application/json"));
    return put;
  }

  protected HttpDelete createDeleteRequest() {
    HttpDelete delete = new HttpDelete(url);
    return delete;
  }

  protected String createRef() throws Exception {
    return createRef(Project.nameKey(project + TEST_REPLICATION_SUFFIX));
  }

  protected String createRef(NameKey projectName) throws Exception {
    testRepo = cloneProject(createTestProject(projectName.get()));

    Result pushResult = createChange("topic", "test.txt", "test_content");
    return RefNames.changeMetaRef(pushResult.getChange().getId());
  }

  protected Optional<RevisionData> createRevisionData(String refName) throws Exception {
    return createRevisionData(Project.nameKey(project + TEST_REPLICATION_SUFFIX), refName);
  }

  protected Optional<RevisionData> createRevisionData(NameKey projectName, String refName)
      throws Exception {
    try (Repository repository = repoManager.openRepository(projectName)) {
      return revisionReader.read(
          projectName, repository.exactRef(refName).getObjectId(), refName, 0);
    }
  }

  protected Object encode(byte[] content) {
    return Base64.getEncoder().encodeToString(content);
  }

  public ResponseHandler<Object> assertHttpResponseCode(int responseCode) {
    return new ResponseHandler<Object>() {

      @Override
      public Object handleResponse(HttpResponse response)
          throws ClientProtocolException, IOException {
        assertThat(response.getStatusLine().getStatusCode()).isEqualTo(responseCode);
        return null;
      }
    };
  }

  protected HttpRequestBase withBearerTokenAuthentication(
      HttpRequestBase httpRequest, String bearerToken) {
    httpRequest.addHeader(new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken));
    return httpRequest;
  }

  protected HttpRequestBase withBasicAuthenticationAsAdmin(HttpRequestBase httpRequest)
      throws AuthenticationException {
    return withBasicAuthentication(httpRequest, admin);
  }

  protected HttpRequestBase withBasicAuthenticationAsUser(HttpRequestBase httpRequest)
      throws AuthenticationException {
    return withBasicAuthentication(httpRequest, user);
  }

  private HttpRequestBase withBasicAuthentication(HttpRequestBase httpRequest, TestAccount account)
      throws AuthenticationException {
    UsernamePasswordCredentials creds =
        new UsernamePasswordCredentials(account.username(), account.httpPassword());
    httpRequest.addHeader(new BasicScheme().authenticate(creds, httpRequest, null));
    return httpRequest;
  }

  private Project.NameKey createTestProject(String name) throws Exception {
    return projectOperations.newProject().name(name).parent(project).create();
  }

  private void setReplicationSource(
      String remoteName, String replicaSuffix, Optional<String> project) throws IOException {
    setReplicationSource(remoteName, Arrays.asList(replicaSuffix), project);
  }

  private void setReplicationSource(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {

    List<String> replicaUrls =
        replicaSuffixes.stream()
            .map(suffix -> gitPath.resolve("${name}" + suffix + ".git").toString())
            .collect(toList());
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setString("remote", remoteName, "apiUrl", adminRestSession.url());
    config.setString("remote", remoteName, "fetch", "+refs/tags/*:refs/tags/*");
    config.setInt("remote", remoteName, "connectionTimeout", TEST_CONNECTION_TIMEOUT);
    config.setInt("remote", remoteName, "idleTimeout", TEST_IDLE_TIMEOUT);
    config.setInt("remote", remoteName, "replicationDelay", TEST_REPLICATION_DELAY);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setBoolean("gerrit", null, "autoReload", true);
    config.save();
  }

  private void setReplicationCredentials(String remoteName, String username, String password)
      throws IOException {
    secureConfig.setString("remote", remoteName, "username", username);
    secureConfig.setString("remote", remoteName, "password", password);
    secureConfig.save();
  }
}
