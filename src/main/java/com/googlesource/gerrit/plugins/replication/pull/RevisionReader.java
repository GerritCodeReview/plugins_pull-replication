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
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

@Singleton
public class RevisionReader {
  private static final Long DEFAULT_MAX_REF_SIZE = 500000L;
  private GitRepositoryManager gitRepositoryManager;
  private Long maxRefSize;

  @Inject
  public RevisionReader(GitRepositoryManager gitRepositoryManager, Config cfg) {
    this.gitRepositoryManager = gitRepositoryManager;
    this.maxRefSize = cfg.getLong("replication", "payloadMaxRefSize", DEFAULT_MAX_REF_SIZE);
  }

  public RevisionData read(Project.NameKey project, String refName)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException, LargeObjectException, RefUpdateException {
    try (Repository git = gitRepositoryManager.openRepository(project)) {
      Ref head = git.exactRef(refName);
      if (head == null) {
        throw new RefUpdateException(
            String.format("Cannot find ref %s in project %s", refName, project.get()));
      }
      Long totalRefSize = 0l;

      ObjectLoader commitLoader = git.open(head.getObjectId());

      verifySize(totalRefSize, commitLoader, head.getObjectId());
      totalRefSize += commitLoader.getSize();

      RevCommit commit = RevCommit.parse(commitLoader.getCachedBytes());

      RevisionObjectData commitRev =
          new RevisionObjectData(commit.getType(), commitLoader.getCachedBytes());

      RevTree tree = commit.getTree();
      ObjectLoader treeLoader = git.open(commit.getTree().toObjectId());
      verifySize(totalRefSize, treeLoader, head.getObjectId());
      totalRefSize += treeLoader.getSize();

      RevisionObjectData treeRev =
          new RevisionObjectData(tree.getType(), treeLoader.getCachedBytes());

      List<RevisionObjectData> blobs = Lists.newLinkedList();
      try (TreeWalk walk = new TreeWalk(git)) {
        walk.addTree(tree);
        walk.setRecursive(true);
        while (walk.next()) {
          ObjectId objectId = walk.getObjectId(0);
          if (!objectId.equals(commit.getId())) {
            ObjectLoader objectLoader = git.open(objectId);
            verifySize(totalRefSize, objectLoader, head.getObjectId());
            totalRefSize += objectLoader.getSize();

            RevisionObjectData rev =
                new RevisionObjectData(objectLoader.getType(), objectLoader.getCachedBytes());
            blobs.add(rev);
          }
        }
      }
      return new RevisionData(commitRev, treeRev, blobs);
    }
  }

  private void verifySize(Long totalRefSize, ObjectLoader loader, ObjectId objectId) {
    if (loader.isLarge() || (totalRefSize + loader.getSize()) > maxRefSize) {
      throw new LargeObjectException(objectId);
    }
  }
}
