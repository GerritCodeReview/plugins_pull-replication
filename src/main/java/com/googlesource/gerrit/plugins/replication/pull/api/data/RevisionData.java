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

package com.googlesource.gerrit.plugins.replication.pull.api.data;

import com.google.common.base.MoreObjects;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;

public class RevisionData {
  private List<String> parentObjectIds;

  private RevisionObjectData commitObject;

  private RevisionObjectData treeObject;

  private List<RevisionObjectData> blobs;

  public RevisionData(
      List<String> parentObjectIds,
      RevisionObjectData commitObject,
      RevisionObjectData treeObject,
      List<RevisionObjectData> blobs) {
    this.parentObjectIds = parentObjectIds;
    this.commitObject = commitObject;
    this.treeObject = treeObject;
    this.blobs = blobs;
  }

  public List<ObjectId> getParentObjetIds() {
    List<String> parentSha1s = MoreObjects.firstNonNull(parentObjectIds, List.of());
    return parentSha1s.stream().map(ObjectId::fromString).toList();
  }

  public RevisionObjectData getCommitObject() {
    return commitObject;
  }

  public RevisionObjectData getTreeObject() {
    return treeObject;
  }

  public List<RevisionObjectData> getBlobs() {
    return blobs;
  }

  @Override
  public String toString() {
    return "{"
        + (commitObject != null ? "commitObject=" + commitObject : "")
        + " "
        + (treeObject != null ? "treeObject=" + treeObject : "")
        + " "
        + (blobs != null && !blobs.isEmpty() ? "blobs=" + blobs : "")
        + "}";
  }
}
