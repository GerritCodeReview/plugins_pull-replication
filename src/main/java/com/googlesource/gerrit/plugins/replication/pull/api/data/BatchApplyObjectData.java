/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.replication.pull.api.data;

import java.util.Optional;

public class BatchApplyObjectData {
  private final String refName;
  private final Optional<RevisionData> revisionData;
  private final boolean isDelete;

  public BatchApplyObjectData(
      String refName, Optional<RevisionData> revisionData, boolean isDelete) {
    this.refName = refName;
    this.revisionData = revisionData;
    this.isDelete = isDelete;
  }

  public String getRefName() {
    return refName;
  }

  public Optional<RevisionData> getRevisionData() {
    return revisionData;
  }

  public boolean isDelete() {
    return isDelete;
  }

  @Override
  public String toString() {
    return String.format(
        "%s:%s isDelete=%s",
        refName, revisionData.map(RevisionData::toString).orElse("ABSENT"), isDelete);
  }
}
