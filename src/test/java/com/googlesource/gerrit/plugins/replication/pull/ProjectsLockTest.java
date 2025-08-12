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

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.gerrit.entities.Project;
import org.junit.Test;

public class ProjectsLockTest {
  private final ProjectsLock projectsLock = new ProjectsLock();
  private final Project.NameKey project1 = Project.nameKey("project1");
  private final Project.NameKey project2 = Project.nameKey("project2");
  private final String task1 = "task1";
  private final String task2 = "task2";

  @Test
  public void shouldSuccessfullyLockAndUnlockProject() throws Exception {
    ProjectsLock.LockToken lockToken = projectsLock.tryLock(project1, task1);
    assertThatIsLocked(lockToken, project1);
    assertThat(projectsLock.unlock(project1, task1)).isTrue();

    try (ProjectsLock.LockToken newLockToken = projectsLock.tryLock(project1, task2)) {
      assertThatIsLocked(newLockToken, project1);
    }
  }

  @Test
  public void shouldFailToLockFromAnotherTaskOnLockedProject() throws Exception {
    try (ProjectsLock.LockToken unused = projectsLock.tryLock(project1, task1)) {
      ProjectsLock.UnableToLockProjectException e =
          assertThrows(
              ProjectsLock.UnableToLockProjectException.class,
              () -> projectsLock.tryLock(project1, task2).close());
      assertThat(e.getConflictingTaskId()).isEqualTo(task1);
    }
  }

  @Test
  public void shouldHandleMultipleProjectsIndependently() throws Exception {
    try (ProjectsLock.LockToken lockToken1 = projectsLock.tryLock(project1, task1);
        ProjectsLock.LockToken lockToken2 = projectsLock.tryLock(project2, task2)) {
      assertThatIsLocked(lockToken1, project1);
      assertThatIsLocked(lockToken2, project2);
    }
  }

  private void assertThatIsLocked(ProjectsLock.LockToken lockToken, Project.NameKey project1) {
    assertThat(lockToken).isNotNull();
    assertThat(lockToken.project).isEqualTo(project1);
  }
}
