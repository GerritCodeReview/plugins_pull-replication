// Copyright (C) 2016 The Android Open Source Project
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
import com.google.gerrit.server.events.RefEvent;

public class FetchReplicationScheduledEvent extends RefEvent {
  static final String TYPE = "fetch-ref-replication-scheduled";

  final String project;
  final String ref;
  final String sourceNode;

  public FetchReplicationScheduledEvent(String project, String ref, String sourceNode) {
    super(TYPE);
    this.project = project;
    this.ref = ref;
    this.sourceNode = sourceNode;
  }

  @Override
  public String getRefName() {
    return ref;
  }

  @Override
  public Project.NameKey getProjectNameKey() {
    return Project.nameKey(project);
  }
}
