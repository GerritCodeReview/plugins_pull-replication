// Copyright (C) 2023 The Android Open Source Project
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

import com.googlesource.gerrit.plugins.replication.pull.fetch.InexistentRefTransportException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.PermanentTransportException;
import com.jcraft.jsch.JSchException;
import org.eclipse.jgit.errors.TransportException;
import org.junit.Test;

public class PermanentFailureExceptionTest {

  @Test
  public void shouldConsiderSchUnknownHostAsPermanent() {
    assertThat(
            PermanentTransportException.wrapTransportException(
                new TransportException(
                    "SSH error", new JSchException("UnknownHostKey: some.place"))))
        .isInstanceOf(PermanentTransportException.class);
  }

  @Test
  public void shouldConsiderNotExistingRefsFromJGitAsPermanent() {
    assertThat(
            PermanentTransportException.wrapTransportException(
                new TransportException("Remote does not have refs/heads/foo available for fetch.")))
        .isInstanceOf(InexistentRefTransportException.class);
  }

  @Test
  public void shouldConsiderNotExistingRefsFromCGitAsPermanent() {
    assertThat(
            PermanentTransportException.wrapTransportException(
                new TransportException(
                    "Cannot fetch from repo, error message: fatal: couldn't find remote ref refs/heads/foobranch")))
        .isInstanceOf(InexistentRefTransportException.class);
  }
}
