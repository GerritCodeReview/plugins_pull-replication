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
import java.util.concurrent.atomic.AtomicReference;

class ProjectsLock {
  public static final String EMPTY_TASK_ID = "";
  private final Map<Project.NameKey, AtomicReference<String>> projectLocksHeldByTasks =
      new ConcurrentHashMap<>();

  LockToken tryLock(Project.NameKey project, String taskId) throws UnableToLockProjectException {
    if (!getProjectAtomicReference(project).compareAndSet(EMPTY_TASK_ID, taskId)) {
      throw new UnableToLockProjectException(project, projectLocksHeldByTasks.get(project).get());
    }
    return new LockToken(this, project, taskId);
  }

  boolean unlock(Project.NameKey project, String taskId) {
    return getProjectAtomicReference(project).compareAndSet(taskId, EMPTY_TASK_ID);
  }

  private AtomicReference<String> getProjectAtomicReference(Project.NameKey project) {
    return projectLocksHeldByTasks.computeIfAbsent(
        project, k -> new AtomicReference<>(EMPTY_TASK_ID));
  }

  static final class LockToken implements AutoCloseable {
    final Project.NameKey project;
    final ProjectsLock projectsLock;
    private final String taskId;
    boolean closed;

    private LockToken(ProjectsLock projectsLock, Project.NameKey project, String taskId) {
      this.project = project;
      this.projectsLock = projectsLock;
      this.taskId = taskId;
    }

    @Override
    public void close() {
      if (!closed && projectsLock.unlock(project, taskId)) {
        closed = true;
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
