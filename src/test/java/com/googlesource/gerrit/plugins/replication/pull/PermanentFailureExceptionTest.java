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

import com.googlesource.gerrit.plugins.replication.pull.fetch.PermanentTransportException;
import org.apache.sshd.common.SshException;
import org.eclipse.jgit.errors.TransportException;
import org.junit.Test;

public class PermanentFailureExceptionTest {

  @Test
  public void shouldConsiderSchUnknownHostAsPermanent() {
    assertThat(
            PermanentTransportException.isPermanentFailure(
                new TransportException(
                    "SSH error", new SshException("UnknownHostKey: some.place"))))
        .isTrue();
  }

  @Test
  public void shouldConsiderNotExistingRefsAsPermanent() {
    assertThat(
            PermanentTransportException.isPermanentFailure(
                new TransportException("Remote does not have refs/heads/foo available for fetch.")))
        .isTrue();
  }
}
