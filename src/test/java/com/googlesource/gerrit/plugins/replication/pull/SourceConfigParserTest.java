// Copyright (C) 2024 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.replication.RemoteConfiguration;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SourceConfigParserTest {
  private static final String TEST_REMOTE_URL = "http://git.foo.remote.url/${name}";
  private static final String TEST_REMOTE_FETCH_REFSPEC = "refs/heads/mybranch:refs/heads/mybranch";
  private static final String TEST_REMOTE_NAME = "testremote";

  @Mock ReplicationConfig replicationConfigMock;

  private SourceConfigParser objectUnderTest;

  @Before
  public void setup() {
    objectUnderTest = new SourceConfigParser(false, Providers.of(replicationConfigMock));
  }

  @Test
  public void shouldThrowWhenBothFetchEveryAndApiUrlsConfigured() {
    // given
    Config config = new Config();
    config.setString("remote", "test_remote", "fetch", "+refs/*:refs/*");
    config.setString("remote", "test_remote", "projects", "to_be_replicated");

    config.setString("remote", "test_remote", "apiUrl", "http://foo.bar/api");
    config.setString("remote", "test_remote", "fetchEvery", "1s");

    // when/then
    String errorMessage =
        assertThrows(ConfigInvalidException.class, () -> objectUnderTest.parseRemotes(config))
            .getMessage();
    assertThat(errorMessage).contains("invalid configuration");
  }

  @Test
  public void shouldIgnoreRemoteWithoutFetchConfiguration() throws Exception {
    Config config = new Config();
    config.fromText("[remote \"foo\"]\n" + "url = http://git.foo.remote.url/repo");

    assertThat(objectUnderTest.parseRemotes(config)).isEmpty();
  }

  @Test
  public void shouldParseRemoteWithFetchConfiguration() throws Exception {
    Config config = new Config();
    config.fromText(
        String.format(
            "[remote \"%s\"]\n" + "url = %s\n" + "fetch = %s",
            TEST_REMOTE_NAME, TEST_REMOTE_URL, TEST_REMOTE_FETCH_REFSPEC));

    List<RemoteConfiguration> remotes = objectUnderTest.parseRemotes(config);
    assertThat(remotes).hasSize(1);
    RemoteConfiguration remoteConfig = remotes.get(0);

    assertThat(remoteConfig.getUrls()).containsExactly(TEST_REMOTE_URL);
    assertThat(remoteConfig.getRemoteConfig().getFetchRefSpecs())
        .containsExactly(new RefSpec(TEST_REMOTE_FETCH_REFSPEC));
  }
}
