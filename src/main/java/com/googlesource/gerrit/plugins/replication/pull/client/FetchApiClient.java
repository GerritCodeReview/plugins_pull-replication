// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.client;

import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.pull.SourcesCollection;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;

public interface FetchApiClient {

  public interface Factory {
    FetchApiClient create(SourcesCollection sources);
  }

  void callFetch(Project.NameKey project, String refName);

  void deleteProject(Project.NameKey project);

  void updateHead(Project.NameKey project, String newHead);

  void callSendObject(
      Project.NameKey project, String refName, boolean isDelete, RevisionData revisionData);
}
