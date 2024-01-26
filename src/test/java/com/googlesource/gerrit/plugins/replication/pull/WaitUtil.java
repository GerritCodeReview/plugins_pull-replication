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

package com.googlesource.gerrit.plugins.replication.pull;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Stopwatch;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class WaitUtil {
  public static void waitUntil(Supplier<Boolean> waitCondition, Duration timeout) throws Exception {
    Stopwatch stopwatch = Stopwatch.createStarted();
    while (!waitCondition.get()) {
      if (stopwatch.elapsed().compareTo(timeout) > 0) {
        throw new InterruptedException();
      }
      MILLISECONDS.sleep(50);
    }
  }

  public static void eventually(Duration timeout, Duration interval, Runnable assertion)
      throws InterruptedException {
    Instant start = Instant.now();
    Instant max = start.plus(timeout);

    boolean failed;
    do {
      try {
        assertion.run();
        failed = false;
      } catch (Throwable e) {
        failed = true;
        if (Instant.now().isAfter(max)) {
          throw e;
        } else {
          Thread.sleep(interval.toMillis());
        }
      }
    } while (failed);
  }
}
