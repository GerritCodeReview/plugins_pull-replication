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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.gerrit.common.UsedAt.Project.PLUGIN_MULTI_SITE;

import com.google.gerrit.common.UsedAt;

/**
 * Temporary solution for stable branches for allowing the multi-site plugin to understand his
 * caller identity.
 *
 * <p>TODO: To be removed from v3.9/master, where this problem does not exist anymore because of a
 * clearer definition of responsibilities between the multi-site and the pull-replication plugins.
 */
public interface PullReplicationEndpoints {

  @UsedAt(PLUGIN_MULTI_SITE)
  public static final String APPLY_OBJECT_API_ENDPOINT = "apply-object";

  @UsedAt(PLUGIN_MULTI_SITE)
  public static final String APPLY_OBJECTS_API_ENDPOINT = "apply-objects";

  public static final String FETCH_ENDPOINT = "fetch";
  public static final String BATCH_FETCH_ENDPOINT = "batch-fetch";
  public static final String INIT_PROJECT_ENDPOINT = "init-project";
  public static final String DELETE_PROJECT_ENDPOINT = "delete-project";
}
