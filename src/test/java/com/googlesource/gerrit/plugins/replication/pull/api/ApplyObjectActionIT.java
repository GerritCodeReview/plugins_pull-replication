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
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.restapi.Url;
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
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.PullReplicationModule")
public class ApplyObjectActionIT extends LightweightPluginDaemonTest {
  private static final Optional<String> ALL_PROJECTS = Optional.empty();
  private static final int TEST_REPLICATION_DELAY = 60;
  private static final String TEST_REPLICATION_SUFFIX = "suffix1";
  private static final String TEST_REPLICATION_REMOTE = "remote1";

  private Path gitPath;
  private FileBasedConfig config;
  private FileBasedConfig secureConfig;
  private RevisionReader revisionReader;

  @Inject private SitePaths sitePaths;
  @Inject private ProjectOperations projectOperations;
  CredentialsFactory credentials;
  Source source;
  SourceHttpClient.Factory httpClientFactory;
  String url;

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

    url =
        String.format(
            "%s/a/projects/%s/pull-replication~apply-object",
            adminRestSession.url(), Url.encode(project.get()));
  }

  @Test
  public void shouldAcceptPayloadWithAsyncField() throws Exception {
    String payloadWithAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(payloadWithAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  public void shouldAcceptPayloadWithoutAsyncField() throws Exception {
    String payloadWithoutAsyncFieldTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutAsyncFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(201), getContext());
  }

  @Test
  public void shouldReturnBadRequestCodeWhenMandatoryFieldLabelIsMissing() throws Exception {
    String payloadWithoutLabelFieldTemplate =
        "{\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload =
        createPayload(payloadWithoutLabelFieldTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(400), getContext());
  }

  @Test
  public void shouldReturnBadRequestCodeWhenPayloadIsNotAProperJSON() throws Exception {
    String wrongPayloadTemplate =
        "{\"label\":\""
            + TEST_REPLICATION_REMOTE
            + "\",\"ref_name\":\"%s\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"%s\"},\"tree_object\":{\"type\":2,\"content\":\"%s\"},\"blobs\":[]}, \"async\":true,}";

    String refName = createRef();
    Optional<RevisionData> revisionDataOption = createRevisionData(refName);
    assertThat(revisionDataOption.isPresent()).isTrue();

    RevisionData revisionData = revisionDataOption.get();
    String sendObjectPayload = createPayload(wrongPayloadTemplate, refName, revisionData);

    HttpPost post = createRequest(sendObjectPayload);
    httpClientFactory.create(source).execute(post, assertHttpResponseCode(400), getContext());
  }

  private String createPayload(
      String wrongPayloadTemplate, String refName, RevisionData revisionData) {
    String sendObjectPayload =
        String.format(
            wrongPayloadTemplate,
            refName,
            encode(revisionData.getCommitObject().getContent()),
            encode(revisionData.getTreeObject().getContent()));
    return sendObjectPayload;
  }

  private HttpPost createRequest(String sendObjectPayload) {
    HttpPost post = new HttpPost(url);
    post.setEntity(new StringEntity(sendObjectPayload, StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));
    return post;
  }

  private String createRef() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushResult = createChange("topic", "test.txt", "test_content");
    return RefNames.changeMetaRef(pushResult.getChange().getId());
  }

  private Optional<RevisionData> createRevisionData(String refName) throws Exception {
    return revisionReader.read(Project.nameKey(project + TEST_REPLICATION_SUFFIX), refName);
  }

  private Object encode(byte[] content) {
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

  private HttpClientContext getContext() {
    HttpClientContext ctx = HttpClientContext.create();
    CredentialsProvider adapted = new BasicCredentialsProvider();
    adapted.setCredentials(
        AuthScope.ANY, new UsernamePasswordCredentials(admin.username(), admin.httpPassword()));
    ctx.setCredentialsProvider(adapted);
    return ctx;
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
    config.setString("replication", null, "instanceLabel", remoteName);
    config.setStringList("remote", remoteName, "url", replicaUrls);
    config.setString("remote", remoteName, "apiUrl", adminRestSession.url());
    config.setString("remote", remoteName, "fetch", "+refs/tags/*:refs/tags/*");
    config.setInt("remote", remoteName, "timeout", 600);
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
