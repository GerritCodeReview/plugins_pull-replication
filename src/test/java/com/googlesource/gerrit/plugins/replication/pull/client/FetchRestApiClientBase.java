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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.BearerTokenProvider;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.api.data.BatchApplyObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.filter.SyncRefsFilter;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.http.Header;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

public abstract class FetchRestApiClientBase {
  private static final boolean IS_REF_UPDATE = false;

  @Mock CredentialsProvider credentialProvider;
  @Mock CredentialsFactory credentials;
  @Mock HttpClient httpClient;
  @Mock SourceHttpClient.Factory httpClientFactory;
  @Mock FileBasedConfig config;
  @Mock ReplicationFileBasedConfig replicationConfig;
  @Mock Source source;
  @Mock BearerTokenProvider bearerTokenProvider;
  @Captor ArgumentCaptor<HttpPost> httpPostCaptor;
  @Captor ArgumentCaptor<HttpPut> httpPutCaptor;
  @Captor ArgumentCaptor<HttpDelete> httpDeleteCaptor;
  String api = "http://gerrit-host";
  String pluginName = "pull-replication";
  String instanceId = "Replication";
  String refName = RefNames.REFS_HEADS + "master";
  long eventCreatedOn = 1684875939;

  String expectedPayload =
      "{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":false}";
  String expectedAsyncPayload =
      "{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":true}";
  Header expectedHeader = new BasicHeader("Content-Type", "application/json");
  SyncRefsFilter syncRefsFilter;

  String commitObjectId = "9f8d52853089a3cf00c02ff7bd0817bd4353a95a";
  String treeObjectId = "77814d216a6cab2ddb9f2877fbbd0febdc0fa608";
  String blobObjectId = "bb383f5249c68a4cc8c82bdd1228b4a8883ff6e8";

  String expectedSendObjectPayload =
      "{\"label\":\"Replication\",\"ref_name\":\"refs/heads/master\",\"event_created_on\":"
          + eventCreatedOn
          + ",\"revision_data\":{\"commit_object\":{\"sha1\":\""
          + commitObjectId
          + "\",\"type\":1,\"content\":\"dHJlZSA3NzgxNGQyMTZhNmNhYjJkZGI5ZjI4NzdmYmJkMGZlYmRjMGZhNjA4CnBhcmVudCA5ODNmZjFhM2NmNzQ3MjVhNTNhNWRlYzhkMGMwNjEyMjEyOGY1YThkCmF1dGhvciBHZXJyaXQgVXNlciAxMDAwMDAwIDwxMDAwMDAwQDY5ZWMzOGYwLTM1MGUtNGQ5Yy05NmQ0LWJjOTU2ZjJmYWFhYz4gMTYxMDU3ODY0OCArMDEwMApjb21taXR0ZXIgR2Vycml0IENvZGUgUmV2aWV3IDxyb290QG1hY3plY2gtWFBTLTE1PiAxNjEwNTc4NjQ4ICswMTAwCgpVcGRhdGUgcGF0Y2ggc2V0IDEKClBhdGNoIFNldCAxOgoKKDEgY29tbWVudCkKClBhdGNoLXNldDogMQo\\u003d\"},\"tree_object\":{\"sha1\":\""
          + treeObjectId
          + "\",\"type\":2,\"content\":\"MTAwNjQ0IGJsb2IgYmIzODNmNTI0OWM2OGE0Y2M4YzgyYmRkMTIyOGI0YTg4ODNmZjZlOCAgICBmNzVhNjkwMDRhOTNiNGNjYzhjZTIxNWMxMjgwODYzNmMyYjc1Njc1\"},\"blobs\":[{\"sha1\":\""
          + blobObjectId
          + "\",\"type\":3,\"content\":\"ewogICJjb21tZW50cyI6IFsKICAgIHsKICAgICAgImtleSI6IHsKICAgICAgICAidXVpZCI6ICI5MGI1YWJmZl80ZjY3NTI2YSIsCiAgICAgICAgImZpbGVuYW1lIjogIi9DT01NSVRfTVNHIiwKICAgICAgICAicGF0Y2hTZXRJZCI6IDEKICAgICAgfSwKICAgICAgImxpbmVOYnIiOiA5LAogICAgICAiYXV0aG9yIjogewogICAgICAgICJpZCI6IDEwMDAwMDAKICAgICAgfSwKICAgICAgIndyaXR0ZW5PbiI6ICIyMDIxLTAxLTEzVDIyOjU3OjI4WiIsCiAgICAgICJzaWRlIjogMSwKICAgICAgIm1lc3NhZ2UiOiAidGVzdCBjb21tZW50IiwKICAgICAgInJhbmdlIjogewogICAgICAgICJzdGFydExpbmUiOiA5LAogICAgICAgICJzdGFydENoYXIiOiAyMSwKICAgICAgICAiZW5kTGluZSI6IDksCiAgICAgICAgImVuZENoYXIiOiAzNAogICAgICB9LAogICAgICAicmV2SWQiOiAiZjc1YTY5MDA0YTkzYjRjY2M4Y2UyMTVjMTI4MDg2MzZjMmI3NTY3NSIsCiAgICAgICJzZXJ2ZXJJZCI6ICI2OWVjMzhmMC0zNTBlLTRkOWMtOTZkNC1iYzk1NmYyZmFhYWMiLAogICAgICAidW5yZXNvbHZlZCI6IHRydWUKICAgIH0KICBdCn0\\u003d\"}]}}";
  String commitObject =
      "tree "
          + treeObjectId
          + "\n"
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
      "100644 blob " + blobObjectId + "    f75a69004a93b4ccc8ce215c12808636c2b75675";
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

  FetchApiClient objectUnderTest;

  protected abstract String urlAuthenticationPrefix();

  protected abstract void assertAuthentication(HttpRequestBase httpRequest);

  @Test
  public void shouldCallFetchEndpoint() throws Exception {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/test_repo/pull-replication~fetch", urlAuthenticationPrefix()));
    assertAuthentication(httpPost);
  }

  @Test
  public void shouldCallBatchFetchEndpoint() throws Exception {

    objectUnderTest.callBatchFetch(
        Project.nameKey("test_repo"),
        List.of(refName, RefNames.REFS_HEADS + "test"),
        new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/test_repo/pull-replication~batch-fetch", urlAuthenticationPrefix()));
    assertAuthentication(httpPost);
  }

  @Test
  public void shouldByDefaultCallSyncFetchForAllRefs() throws Exception {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldCallAsyncFetchForAllRefs() throws Exception {

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"NO_SYNC_REFS"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedAsyncPayload);
  }

  @Test
  public void shouldCallAsyncBatchFetchForAllRefs() throws Exception {

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"NO_SYNC_REFS"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);

    String testRef = RefNames.REFS_HEADS + "test";
    List<String> refs = List.of(refName, testRef);
    objectUnderTest.callBatchFetch(Project.nameKey("test_repo"), refs, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    String expectedPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \""
            + refName
            + "\", \"async\":true},"
            + "{\"label\":\"Replication\", \"ref_name\": \""
            + refs.get(1)
            + "\", \"async\":true}"
            + "]";
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldCallSyncFetchOnlyForMetaRef() throws Exception {
    String metaRefName = "refs/changes/01/101/meta";
    String expectedMetaRefPayload =
        "{\"label\":\"Replication\", \"ref_name\": \"" + metaRefName + "\", \"async\":false}";

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"^refs\\/changes\\/.*\\/meta"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));
    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());
    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedAsyncPayload);

    objectUnderTest.callFetch(Project.nameKey("test_repo"), metaRefName, new URIish(api));
    verify(httpClient, times(2)).execute(httpPostCaptor.capture(), any());
    httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedMetaRefPayload);
  }

  @Test
  public void shouldCallSyncBatchFetchOnlyForMetaRef() throws Exception {
    String metaRefName = "refs/changes/01/101/meta";
    String expectedMetaRefPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \"" + metaRefName + "\", \"async\":false}]";

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"^refs\\/changes\\/.*\\/meta"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);

    objectUnderTest.callBatchFetch(
        Project.nameKey("test_repo"), List.of(metaRefName), new URIish(api));
    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());
    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedMetaRefPayload);
  }

  @Test
  public void shouldCallFetchEndpointWithPayload() throws Exception {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldCallBatchFetchEndpointWithPayload() throws Exception {

    String testRef = RefNames.REFS_HEADS + "test";
    List<String> refs = List.of(refName, testRef);
    objectUnderTest.callBatchFetch(Project.nameKey("test_repo"), refs, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    String expectedPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \""
            + refName
            + "\", \"async\":false},"
            + "{\"label\":\"Replication\", \"ref_name\": \""
            + refs.get(1)
            + "\", \"async\":false}"
            + "]";
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldExecuteOneFetchCallForAsyncAndOneForSyncRefsDuringBatchFetch()
      throws Exception {

    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"^refs\\/heads\\/test"});
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    String testRef = RefNames.REFS_HEADS + "test";
    List<String> refs = List.of(refName, testRef);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);
    objectUnderTest.callBatchFetch(Project.nameKey("test_repo"), refs, new URIish(api));

    verify(httpClient, times(2)).execute(httpPostCaptor.capture(), any());

    List<HttpPost> httpPosts = httpPostCaptor.getAllValues();
    String expectedSyncPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \""
            + refs.get(1)
            + "\", \"async\":false}"
            + "]";
    String expectedAsyncPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":true}]";

    assertThat(readPayload(httpPosts.get(0))).isEqualTo(expectedAsyncPayload);
    assertThat(readPayload(httpPosts.get(1))).isEqualTo(expectedSyncPayload);
  }

  @Test
  public void shouldNotExecuteSyncFetchCallWhenAsyncCallFailsDuringBatchFetch() throws Exception {
    when(config.getStringList("replication", null, "syncRefs"))
        .thenReturn(new String[] {"^refs\\/heads\\/test"});
    when(httpClient.execute(any(), any())).thenReturn(new HttpResult(500, Optional.of("BOOM")));
    syncRefsFilter = new SyncRefsFilter(replicationConfig);
    String testRef = RefNames.REFS_HEADS + "test";
    List<String> refs = List.of(refName, testRef);
    objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            instanceId,
            bearerTokenProvider,
            source);
    objectUnderTest.callBatchFetch(Project.nameKey("test_repo"), refs, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    String expectedAsyncPayload =
        "[{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\", \"async\":true}]";

    assertThat(readPayload(httpPost)).isEqualTo(expectedAsyncPayload);
  }

  @Test
  public void shouldSetContentTypeHeader() throws Exception {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  @Test
  public void shouldSetContentTypeHeaderInBatchFetch() throws Exception {

    objectUnderTest.callBatchFetch(Project.nameKey("test_repo"), List.of(refName), new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  @Test
  public void shouldCallSendObjectEndpoint() throws Exception {

    objectUnderTest.callSendObject(
        Project.nameKey("test_repo"),
        refName,
        eventCreatedOn,
        IS_REF_UPDATE,
        createSampleRevisionData(),
        new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/test_repo/pull-replication~apply-object", urlAuthenticationPrefix()));
    assertAuthentication(httpPost);
  }

  @Test
  public void shouldCallSendObjectEndpointWithPayload() throws Exception {

    objectUnderTest.callSendObject(
        Project.nameKey("test_repo"),
        refName,
        eventCreatedOn,
        IS_REF_UPDATE,
        createSampleRevisionData(),
        new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedSendObjectPayload);
  }

  @Test
  public void shouldSetContentTypeHeaderForSendObjectCall() throws Exception {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  @Test
  public void shouldThrowExceptionWhenInstanceLabelIsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                null,
                bearerTokenProvider,
                source));
  }

  @Test
  public void shouldTrimInstanceLabel() {
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                " ",
                bearerTokenProvider,
                source));
  }

  @Test
  public void shouldThrowExceptionWhenInstanceLabelIsEmpty() {
    assertThrows(
        NullPointerException.class,
        () ->
            new FetchRestApiClient(
                credentials,
                httpClientFactory,
                replicationConfig,
                syncRefsFilter,
                pluginName,
                "",
                bearerTokenProvider,
                source));
  }

  @Test
  public void shouldUseReplicationLabelWhenProvided() throws Exception {
    when(config.getString("replication", null, "instanceLabel")).thenReturn(instanceId);
    FetchRestApiClient objectUnderTest =
        new FetchRestApiClient(
            credentials,
            httpClientFactory,
            replicationConfig,
            syncRefsFilter,
            pluginName,
            "",
            bearerTokenProvider,
            source);
    objectUnderTest.callFetch(Project.nameKey("test_repo"), refName, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldCallInitProjectEndpoint() throws Exception {

    objectUnderTest.initProject(Project.nameKey("test_repo"), new URIish(api));

    verify(httpClient, times(1)).execute(httpPutCaptor.capture(), any());

    HttpPut httpPut = httpPutCaptor.getValue();
    assertThat(httpPut.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPut.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/plugins/pull-replication/init-project/test_repo.git",
                urlAuthenticationPrefix()));
    assertAuthentication(httpPut);
  }

  @Test
  public void shouldCallDeleteProjectEndpoint() throws Exception {

    objectUnderTest.deleteProject(Project.nameKey("test_repo"), new URIish(api));

    verify(httpClient, times(1)).execute(httpDeleteCaptor.capture(), any());

    HttpDelete httpDelete = httpDeleteCaptor.getValue();
    assertThat(httpDelete.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpDelete.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/test_repo/pull-replication~delete-project",
                urlAuthenticationPrefix()));
    assertAuthentication(httpDelete);
  }

  @Test
  public void shouldCallUpdateHEADEndpoint() throws Exception {
    String newHead = "newHead";
    String projectName = "aProject";
    objectUnderTest.updateHead(Project.nameKey(projectName), newHead, new URIish(api));

    verify(httpClient, times(1)).execute(httpPutCaptor.capture(), any());

    HttpPut httpPut = httpPutCaptor.getValue();
    String payload =
        CharStreams.toString(
            new InputStreamReader(httpPut.getEntity().getContent(), Charsets.UTF_8));

    assertThat(httpPut.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPut.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/%s/pull-replication~HEAD", urlAuthenticationPrefix(), projectName));
    assertThat(payload).isEqualTo(String.format("{\"ref\": \"%s\"}", newHead));
    assertAuthentication(httpPut);
  }

  @Test
  public void shouldCallBatchSendObjectEndpoint() throws Exception {

    List<BatchApplyObjectData> batchApplyObjects = new ArrayList<>();
    batchApplyObjects.add(
        BatchApplyObjectData.create(refName, Optional.of(createSampleRevisionData("a")), false));

    objectUnderTest.callBatchSendObject(
        Project.nameKey("test_repo"), batchApplyObjects, eventCreatedOn, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo(
            String.format(
                "%s/projects/test_repo/pull-replication~batch-apply-object",
                urlAuthenticationPrefix()));
    assertAuthentication(httpPost);
  }

  @Test
  public void shouldCallBatchApplyObjectEndpointWithAListOfRefsInPayload() throws Exception {
    List<BatchApplyObjectData> batchApplyObjects = new ArrayList<>();
    RevisionData revisionA = createSampleRevisionData("a");
    RevisionData revisionB = createSampleRevisionData("b");
    String refNameB = "refs/heads/b";
    batchApplyObjects.add(BatchApplyObjectData.create(refName, Optional.of(revisionA), false));
    batchApplyObjects.add(BatchApplyObjectData.create(refNameB, Optional.of(revisionB), false));

    objectUnderTest.callBatchSendObject(
        Project.nameKey("test_repo"), batchApplyObjects, eventCreatedOn, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();

    String expectedSendObjectsPayload =
        "[{\"label\":\"Replication\",\"ref_name\":\""
            + refName
            + "\",\"event_created_on\":"
            + eventCreatedOn
            + ",\"revision_data\":{\"commit_object\":{\"sha1\":\""
            + revisionA.getCommitObject().getSha1()
            + "\",\"type\":1,\"content\":\"Y29tbWl0YWNvbnRlbnQ\\u003d\"},\"tree_object\":{\"sha1\":\""
            + revisionA.getTreeObject().getSha1()
            + "\",\"type\":2,\"content\":\"dHJlZWFjb250ZW50\"},\"blobs\":[{\"sha1\":\""
            + revisionA.getBlobs().get(0).getSha1()
            + "\",\"type\":3,\"content\":\"YmxvYmFjb250ZW50\"}]}},"
            + "{\"label\":\"Replication\",\"ref_name\":\""
            + refNameB
            + "\",\"event_created_on\":"
            + eventCreatedOn
            + ",\"revision_data\":{\"commit_object\":{\"sha1\":\""
            + revisionB.getCommitObject().getSha1()
            + "\",\"type\":1,\"content\":\"Y29tbWl0YmNvbnRlbnQ\\u003d\"},\"tree_object\":{\"sha1\":\""
            + revisionB.getTreeObject().getSha1()
            + "\",\"type\":2,\"content\":\"dHJlZWJjb250ZW50\"},\"blobs\":[{\"sha1\":\""
            + revisionB.getBlobs().get(0).getSha1()
            + "\",\"type\":3,\"content\":\"YmxvYmJjb250ZW50\"}]}}]";
    assertThat(readPayload(httpPost)).isEqualTo(expectedSendObjectsPayload);
  }

  @Test
  public void shouldCallBatchApplyObjectEndpointWithNoRevisionDataForDeletes() throws Exception {
    List<BatchApplyObjectData> batchApplyObjects = new ArrayList<>();
    batchApplyObjects.add(BatchApplyObjectData.create(refName, Optional.empty(), true));

    objectUnderTest.callBatchSendObject(
        Project.nameKey("test_repo"), batchApplyObjects, eventCreatedOn, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any());

    HttpPost httpPost = httpPostCaptor.getValue();

    String expectedSendObjectsPayload =
        "[{\"label\":\"Replication\",\"ref_name\":\""
            + refName
            + "\",\"event_created_on\":"
            + eventCreatedOn
            + "}]";
    assertThat(readPayload(httpPost)).isEqualTo(expectedSendObjectsPayload);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowExceptionIfDeleteFlagIsSetButRevisionDataIsPresentForBatchSendEndpoint()
      throws Exception {
    List<BatchApplyObjectData> batchApplyObjects = new ArrayList<>();
    batchApplyObjects.add(
        BatchApplyObjectData.create(refName, Optional.of(createSampleRevisionData()), true));

    objectUnderTest.callBatchSendObject(
        Project.nameKey("test_repo"), batchApplyObjects, eventCreatedOn, new URIish(api));
  }

  public String readPayload(HttpPost entity) throws Exception {
    ByteBuffer buf = IO.readWholeStream(entity.getEntity().getContent(), 1024);
    return RawParseUtils.decode(buf.array(), buf.arrayOffset(), buf.limit()).trim();
  }

  private RevisionData createSampleRevisionData(String prefix) {
    String commitPrefix = "commit" + prefix;
    String treePrefix = "tree" + prefix;
    String blobPrefix = "blob" + prefix;
    return createSampleRevisionData(
        commitPrefix,
        commitPrefix + "content",
        treePrefix,
        treePrefix + "content",
        blobPrefix,
        blobPrefix + "content");
  }

  private RevisionData createSampleRevisionData(
      String commitObjectId,
      String commitContent,
      String treeObjectId,
      String treeContent,
      String blobObjectId,
      String blobContent) {
    RevisionObjectData commitData =
        new RevisionObjectData(commitObjectId, Constants.OBJ_COMMIT, commitContent.getBytes());
    RevisionObjectData treeData =
        new RevisionObjectData(treeObjectId, Constants.OBJ_TREE, treeContent.getBytes());
    RevisionObjectData blobData =
        new RevisionObjectData(blobObjectId, Constants.OBJ_BLOB, blobContent.getBytes());
    return new RevisionData(
        Collections.emptyList(), commitData, treeData, Lists.newArrayList(blobData));
  }

  private RevisionData createSampleRevisionData() {
    return createSampleRevisionData(
        commitObjectId, commitObject, treeObjectId, treeObject, blobObjectId, blobObject);
  }
}
