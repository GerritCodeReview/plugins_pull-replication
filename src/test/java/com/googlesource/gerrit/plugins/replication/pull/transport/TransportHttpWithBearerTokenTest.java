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

import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

public class TransportHttpWithBearerTokenTest {

  @Test
  public void cannotHandleURIWhenSchemaIsNeitherHttpNorHttps() throws URISyntaxException {
    URIish uriUnderTest = new URIish("some-uri").setScheme(null);
    boolean result = TransportHttpWithBearerToken.canHandle(uriUnderTest);
    assertThat(result).isFalse();
  }

  @Test
  public void cannotHandleURIWhenHostIsNotPresent() throws URISyntaxException {
    URIish uriUnderTest = new URIish("some-uri").setScheme("http").setHost(null);
    boolean result = TransportHttpWithBearerToken.canHandle(uriUnderTest);
    assertThat(result).isFalse();
  }

  @Test
  public void cannotHandleURIWhenPathIsNotPresent() throws URISyntaxException {
    URIish uriUnderTest =
        new URIish("some-uri").setScheme("http").setHost("some-host").setPath(null);
    boolean result = TransportHttpWithBearerToken.canHandle(uriUnderTest);
    assertThat(result).isFalse();
  }

  @Test
  public void canHandleURIWhenIsWellFormed() throws URISyntaxException {
    URIish uriUnderTest = new URIish("http://some-host/some-path");
    boolean result = TransportHttpWithBearerToken.canHandle(uriUnderTest);
    assertThat(result).isTrue();
  }
}
