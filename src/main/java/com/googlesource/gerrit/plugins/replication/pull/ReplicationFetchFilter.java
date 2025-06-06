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

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.extensions.annotations.ExtensionPoint;
import com.google.gerrit.git.LockFailureException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter that is invoked before a set of remote refs are fetched from a remote instance.
 *
 * <p>It can be used to filter out unwanted fetches.
 */
@ExtensionPoint
public interface ReplicationFetchFilter {

  Set<String> filter(String projectName, Set<String> fetchRefs);

  default Map<String, AutoCloseable> filterAndLock(String projectName, Set<String> fetchRefs)
      throws LockFailureException {
    return filter(projectName, fetchRefs).stream()
        .collect(Collectors.toMap(ref -> ref, ref -> () -> {}));
  }
}
