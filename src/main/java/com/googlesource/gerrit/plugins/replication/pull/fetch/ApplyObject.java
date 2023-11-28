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
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.LocalGitRepositoryManagerProvider;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionObjectData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import java.io.IOException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;

public class ApplyObject {

  private final GitRepositoryManager gitManager;

  // NOTE: We do need specifically the local GitRepositoryManager to make sure
  // to be able to write onto the directly physical repository without any wrapper.
  // Using for instance the multi-site wrapper injected by Guice would result
  // in a split-brain because of the misalignment of local vs. global refs values.
  @Inject
  public ApplyObject(LocalGitRepositoryManagerProvider gitManagerProvider) {
    this.gitManager = gitManagerProvider.get();
  }

  public RefUpdateState apply(Project.NameKey name, RefSpec refSpec, RevisionData[] revisionsData)
      throws MissingParentObjectException, IOException, ResourceNotFoundException {
    try (Repository git = gitManager.openRepository(name)) {

      ObjectId refHead = null;
      RefUpdate ru = git.updateRef(refSpec.getSource());
      try (ObjectInserter oi = git.newObjectInserter()) {
        for (RevisionData revisionData : revisionsData) {

          ObjectId newObjectID = null;
          RevisionObjectData commitObject = revisionData.getCommitObject();

          if (commitObject != null) {
            RevCommit commit = RevCommit.parse(commitObject.getContent());
            for (RevCommit parent : commit.getParents()) {
              if (!git.getObjectDatabase().has(parent.getId())) {
                throw new MissingParentObjectException(name, refSpec.getSource(), parent.getId());
              }
            }
          }

          for (RevisionObjectData rev : revisionData.getBlobs()) {
            ObjectId blobObjectId = oi.insert(rev.getType(), rev.getContent());
            if (newObjectID == null) {
              newObjectID = blobObjectId;
            }
            refHead = newObjectID;
          }

          if (commitObject != null) {
            RevisionObjectData treeObject = revisionData.getTreeObject();
            oi.insert(treeObject.getType(), treeObject.getContent());

            refHead = oi.insert(commitObject.getType(), commitObject.getContent());
          }

          oi.flush();

          if (commitObject == null) {
            // Non-commits must be forced as they do not have a graph associated
            ru.setForceUpdate(true);
          }
        }

        ru.setNewObjectId(refHead);
        RefUpdate.Result result = ru.update();
        return new RefUpdateState(refSpec.getSource(), result);
      }
    } catch (RepositoryNotFoundException e) {
      throw new ResourceNotFoundException(IdString.fromDecoded(name.get()), e);
    }
  }
}
