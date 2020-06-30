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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.common.flogger.FluentLogger;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.AssistedInjectBinding;
import com.google.inject.assistedinject.AssistedInjectTargetVisitor;
import com.google.inject.assistedinject.AssistedMethod;
import com.google.inject.spi.DefaultBindingTargetVisitor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class NativeGitFetchValidator extends DefaultBindingTargetVisitor<FetchFactory, Void>
    implements AssistedInjectTargetVisitor<FetchFactory, Void> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private int DEFAULT_TIMEOUT_IN_SECONDS = 10;

  @Override
  public Void visit(AssistedInjectBinding<? extends FetchFactory> binding) {
    TypeLiteral<NativeGitFetch> nativeGitFetchType = new TypeLiteral<NativeGitFetch>() {};
    for (AssistedMethod method : binding.getAssistedMethods()) {
      if (method.getImplementationType().equals(nativeGitFetchType)) {
        String[] command = new String[] {"git", "-c", "protocol.version=2", "--version"};

        ProcessBuilder pb = new ProcessBuilder().command(command);
        try {
          Process process = pb.start();

          boolean isFinished = process.waitFor(DEFAULT_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
          if (!isFinished) {
            throw new IllegalStateException(
                "Timeout while checking if native git client is available");
          }
          if (process.exitValue() != 0) {
            String errorMessage = readMessage(process.getErrorStream());
            throw new IllegalStateException(
                String.format(
                    "Cannot check if native git client is available, error message: %s}",
                    errorMessage));
          }

          String commandOutputMessage = readMessage(process.getInputStream());
          logger.atInfo().log("Native git client version: %s", commandOutputMessage);
        } catch (IOException e) {
          throw new IllegalStateException(
              "Cannot start process to check if native git client is available", e);
        } catch (InterruptedException e) {
          throw new IllegalStateException(
              "Timeout while checking if native git client is available");
        }
      }
    }
    return null;
  }

  private String readMessage(InputStream stream) {
    return new BufferedReader(new InputStreamReader(stream))
        .lines()
        .collect(Collectors.joining("\n"));
  }
}
