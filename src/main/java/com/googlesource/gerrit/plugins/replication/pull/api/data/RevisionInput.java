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

import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.lib.Constants;

public class RevisionInput {
  private String label;

  private String refName;

  private long eventCreatedOn;
  private RevisionData revisionData;

  public RevisionInput(
      String label, String refName, long eventCreatedOn, RevisionData revisionData) {
    this.label = label;
    this.refName = refName;
    this.eventCreatedOn = eventCreatedOn;
    this.revisionData = revisionData;
  }

  public String getLabel() {
    return label;
  }

  public String getRefName() {
    return refName;
  }

  public RevisionData getRevisionData() {
    return revisionData;
  }

  public long getEventCreatedOn() {
    return eventCreatedOn;
  }

  public void validate() {
    validate(refName, revisionData);
  }

  static void validate(String refName, RevisionData revisionData) {
    // Non-heads refs can point to non-commit objects
    if (!refName.startsWith(Constants.R_HEADS)
        && Objects.isNull(revisionData.getCommitObject())
        && Objects.isNull(revisionData.getTreeObject())) {

      List<RevisionObjectData> blobs = revisionData.getBlobs();

      if (Objects.isNull(blobs) || blobs.isEmpty()) {
        throw new IllegalArgumentException(
            "Ref " + refName + " cannot have a null or empty list of BLOBs associated");
      }

      if (blobs.size() > 1) {
        throw new IllegalArgumentException("Ref " + refName + " has more than one BLOB associated");
      }

      return;
    }

    if (Objects.isNull(revisionData.getCommitObject())
        || Objects.isNull(revisionData.getCommitObject().getContent())
        || revisionData.getCommitObject().getContent().length == 0
        || Objects.isNull(revisionData.getCommitObject().getType())) {
      throw new IllegalArgumentException(
          "Commit object for ref " + refName + " cannot be null or empty");
    }

    if (Objects.isNull(revisionData.getTreeObject())
        || Objects.isNull(revisionData.getTreeObject().getContent())
        || Objects.isNull(revisionData.getTreeObject().getType())) {
      throw new IllegalArgumentException(
          "Ref-update tree object for ref " + refName + " cannot be null");
    }
  }
}
