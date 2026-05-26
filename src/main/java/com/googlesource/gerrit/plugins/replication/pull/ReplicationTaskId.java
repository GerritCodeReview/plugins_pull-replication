// Copyright (C) 2026 The Android Open Source Project
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

import com.google.gerrit.common.Nullable;
import java.io.IOException;

public class ReplicationTaskId {
  private static final ThreadLocal<String> currentTaskId = new ThreadLocal<>();

  @FunctionalInterface
  public interface ReplicationBody<T> {
    T apply() throws IOException;
  }

  static <T> T withTaskId(String taskId, ReplicationBody<T> body) throws IOException {
    String oldTaskId = currentTaskId.get();
    currentTaskId.set(taskId);
    try {
      return body.apply();
    } finally {
      if (oldTaskId == null) {
        currentTaskId.remove();
      } else {
        currentTaskId.set(oldTaskId);
      }
    }
  }

  @Nullable
  public static String get() {
    return currentTaskId.get();
  }
}
