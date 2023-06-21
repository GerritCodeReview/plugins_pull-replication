// Copyright (C) 2023 The Android Open Source Project
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

import com.google.auto.value.AutoValue;
import java.util.Optional;

@AutoValue
public abstract class BatchApplyObjectData {

  public static BatchApplyObjectData create(
      String refName, Optional<RevisionData> revisionData, boolean isDelete)
      throws IllegalArgumentException {
    if (isDelete && revisionData.isPresent()) {
      throw new IllegalArgumentException(
          "DELETE ref-updates cannot be associated with a RevisionData");
    }
    return new AutoValue_BatchApplyObjectData(refName, revisionData, isDelete);
  }

  public abstract String refName();

  public abstract Optional<RevisionData> revisionData();

  public abstract boolean isDelete();

  @Override
  public String toString() {
    return String.format(
        "%s:%s isDelete=%s",
        refName(), revisionData().map(RevisionData::toString).orElse("ABSENT"), isDelete());
  }
}
