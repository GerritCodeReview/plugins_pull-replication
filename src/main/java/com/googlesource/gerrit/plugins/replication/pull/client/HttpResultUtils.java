//
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

package com.googlesource.gerrit.plugins.replication.pull.client;

import com.google.gerrit.entities.Project;
import java.util.Optional;

public class HttpResultUtils {

  public static String status(Optional<HttpResult> maybeResult) {
    return maybeResult.map(HttpResult::toString).orElse("unknown");
  }

  public static boolean isSuccessful(Optional<HttpResult> maybeResult) {
    return maybeResult.map(HttpResult::isSuccessful).orElse(false);
  }

  public static boolean isProjectMissing(
      Optional<HttpResult> maybeResult, Project.NameKey project) {
    return maybeResult.map(r -> r.isProjectMissing(project)).orElse(false);
  }

  public static boolean isParentObjectMissing(Optional<HttpResult> maybeResult) {
    return maybeResult.map(HttpResult::isParentObjectMissing).orElse(false);
  }

  public static String errorMsg(Optional<HttpResult> maybeResult) {
    return maybeResult.flatMap(HttpResult::getMessage).orElse("unknown");
  }
}
