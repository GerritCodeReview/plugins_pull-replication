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

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.RefNames;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import java.util.List;
import org.eclipse.jgit.lib.Config;

@Singleton
public class RefsFilter {
  public enum PatternType {
    REGEX,
    WILDCARD,
    EXACT_MATCH;

    public static PatternType getPatternType(String pattern) {
      if (pattern.startsWith(AccessSection.REGEX_PREFIX)) {
        return REGEX;
      } else if (pattern.endsWith("*")) {
        return WILDCARD;
      } else {
        return EXACT_MATCH;
      }
    }
  }

  private final List<String> refsPatterns;

  @Inject
  public RefsFilter(ReplicationConfig replicationConfig) {
    refsPatterns = getRefNamePatterns(replicationConfig.getConfig());
  }

  public boolean match(String refName) {
    if (refName == null || Strings.isNullOrEmpty(refName)) {
      throw new IllegalArgumentException(
          String.format("Ref name cannot be null or empty, but was %s", refName));
    }
    if (refsPatterns.isEmpty()) {
      return true;
    }

    for (String pattern : refsPatterns) {
      if (matchesPattern(refName, pattern)) {
        return true;
      }
    }
    return false;
  }

  private List<String> getRefNamePatterns(Config cfg) {
    return ImmutableList.<String>builder()
        .addAll(getDefaultExcludeRefPatterns())
        .addAll(ImmutableList.copyOf(cfg.getStringList("replication", null, "excludeRefs")))
        .build();
  }

  private boolean matchesPattern(String refName, String pattern) {
    boolean match = false;
    switch (PatternType.getPatternType(pattern)) {
      case REGEX:
        match = refName.matches(pattern);
        break;
      case WILDCARD:
        match = refName.startsWith(pattern.substring(0, pattern.length() - 1));
        break;
      case EXACT_MATCH:
        match = refName.equals(pattern);
    }
    return match;
  }

  private List<String> getDefaultExcludeRefPatterns() {
    return ImmutableList.of(
        RefNames.REFS_USERS + "*",
        RefNames.REFS_CONFIG,
        RefNames.REFS_SEQUENCES + "*",
        RefNames.REFS_EXTERNAL_IDS,
        RefNames.REFS_GROUPS + "*",
        RefNames.REFS_GROUPNAMES,
        RefNames.REFS_CACHE_AUTOMERGE + "*",
        RefNames.REFS_STARRED_CHANGES + "*");
  }
}
