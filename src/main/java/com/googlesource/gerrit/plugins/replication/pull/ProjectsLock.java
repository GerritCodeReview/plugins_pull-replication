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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProjectsLock {
  private final Map<Project.NameKey, AtomicBoolean> locks = new ConcurrentHashMap<>();

  public Optional<LockToken> tryLock(Project.NameKey project) {
    AtomicBoolean flag = locks.computeIfAbsent(project, k -> new AtomicBoolean(false));

    if (flag.compareAndSet(false, true)) {
      return Optional.of(new LockToken(project, this));
    }
    return Optional.empty();
  }

  void unlock(Project.NameKey project) {
    AtomicBoolean flag = locks.get(project);
    if (flag != null) {
      flag.set(false);
    }
  }

  public static final class LockToken implements AutoCloseable {
    private final Project.NameKey project;
    private final ProjectsLock projectsLock;

    private LockToken(Project.NameKey project, ProjectsLock projectsLock) {
      this.project = project;
      this.projectsLock = projectsLock;
    }

    @Override
    public void close() {
      projectsLock.unlock(project);
    }
  }
}
