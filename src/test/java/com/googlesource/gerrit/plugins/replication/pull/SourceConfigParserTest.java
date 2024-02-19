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
import com.googlesource.gerrit.plugins.replication.ConfigParser.ReplicationConfigurationException;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SourceConfigParserTest {
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
        assertThrows(
                ReplicationConfigurationException.class, () -> objectUnderTest.parseRemotes(config))
            .getMessage();
    assertThat(errorMessage).contains("invalid configuration");
  }
}
