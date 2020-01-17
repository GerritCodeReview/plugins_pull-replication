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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResponseHandler.HttpResult;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import org.apache.http.Header;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.eclipse.jgit.lib.ObjectId;
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
  @Mock CloseableHttpClient httpClient;
  @Mock FileBasedConfig config;
  @Mock ReplicationFileBasedConfig replicationConfig;
  @Mock Source source;
  @Captor ArgumentCaptor<HttpPost> httpPostCaptor;

  String api = "http://gerrit-host/";
  String label = "Replication";
  ObjectId objectId = ObjectId.fromString("c90989ed7a8ab01f1bdd022872428f020b866358");

  String expectedPayload =
      "{\"label\":\"Replication\", \"object_id\": \"c90989ed7a8ab01f1bdd022872428f020b866358\"}";
  Header expectedHeader = new BasicHeader("Content-Type", "application/json");

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

    HttpResult httpResult = new HttpResult(true, "result message");
    when(httpClient.execute(any(HttpPost.class), any(), any())).thenReturn(httpResult);

    objectUnderTest = new FetchRestApiClient(credentials, httpClient, replicationConfig, source);
  }

  @Test
  public void shouldCallFetchEndpoint()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), objectId, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getURI().getHost()).isEqualTo("gerrit-host");
    assertThat(httpPost.getURI().getPath())
        .isEqualTo("/a/projects/test_repo/pull-replication~fetch");
  }

  @Test
  public void shouldCallFetchEndpointWithPayload()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), objectId, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(readPayload(httpPost)).isEqualTo(expectedPayload);
  }

  @Test
  public void shouldSetContentTypeHeader()
      throws ClientProtocolException, IOException, URISyntaxException {

    objectUnderTest.callFetch(Project.nameKey("test_repo"), objectId, new URIish(api));

    verify(httpClient, times(1)).execute(httpPostCaptor.capture(), any(), any());

    HttpPost httpPost = httpPostCaptor.getValue();
    assertThat(httpPost.getLastHeader("Content-Type").getValue())
        .isEqualTo(expectedHeader.getValue());
  }

  public String readPayload(HttpPost entity) throws UnsupportedOperationException, IOException {
    ByteBuffer buf = IO.readWholeStream(entity.getEntity().getContent(), 1024);
    return RawParseUtils.decode(buf.array(), buf.arrayOffset(), buf.limit()).trim();
  }
}
