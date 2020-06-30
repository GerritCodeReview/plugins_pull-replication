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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

public class CGitFetch implements Fetch {

  private File localProjectDirectory;
  private URIish uri;
  private int timeout;

  @Inject
  public CGitFetch(RemoteConfig config, @Assisted URIish uri, @Assisted Repository git) {

    this.localProjectDirectory = git.getDirectory();
    this.uri = uri;
    this.timeout = config.getTimeout();
  }

  @Override
  public List<RefUpdateState> fetch(List<RefSpec> refsSpec) throws IOException {
    List<String> refs = refsSpec.stream().map(s -> s.toString()).collect(Collectors.toList());
    List<String> command = Lists.newArrayList("git", "fetch", uri.toASCIIString());
    command.addAll(refs);
    ProcessBuilder pb = new ProcessBuilder().command(command).directory(localProjectDirectory);
    repLog.info("Fetch references {} from {}", refs, uri);
    Process process = pb.start();

    try {
      boolean isFinished = waitForTaskToFinish(process);
      if (!isFinished) {
        throw new TransportException(
            String.format("Timeout exception during the fetch from: %s, refs: %s", uri, refs));
      }
      if (process.exitValue() != 0) {
        String errorMessage =
            new BufferedReader(new InputStreamReader(process.getErrorStream()))
                .lines()
                .collect(Collectors.joining("\n"));
        throw new TransportException(
            String.format("Cannot fetch from %s, error message: %s}", uri, errorMessage));
      }

      return refsSpec.stream()
          .map(
              value -> {
                return new RefUpdateState(value.getSource(), RefUpdate.Result.NEW);
              })
          .collect(Collectors.toList());
    } catch (InterruptedException e) {
      repLog.error("Thread interrupted during the fetch from: {}, refs: {}", uri, refs);
      throw new IllegalStateException(e);
    }
  }

  public boolean waitForTaskToFinish(Process process) throws InterruptedException {
    if (timeout == 0) {
      process.waitFor();
      return true;
    }
    return process.waitFor(timeout, TimeUnit.SECONDS);
  }
}
