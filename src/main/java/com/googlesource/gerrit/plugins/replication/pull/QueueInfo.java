// Copyright (C) 2025 The Android Open Source Project
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

import com.google.gerrit.entities.Project;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the state of the replication queues.
 *
 * <p>This class tracks:
 *
 * <ul>
 *   <li>{@link #pending} - operations that are scheduled but not yet started
 *   <li>{@link #inFlight} - operations that are currently running
 * </ul>
 *
 * <p>Both maps are keyed by {@link Project.NameKey} and store a {@code FetchOne} task representing
 * the work to be performed for that project.
 *
 * <p>The class is a singleton so that it can be injected and shared across multiple {@link
 * Source}s.
 */
public class QueueInfo {
  private final Map<Project.NameKey, FetchOne> pending;
  private final Map<Project.NameKey, FetchOne> inFlight;

  public QueueInfo() {
    this.pending = new ConcurrentHashMap<>();
    this.inFlight = new ConcurrentHashMap<>();
  }

  /**
   * Returns the map of operations that are scheduled but not yet started.
   *
   * @return a thread-safe map of pending operations, keyed by project.
   */
  public Map<Project.NameKey, FetchOne> pending() {
    return pending;
  }

  /**
   * Returns the map of operations that are currently running.
   *
   * @return a thread-safe map of in-flight operations, keyed by project.
   */
  public Map<Project.NameKey, FetchOne> inFlight() {
    return inFlight;
  }
}
