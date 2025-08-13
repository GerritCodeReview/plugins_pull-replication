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

class ProjectsLock {
  private final Map<Project.NameKey, String> locks = new ConcurrentHashMap<>();

  LockToken tryLock(Project.NameKey project, String taskId) throws UnableToLockProjectException {
    String previousTaskId = locks.putIfAbsent(project, taskId);

    if (previousTaskId != null) {
      throw new UnableToLockProjectException(project, previousTaskId);
    }

    return new LockToken(project, this);
  }

  void unlock(Project.NameKey project) {
    locks.remove(project);
  }

  static final class LockToken implements AutoCloseable {
    private final Project.NameKey project;
    private final ProjectsLock projectsLock;
    private boolean closed;

    private LockToken(Project.NameKey project, ProjectsLock projectsLock) {
      this.project = project;
      this.projectsLock = projectsLock;
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        projectsLock.unlock(project);
      }
    }
  }

  static class UnableToLockProjectException extends Exception {
    private final String conflictingTaskId;

    public UnableToLockProjectException(Project.NameKey project, String conflictingTaskId) {
      super(
          "Unable to lock project "
              + project
              + " because it is already locked by task "
              + conflictingTaskId);

      this.conflictingTaskId = conflictingTaskId;
    }

    public String getConflictingTaskId() {
      return conflictingTaskId;
    }
  }
}
