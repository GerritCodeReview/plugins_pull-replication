// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.filter;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.RefNames;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.api.ReplicationConfig;
import java.util.List;
import org.eclipse.jgit.lib.Config;

@Singleton
public class ExcludedRefsFilter extends RefsFilter {
  public static String[] DEFAULT_REPLICATION_EXCLUDE_REFS =
      new String[] {RefNames.REFS_CACHE_AUTOMERGE + "*", RefNames.REFS_STARRED_CHANGES + "*"};

  @Inject
  public ExcludedRefsFilter(ReplicationConfig replicationConfig) {
    super(replicationConfig);
  }

  @Override
  protected List<String> getRefNamePatterns(Config cfg) {
    String[] replicationExcludeRefs = cfg.getStringList("replication", null, "excludeRefs");
    if (replicationExcludeRefs.length == 0) {
      replicationExcludeRefs = DEFAULT_REPLICATION_EXCLUDE_REFS;
    }
    return ImmutableList.copyOf(replicationExcludeRefs);
  }
}
