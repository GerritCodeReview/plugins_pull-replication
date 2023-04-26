// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.transport;

import static com.google.common.truth.Truth.assertThat;
import static org.eclipse.jgit.transport.HttpConfig.EXTRA_HEADER;
import static org.eclipse.jgit.transport.HttpConfig.HTTP;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.pull.BearerTokenProvider;
import com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TransferConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TransportProviderTest {
  @Mock private SourceConfiguration sourceConfig;
  @Mock private CredentialsFactory cpFactory;
  @Mock private RemoteConfig remoteConfig;
  @Mock private BearerTokenProvider bearerTokenProvider;
  @Mock private Repository repository;
  @Mock private StoredConfig storedConfig;
  @Mock private org.eclipse.jgit.transport.TransferConfig transferConfig;

  @Before
  public void setup() {
    when(sourceConfig.getRemoteConfig()).thenReturn(remoteConfig);
    when(repository.getConfig()).thenReturn(storedConfig);
    String[] emptyHeaders = {};
    when(storedConfig.getStringList(HTTP, null, EXTRA_HEADER)).thenReturn(emptyHeaders);
    when(storedConfig.get(TransferConfig.KEY)).thenReturn(transferConfig);
  }

  private void verifyConstructor() {
    verify(sourceConfig).getRemoteConfig();
    verify(remoteConfig).getName();
    verify(bearerTokenProvider).get();
  }

  @Test
  public void shouldProvideTransportHttpWithBearerToken() throws URISyntaxException, IOException {
    when(bearerTokenProvider.get()).thenReturn(Optional.of("some-bearer-token"));

    TransportProvider transportProvider =
        new TransportProvider(sourceConfig, cpFactory, bearerTokenProvider);
    verifyConstructor();

    URIish urIish = new URIish("http://some-host/some-path");
    Transport transport = transportProvider.open(repository, urIish);

    // TODO(davido): We cannot access headers to check that the bearer token is set.
    assertThat(transport).isInstanceOf(TransportHttp.class);
  }

  @Test
  public void shouldProvideNativeTransportWhenNoBearerTokenProvided()
      throws URISyntaxException, IOException {

    when(bearerTokenProvider.get()).thenReturn(Optional.empty());

    TransportProvider transportProvider =
        new TransportProvider(sourceConfig, cpFactory, bearerTokenProvider);
    verifyConstructor();

    URIish urIish = new URIish("ssh://some-host/some-path");
    Transport transport = transportProvider.open(repository, urIish);

    assertThat(transport).isNotInstanceOf(TransportHttp.class);
  }

  @Test
  public void shouldProvideNativeTransportWhenNoHttpSchemeProvided()
      throws URISyntaxException, IOException {
    when(bearerTokenProvider.get()).thenReturn(Optional.of("some-bearer-token"));

    TransportProvider transportProvider =
        new TransportProvider(sourceConfig, cpFactory, bearerTokenProvider);
    verifyConstructor();

    URIish urIish = new URIish("ssh://some-host/some-path");
    Transport transport = transportProvider.open(repository, urIish);
    assertThat(transport).isNotInstanceOf(TransportHttp.class);
  }
}
