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
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.api.ApplyObjectAction.Rev;
import com.googlesource.gerrit.plugins.replication.pull.api.ApplyObjectAction.Revs;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

@Singleton
public class RevReader {
  private GitRepositoryManager gitRepositoryManager;

  @Inject
  public RevReader(GitRepositoryManager gitRepositoryManager) {
    this.gitRepositoryManager = gitRepositoryManager;
  }

  public Revs read(Project.NameKey project, String refName)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException {
    try (Repository git = gitRepositoryManager.openRepository(project)) {
      Ref head = git.exactRef(refName);

      ObjectLoader commitLoader = git.open(head.getObjectId());
      RevCommit commit = RevCommit.parse(commitLoader.getCachedBytes());
      Rev commitRev = new Rev(commit.getType(), commitLoader.getCachedBytes());

      RevTree tree = commit.getTree();
      ObjectLoader treeLoader = git.open(commit.getTree().toObjectId());
      Rev treeRev = new Rev(tree.getType(), treeLoader.getCachedBytes());

      List<Rev> blobs = Lists.newLinkedList();
      try (TreeWalk walk = new TreeWalk(git)) {
        walk.addTree(tree);
        while (walk.next()) {
          ObjectId blobObjectId = walk.getObjectId(0);
          if (!blobObjectId.equals(commit.getId())) {
            ObjectLoader blobLoader = git.open(blobObjectId);
            if (blobLoader.getType() == Constants.OBJ_BLOB) {
              Rev rev = new Rev(blobLoader.getType(), blobLoader.getCachedBytes());
              blobs.add(rev);
            }
          }
        }
      }
      Revs revs = new Revs();
      revs.blobs = blobs;
      revs.objectBlob = commitRev;
      revs.treeObject = treeRev;
      return revs;
    }
  }
}
