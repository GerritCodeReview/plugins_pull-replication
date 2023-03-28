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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import org.apache.sshd.common.SshException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;

public class PermanentTransportException extends TransportException {
  private static final long serialVersionUID = 1L;

  public PermanentTransportException(String msg, Throwable cause) {
    super(msg, cause);
  }

  public static boolean isPermanentFailure(TransportException e) {
    Throwable cause = e.getCause();
    String message = e.getMessage();
    return (cause instanceof SshException && cause.getMessage().startsWith("Failed (UnsupportedCredentialItem) to execute:"))
        || message.matches(JGitText.get().remoteDoesNotHaveSpec.replaceAll("\\{0\\}", ".+"));
  }
}
