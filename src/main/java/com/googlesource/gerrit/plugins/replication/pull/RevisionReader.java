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

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

public class RevisionReader {
  private GitRepositoryManager gitRepositoryManager;

  @Inject
  public RevisionReader(GitRepositoryManager gitRepositoryManager) {
    this.gitRepositoryManager = gitRepositoryManager;
  }

  public RevisionData read(Project.NameKey project, String refName)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          RepositoryNotFoundException, RefUpdateException, IOException {
    try (Repository git = gitRepositoryManager.openRepository(project)) {
      Ref head = git.exactRef(refName);
      if (head == null) {
        throw new RefUpdateException(
            String.format("Cannot find ref %s in project %s", refName, project.get()));
      }

      ObjectLoader commitLoader = git.open(head.getObjectId());
      RevCommit commit = RevCommit.parse(commitLoader.getCachedBytes());
      RevisionObjectData commitRev =
          new RevisionObjectData(commit.getType(), commitLoader.getCachedBytes());

      RevTree tree = commit.getTree();
      ObjectLoader treeLoader = git.open(commit.getTree().toObjectId());
      RevisionObjectData treeRev =
          new RevisionObjectData(tree.getType(), treeLoader.getCachedBytes());

      List<RevisionObjectData> blobs = Lists.newLinkedList();
      try (TreeWalk walk = new TreeWalk(git)) {
        walk.addTree(tree);
        while (walk.next()) {
          ObjectId blobObjectId = walk.getObjectId(0);
          ObjectLoader blobLoader = git.open(blobObjectId);
          if (blobLoader.getType() == Constants.OBJ_BLOB) {
            RevisionObjectData rev =
                new RevisionObjectData(blobLoader.getType(), blobLoader.getCachedBytes());
            blobs.add(rev);
          }
        }
      }
      return new RevisionData(commitRev, treeRev, blobs);
    }
  }
}
