// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.client;

import com.google.auto.value.AutoValue;
import com.google.gerrit.entities.Project;
import java.util.Optional;

@AutoValue
public abstract class Result {
  public abstract Optional<String> message();

  public abstract boolean isParentObjectMissing();

  public abstract boolean isSuccessful();

  public boolean isProjectMissing(Project.NameKey projectName) {
    String projectMissingMessage = String.format("Not found: %s", projectName.get());
    return message().map(msg -> msg.contains(projectMissingMessage)).orElse(false);
  }

  static Builder builder() {
    return new AutoValue_Result.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setMessage(Optional<String> message);

    abstract Builder setIsParentObjectMissing(Boolean isParentObjectMissing);

    abstract Builder setIsSuccessful(Boolean isSuccessful);

    abstract Result build();
  }
}
