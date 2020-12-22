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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;

public class ApplyObject {

  private final GitRepositoryManager gitManager;

  @Inject
  public ApplyObject(LocalDiskRepositoryManager gitManager) {
    this.gitManager = gitManager;
  }

  public RefUpdateState apply(Project.NameKey name, RefSpec refSpec, RevisionData revisionData)
      throws IOException {
    try (Repository git = gitManager.openRepository(name)) {

      ObjectId newObjectID = null;
      try (ObjectInserter oi = git.newObjectInserter()) {
        RevisionObjectData commitObject = revisionData.getCommitObject();
        newObjectID = oi.insert(commitObject.getType(), commitObject.getContent());

        RevisionObjectData treeObject = revisionData.getTreeObject();
        ObjectId t = oi.insert(treeObject.getType(), treeObject.getContent());

        for (RevisionObjectData rev : revisionData.getBlobs()) {
          ObjectId d = oi.insert(rev.getType(), rev.getContent());
        }

        oi.flush();
      }
      RefUpdate ru = git.updateRef(refSpec.getSource());
      ru.setNewObjectId(newObjectID);
      RefUpdate.Result result = ru.update();

      return new RefUpdateState(refSpec.getSource(), result);
    }
  }
}
