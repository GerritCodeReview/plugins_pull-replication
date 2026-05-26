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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.googlesource.gerrit.plugins.replication.pull.ReplicationTaskId.withTaskId;

import java.io.IOException;
import org.junit.Test;

public class ReplicationTaskIdTest {
  String OUTER_TASK_ID = "outer";
  String INNER_TASK_ID = "inner";

  @Test
  public void withTaskIdShouldPreserveIdUponNestedCalls() throws IOException {
    String unused =
        withTaskId(
            OUTER_TASK_ID,
            () -> {
              withTaskId(
                  INNER_TASK_ID,
                  () -> {
                    assertThat(ReplicationTaskId.get()).isEqualTo(INNER_TASK_ID);
                    return "unused";
                  });
              assertThat(ReplicationTaskId.get()).isEqualTo(OUTER_TASK_ID);
              return "unused";
            });
    assertThat(ReplicationTaskId.get()).isNull();
  }

  @Test
  public void withTaskIdShouldPreserveIdUponNestedCallsWithExceptions() throws IOException {
    assertThrows(
        IOException.class,
        () ->
            withTaskId(
                OUTER_TASK_ID,
                () -> {
                  try {
                    withTaskId(
                        INNER_TASK_ID,
                        () -> {
                          assertThat(ReplicationTaskId.get()).isEqualTo(INNER_TASK_ID);
                          throw new IOException("exception");
                        });
                  } catch (IOException e) {
                    assertThat(ReplicationTaskId.get()).isEqualTo(OUTER_TASK_ID);
                    throw e;
                  }
                  return "unused";
                }));
    assertThat(ReplicationTaskId.get()).isNull();
  }
}
