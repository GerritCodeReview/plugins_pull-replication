// Copyright (C) 2021 The Android Open Source Project
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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import java.util.concurrent.ScheduledExecutorService;

public abstract class ExecutorProvider
    implements Provider<ScheduledExecutorService>, LifecycleListener {
  private ScheduledExecutorService executor;

  protected ExecutorProvider(WorkQueue workQueue, int threadPoolSize, String threadNamePrefix) {
    executor = workQueue.createQueue(threadPoolSize, threadNamePrefix);
  }

  @Override
  public void start() {
    // do nothing
  }

  @Override
  public void stop() {
    executor.shutdown();
    executor = null;
  }

  @Override
  public ScheduledExecutorService get() {
    return executor;
  }
}
