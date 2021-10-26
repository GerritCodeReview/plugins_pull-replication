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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.filter.SyncRefsFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.http.Header;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class FetchRestApiClientTest {
  @Mock CredentialsProvider credentialProvider;
  @Mock CredentialsFactory credentials;
  @Mock HttpClient httpClient;
  @Mock SourceHttpClient.Factory httpClientFactory;
  @Mock FileBasedConfig config;
  @Mock ReplicationFileBasedConfig replicationConfig;
  @Mock Source source;
  @Captor ArgumentCaptor<HttpPost> httpPostCaptor;
  @Captor ArgumentCaptor<HttpPut> httpPutCaptor;

  String api = "http://gerrit-host";
  String pluginName = "pull-replication";
  String label = "Replication";
  String refName = RefNames.REFS_HEADS + "master";

  String expectedPayload =
      "{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":false}";
  String expectedAsyncPayload =
      "{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":true}";
  Header expectedHeader = new BasicHeader("Content-Type", "application/json");
  SyncRefsFilter syncRefsFilter;

  String expectedSendObjectPayload =
      "{\"label\":\"Replication\",\"ref_name\":\"refs/heads/master\",\"revision_data\":{\"commit_object\":{\"type\":1,\"content\":\"dHJlZSA3NzgxNGQyMTZhNmNhYjJkZGI5ZjI4NzdmYmJkMGZlYmRjMGZhNjA4CnBhcmVudCA5ODNmZjFhM2NmNzQ3MjVhNTNhNWRlYzhkMGMwNjEyMjEyOGY1YThkCmF1dGhvciBHZXJyaXQgVXNlciAxMDAwMDAwIDwxMDAwMDAwQDY5ZWMzOGYwLTM1MGUtNGQ5Yy05NmQ0LWJjOTU2ZjJmYWFhYz4gMTYxMDU3ODY0OCArMDEwMApjb21taXR0ZXIgR2Vycml0IENvZGUgUmV2aWV3IDxyb290QG1hY3plY2gtWFBTLTE1PiAxNjEwNTc4NjQ4ICswMTAwCgpVcGRhdGUgcGF0Y2ggc2V0IDEKClBhdGNoIFNldCAxOgoKKDEgY29tbWVudCkKClBhdGNoLXNldDogMQo\\u003d\"},\"tree_object\":{\"type\":2,\"content\":\"MTAwNjQ0IGJsb2IgYmIzODNmNTI0OWM2OGE0Y2M4YzgyYmRkMTIyOGI0YTg4ODNmZjZlOCAgICBmNzVhNjkwMDRhOTNiNGNjYzhjZTIxNWMxMjgwODYzNmMyYjc1Njc1\"},\"blobs\":[{\"type\":3,\"content\":\"ewogICJjb21tZW50cyI6IFsKICAgIHsKICAgICAgImtleSI6IHsKICAgICAgICAidXVpZCI6ICI5MGI1YWJmZl80ZjY3NTI2YSIsCiAgICAgICAgImZpbGVuYW1lIjogIi9DT01NSVRfTVNHIiwKICAgICAgICAicGF0Y2hTZXRJZCI6IDEKICAgICAgfSwKICAgICAgImxpbmVOYnIiOiA5LAogICAgICAiYXV0aG9yIjogewogICAgICAgICJpZCI6IDEwMDAwMDAKICAgICAgfSwKICAgICAgIndyaXR0ZW5PbiI6ICIyMDIxLTAxLTEzVDIyOjU3OjI4WiIsCiAgICAgICJzaWRlIjogMSwKICAgICAgIm1lc3NhZ2UiOiAidGVzdCBjb21tZW50IiwKICAgICAgInJhbmdlIjogewogICAgICAgICJzdGFydExpbmUiOiA5LAogICAgICAgICJzdGFydENoYXIiOiAyMSwKICAgICAgICAiZW5kTGluZSI6IDksCiAgICAgICAgImVuZENoYXIiOiAzNAogICAgICB9LAogICAgICAicmV2SWQiOiAiZjc1YTY5MDA0YTkzYjRjY2M4Y2UyMTVjMTI4MDg2MzZjMmI3NTY3NSIsCiAgICAgICJzZXJ2ZXJJZCI6ICI2OWVjMzhmMC0zNTBlLTRkOWMtOTZkNC1iYzk1NmYyZmFhYWMiLAogICAgICAidW5yZXNvbHZlZCI6IHRydWUKICAgIH0KICBdCn0\\u003d\"}]}}";
  String commitObject =
      "tree 77814d216a6cab2ddb9f2877fbbd0febdc0fa608\n"
          + "parent 983ff1a3cf74725a53a5dec8d0c06122128f5a8d\n"
          + "author Gerrit User 1000000 <1000000@69ec38f0-350e-4d9c-96d4-bc956f2faaac> 1610578648 +0100\n"
          + "committer Gerrit Code Review <root@maczech-XPS-15> 1610578648 +0100\n"
          + "\n"
          + "Update patch set 1\n"
          + "\n"
          + "Patch Set 1:\n"
          + "\n"
          + "(1 comment)\n"
          + "\n"
          + "Patch-set: 1\n";
  String treeObject =
      "100644 blob bb383f5249c68a4cc8c82bdd1228b4a8883ff6e8    f75a69004a93b4ccc8ce215c12808636c2b75675";
  String blobObject =
      "{\n"
          + "  \"comments\": [\n"
          + "    {\n"
          + "      \"key\": {\n"
          + "        \"uuid\": \"90b5abff_4f67526a\",\n"
          + "        \"filename\": \"/COMMIT_MSG\",\n"
          + "        \"patchSetId\": 1\n"
          + "      },\n"
          + "      \"lineNbr\": 9,\n"
          + "      \"author\": {\n"
          + "        \"id\": 1000000\n"
          + "      },\n"
          + "      \"writtenOn\": \"2021-01-13T22:57:28Z\",\n"
          + "      \"side\": 1,\n"
          + "      \"message\": \"test comment\",\n"
          + "      \"range\": {\n"
          + "        \"startLine\": 9,\n"
          + "        \"startChar\": 21,\n"
          + "        \"endLine\": 9,\n"
          + "        \"endChar\": 34\n"
          + "      },\n"
          + "      \"revId\": \"f75a69004a93b4ccc8ce215c12808636c2b75675\",\n"
          + "      \"serverId\": \"69ec38f0-350e-4d9c-96d4-bc956f2faaac\",\n"
          + "      \"unresolved\": true\n"
          + "    }\n"
          + "  ]\n"
          + "}";

  FetchRestApiClient objectUnderTest;

  @Before
  public void setup() throws ClientProtocolException, IOException {
    when(credentialProvider.supports(any()))
        .thenAnswer(
            new Answer<Boolean>() {

              @Override
              public Boolean answer(InvocationOnMock invocation) throws Throwable {
                CredentialItem.Username user = (CredentialItem.Username) invocation.getArgument(0);
                CredentialItem.Password password =
                    (CredentialItem.Password) invocation.getArgument(1);
                user.setValue("admin");
                password.setValue("secret".toCharArray());
                return true;
              }
            });

    when(credentialProvider.get(any(), any(CredentialItem.class))).thenReturn(true);
    when(credentials.create(anyString())).thenReturn(credentialProvider);
    when(replicationConfig.getConfig()).thenReturn(config);
    when(config.getStringList("replication", null, "syncRefs")).thenReturn(new String[0]);
    when(source.getRemoteConfigName()).thenReturn("Replication");
    when(config.getString("replication", null, "instanceLabel")).thenReturn(label);

    HttpResult httpResult = new HttpResult(SC_CREATED, Optional.of("result message"));
    when(httpClient.execute(any(HttpPost.class), any(), any())).thenReturn(httpResult);
    when(httpClientFactory.create(any())).thenReturn(httpClient);
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials, httpClientFactory, replicationConfig, syncRefsFilter, pluginName, source);
  }

  @Test
  public void shouldCallFetchEndpoint()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo("/a/projects/test_repo/pull-replication~fetch");
  }

  @Test
  public void shouldByDefaultCallSyncFetchForAllRefs()
      throws ClientProtocolException, IOException, URISyntaxException {

    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials, httpClientFactory, replicationConfig, syncRefsFilter, pluginName, source);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldCallAsyncFetchForAllRefs()
      throws ClientProtocolException, IOException, URISyntaxException {

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"NO_SYNC_REFS"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials, httpClientFactory, replicationConfig, syncRefsFilter, pluginName, source);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedAsyncPayload);
  }

  @Test
  public void shouldCallSyncFetchOnlyForMetaRef()
      throws ClientProtocolException, IOException, URISyntaxException {
    String metaRefName = "refs/changes/01/101/meta";
    String expectedMetaRefPayload =
        "{\"label\":\"Replication\", \"ref_name\": \"" + metaRefName + "\", \"async\":false}";

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"^refs\\/changes\\/.*\\/meta"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials, httpClientFactory, replicationConfig, syncRefsFilter, pluginName, source);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));
    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());
    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedAsyncPayload);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), metaRefName, new URIish(api));
    verify(httpClient, times(2)).execute(httpPostCaptor.capture(), any(), any());
    httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedMetaRefPayload);
  }

  @Test
  public void shouldCallFetchEndpointWithPayload()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldSetContentTypeHeader()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  @Test
  public void shouldCallSendObjectEndpoint()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callSendObject(
        Project.nameKey("test_repo"), refName, createSampleRevisionData(), new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo("/a/projects/test_repo/pull-replication~apply-object");
  }

  @Test
  public void shouldCallSendObjectEndpointWithPayload()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callSendObject(
        Project.nameKey("test_repo"), refName, createSampleRevisionData(), new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedSendObjectPayload);
  }

  @Test
  public void shouldSetContentTypeHeaderForSendObjectCall()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  @Test
  public void shouldThrowExceptionWhenInstanceLabelIsNull() {
    when(config.getString("replication", null, "instanceLabel")).thenReturn(null);
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                source));
  }

  @Test
  public void shouldTrimInstanceLabel() {
    when(config.getString("replication", null, "instanceLabel")).thenReturn(" ");
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                source));
  }

  @Test
  public void shouldThrowExceptionWhenInstanceLabelIsEmpty() {
    when(config.getString("replication", null, "instanceLabel")).thenReturn("");
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                source));
  }

  @Test
  public void shouldCallCreateProjectEndpoint()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.createProject(Project.nameKey("test_repo"), new URIish(api));

    verify(httpClient, times(1)).execute(httpPutCaptor.capture(), any(), any());

    HttpPut httpPut = httpPutCaptor.getValue();
    assertThat(httpPut.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPut.getURI().getPath()).isEqualTo("/a/projects/test_repo");
  }

  public String readPayload(HttpPost entity) throws UnsupportedOperationException, IOException {
    ByteBuffer buf = IO.readWholeStream(entity.getEntity().getContent(), 1024);
    return RawParseUtils.decode(buf.array(), buf.arrayOffset(), buf.limit()).trim();
  }

  private RevisionData createSampleRevisionData() {
    RevisionObjectData commitData =
        new RevisionObjectData(Constants.OBJ_COMMIT, commitObject.getBytes());
    RevisionObjectData treeData = new RevisionObjectData(Constants.OBJ_TREE, treeObject.getBytes());
    RevisionObjectData blobData = new RevisionObjectData(Constants.OBJ_BLOB, blobObject.getBytes());
    return new RevisionData(commitData, treeData, Lists.newArrayList(blobData));
  }
}
