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

import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.events.HeadUpdatedListener;

class FakeHeadUpdateEvent implements HeadUpdatedListener.Event {

  private final String oldName;
  private final String newName;
  private final String projectName;

  FakeHeadUpdateEvent(String oldName, String newName, String projectName) {

    this.oldName = oldName;
    this.newName = newName;
    this.projectName = projectName;
  }

  @Override
  public NotifyHandling getNotify() {
    return NotifyHandling.NONE;
  }

  @Override
  public String getOldHeadName() {
    return oldName;
  }

  @Override
  public String getNewHeadName() {
    return newName;
  }

  @Override
  public String getProjectName() {
    return projectName;
  }
}
