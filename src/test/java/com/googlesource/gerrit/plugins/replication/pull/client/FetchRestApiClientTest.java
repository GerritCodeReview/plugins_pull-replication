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
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Optional;
import org.apache.http.Header;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
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

  String api = "http://gerrit-host";
  String label = "Replication";
  String refName = RefNames.REFS_HEADS + "master";

  String expectedPayload = "{\"label\":\"Replication\", \"ref_name\": \"" + refName + "\"}";
  Header expectedHeader = new BasicHeader("Content-Type", "application/json");

  String expectedSendObjectPayload =
      "{\"label\":\"Replication\",\"ref_name\":\"refs/heads/master\",\"revision_data\":{\"object_blob\":{\"type\":1,\"content\":[116,114,101,101,32,55,55,56,49,52,100,50,49,54,97,54,99,97,98,50,100,100,98,57,102,50,56,55,55,102,98,98,100,48,102,101,98,100,99,48,102,97,54,48,56,10,112,97,114,101,110,116,32,57,56,51,102,102,49,97,51,99,102,55,52,55,50,53,97,53,51,97,53,100,101,99,56,100,48,99,48,54,49,50,50,49,50,56,102,53,97,56,100,10,97,117,116,104,111,114,32,71,101,114,114,105,116,32,85,115,101,114,32,49,48,48,48,48,48,48,32,60,49,48,48,48,48,48,48,64,54,57,101,99,51,56,102,48,45,51,53,48,101,45,52,100,57,99,45,57,54,100,52,45,98,99,57,53,54,102,50,102,97,97,97,99,62,32,49,54,49,48,53,55,56,54,52,56,32,43,48,49,48,48,10,99,111,109,109,105,116,116,101,114,32,71,101,114,114,105,116,32,67,111,100,101,32,82,101,118,105,101,119,32,60,114,111,111,116,64,109,97,99,122,101,99,104,45,88,80,83,45,49,53,62,32,49,54,49,48,53,55,56,54,52,56,32,43,48,49,48,48,10,10,85,112,100,97,116,101,32,112,97,116,99,104,32,115,101,116,32,49,10,10,80,97,116,99,104,32,83,101,116,32,49,58,10,10,40,49,32,99,111,109,109,101,110,116,41,10,10,80,97,116,99,104,45,115,101,116,58,32,49,10]},\"tree_object\":{\"type\":2,\"content\":[49,48,48,54,52,52,32,98,108,111,98,32,98,98,51,56,51,102,53,50,52,57,99,54,56,97,52,99,99,56,99,56,50,98,100,100,49,50,50,56,98,52,97,56,56,56,51,102,102,54,101,56,32,32,32,32,102,55,53,97,54,57,48,48,52,97,57,51,98,52,99,99,99,56,99,101,50,49,53,99,49,50,56,48,56,54,51,54,99,50,98,55,53,54,55,53]},\"blobs\":[{\"type\":3,\"content\":[123,10,32,32,34,99,111,109,109,101,110,116,115,34,58,32,91,10,32,32,32,32,123,10,32,32,32,32,32,32,34,107,101,121,34,58,32,123,10,32,32,32,32,32,32,32,32,34,117,117,105,100,34,58,32,34,57,48,98,53,97,98,102,102,95,52,102,54,55,53,50,54,97,34,44,10,32,32,32,32,32,32,32,32,34,102,105,108,101,110,97,109,101,34,58,32,34,47,67,79,77,77,73,84,95,77,83,71,34,44,10,32,32,32,32,32,32,32,32,34,112,97,116,99,104,83,101,116,73,100,34,58,32,49,10,32,32,32,32,32,32,125,44,10,32,32,32,32,32,32,34,108,105,110,101,78,98,114,34,58,32,57,44,10,32,32,32,32,32,32,34,97,117,116,104,111,114,34,58,32,123,10,32,32,32,32,32,32,32,32,34,105,100,34,58,32,49,48,48,48,48,48,48,10,32,32,32,32,32,32,125,44,10,32,32,32,32,32,32,34,119,114,105,116,116,101,110,79,110,34,58,32,34,50,48,50,49,45,48,49,45,49,51,84,50,50,58,53,55,58,50,56,90,34,44,10,32,32,32,32,32,32,34,115,105,100,101,34,58,32,49,44,10,32,32,32,32,32,32,34,109,101,115,115,97,103,101,34,58,32,34,116,101,115,116,32,99,111,109,109,101,110,116,34,44,10,32,32,32,32,32,32,34,114,97,110,103,101,34,58,32,123,10,32,32,32,32,32,32,32,32,34,115,116,97,114,116,76,105,110,101,34,58,32,57,44,10,32,32,32,32,32,32,32,32,34,115,116,97,114,116,67,104,97,114,34,58,32,50,49,44,10,32,32,32,32,32,32,32,32,34,101,110,100,76,105,110,101,34,58,32,57,44,10,32,32,32,32,32,32,32,32,34,101,110,100,67,104,97,114,34,58,32,51,52,10,32,32,32,32,32,32,125,44,10,32,32,32,32,32,32,34,114,101,118,73,100,34,58,32,34,102,55,53,97,54,57,48,48,52,97,57,51,98,52,99,99,99,56,99,101,50,49,53,99,49,50,56,48,56,54,51,54,99,50,98,55,53,54,55,53,34,44,10,32,32,32,32,32,32,34,115,101,114,118,101,114,73,100,34,58,32,34,54,57,101,99,51,56,102,48,45,51,53,48,101,45,52,100,57,99,45,57,54,100,52,45,98,99,57,53,54,102,50,102,97,97,97,99,34,44,10,32,32,32,32,32,32,34,117,110,114,101,115,111,108,118,101,100,34,58,32,116,114,117,101,10,32,32,32,32,125,10,32,32,93,10,125]}]},\"async\":false}";
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
    when(source.getRemoteConfigName()).thenReturn("Replication");
    when(config.getString("replication", null, "instanceLabel")).thenReturn(label);

    HttpResult httpResult = new HttpResult(SC_CREATED, Optional.of("result message"));
    when(httpClient.execute(any(HttpPost.class), any(), any())).thenReturn(httpResult);
    when(httpClientFactory.create(any())).thenReturn(httpClient);
    objectUnderTest =
        new FetchRestApiClient(credentials, httpClientFactory, replicationConfig, source);
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
        () -> new FetchRestApiClient(credentials, httpClientFactory, replicationConfig, source));
  }

  @Test
  public void shouldTrimInstanceLabel() {
    when(config.getString("replication", null, "instanceLabel")).thenReturn(" ");
    assertThrows(
        NullPointerException.class,
        () -> new FetchRestApiClient(credentials, httpClientFactory, replicationConfig, source));
  }

  @Test
  public void shouldThrowExceptionWhenInstanceLabelIsEmpty() {
    when(config.getString("replication", null, "instanceLabel")).thenReturn("");
    assertThrows(
        NullPointerException.class,
        () -> new FetchRestApiClient(credentials, httpClientFactory, replicationConfig, source));
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
