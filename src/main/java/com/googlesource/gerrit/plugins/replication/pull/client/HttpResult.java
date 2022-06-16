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

package com.googlesource.gerrit.plugins.replication.pull.client;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;

import com.google.gerrit.entities.Project;
import java.util.Optional;

public class HttpResult {
  private final Optional<String> message;
  private final int responseCode;

  HttpResult(int responseCode, Optional<String> message) {
    this.message = message;
    this.responseCode = responseCode;
  }

  public Optional<String> getMessage() {
    return message;
  }

  public boolean isSuccessful() {
    return responseCode / 100 == 2; // Any 2xx response code is a success
  }

  public boolean isProjectMissing(Project.NameKey projectName) {
    String projectMissingMessage = String.format("Not found: %s", projectName.get());
    return message.map(msg -> msg.contains(projectMissingMessage)).orElse(false);
  }

  public boolean isParentObjectMissing() {
    return responseCode == SC_CONFLICT;
  }

  @Override
  public String toString() {
    return isSuccessful()
        ? "OK"
        : "FAILED" + ", status=" + responseCode + message.map(s -> " '" + s + "'").orElse("");
  }
}
