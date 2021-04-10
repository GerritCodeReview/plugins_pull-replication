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

import static com.googlesource.gerrit.plugins.replication.pull.PullReplicationLogger.repLog;

import com.google.common.collect.Lists;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.RefUpdateException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

public class RevisionReader {
  private static final String CONFIG_MAX_API_PAYLOAD_SIZE = "maxApiPayloadSize";
  private static final Long DEFAULT_MAX_PAYLOAD_SIZE_IN_BYTES = 10000L;
  private GitRepositoryManager gitRepositoryManager;
  private Long maxRefSize;

  @Inject
  public RevisionReader(GitRepositoryManager gitRepositoryManager, ReplicationConfig cfg) {
    this.gitRepositoryManager = gitRepositoryManager;
    this.maxRefSize =
        cfg.getConfig()
            .getLong("replication", CONFIG_MAX_API_PAYLOAD_SIZE, DEFAULT_MAX_PAYLOAD_SIZE_IN_BYTES);
  }

  public Optional<RevisionData> read(Project.NameKey project, String refName)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          RepositoryNotFoundException, RefUpdateException, IOException {
    try (Repository git = gitRepositoryManager.openRepository(project)) {
      Ref head = git.exactRef(refName);
      if (head == null) {
        throw new RefUpdateException(
            String.format("Cannot find ref %s in project %s", refName, project.get()));
      }

      Long totalRefSize = 0l;

      ObjectLoader commitLoader = git.open(head.getObjectId());
      totalRefSize += commitLoader.getSize();
      verifySize(totalRefSize, commitLoader);

      RevCommit commit = RevCommit.parse(commitLoader.getCachedBytes());
      RevisionObjectData commitRev =
          new RevisionObjectData(commit.getType(), commitLoader.getCachedBytes());

      RevTree tree = commit.getTree();
      ObjectLoader treeLoader = git.open(commit.getTree().toObjectId());
      totalRefSize += treeLoader.getSize();
      verifySize(totalRefSize, treeLoader);

      RevisionObjectData treeRev =
          new RevisionObjectData(tree.getType(), treeLoader.getCachedBytes());

      List<RevisionObjectData> blobs = Lists.newLinkedList();
      try (TreeWalk walk = new TreeWalk(git)) {
        if (commit.getParentCount() > 0) {
          List<DiffEntry> diffEntries = readDiffs(git, commit, tree, walk);
          blobs = readBlobs(git, totalRefSize, diffEntries);
        } else {
          walk.setRecursive(true);
          walk.addTree(tree);
          blobs = readBlobs(git, totalRefSize, walk);
        }
      }
      return Optional.of(new RevisionData(commitRev, treeRev, blobs));
    } catch (LargeObjectException e) {
      repLog.trace(
          "Ref %s size for project %s is greater than configured '%s'",
          refName, project, CONFIG_MAX_API_PAYLOAD_SIZE);
      return Optional.empty();
    }
  }

  private List<DiffEntry> readDiffs(Repository git, RevCommit commit, RevTree tree, TreeWalk walk)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException {
    walk.setFilter(TreeFilter.ANY_DIFF);
    walk.reset(getParentTree(git, commit), tree);
    return DiffEntry.scan(walk, true);
  }

  private List<RevisionObjectData> readBlobs(Repository git, Long totalRefSize, TreeWalk walk)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException {
    List<RevisionObjectData> blobs = Lists.newLinkedList();
    while (walk.next()) {
      ObjectId objectId = walk.getObjectId(0);
      ObjectLoader objectLoader = git.open(objectId);
      totalRefSize += objectLoader.getSize();
      verifySize(totalRefSize, objectLoader);

      RevisionObjectData rev =
          new RevisionObjectData(objectLoader.getType(), objectLoader.getCachedBytes());
      blobs.add(rev);
    }
    return blobs;
  }

  private List<RevisionObjectData> readBlobs(
      Repository git, Long totalRefSize, List<DiffEntry> diffEntries)
      throws MissingObjectException, IOException {
    List<RevisionObjectData> blobs = Lists.newLinkedList();
    for (DiffEntry diffEntry : diffEntries) {
      if (!ChangeType.DELETE.equals(diffEntry.getChangeType())) {
        ObjectLoader objectLoader = git.open(diffEntry.getNewId().toObjectId());
        totalRefSize += objectLoader.getSize();
        verifySize(totalRefSize, objectLoader);
        RevisionObjectData rev =
            new RevisionObjectData(objectLoader.getType(), objectLoader.getCachedBytes());
        blobs.add(rev);
      }
    }
    return blobs;
  }

  private RevTree getParentTree(Repository git, RevCommit commit)
      throws MissingObjectException, IOException {
    RevCommit parent = commit.getParent(0);
    ObjectLoader parentLoader = git.open(parent.getId());
    RevCommit parentCommit = RevCommit.parse(parentLoader.getCachedBytes());
    return parentCommit.getTree();
  }

  private void verifySize(Long totalRefSize, ObjectLoader loader) throws LargeObjectException {
    if (loader.isLarge() || totalRefSize > maxRefSize) {
      throw new LargeObjectException();
    }
  }
}
