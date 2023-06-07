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

import com.google.gerrit.server.cache.CacheModule;
import java.time.Duration;

public class ApplyObjectCacheModule extends CacheModule {
  public static final String APPLY_OBJECTS_CACHE = "apply_objects";
  public static final Duration APPLY_OBJECTS_CACHE_MAX_AGE = Duration.ofMinutes(1);

  @Override
  protected void configure() {
    cache(APPLY_OBJECTS_CACHE, ApplyObjectsCacheKey.class, Long.class)
        .expireAfterWrite(APPLY_OBJECTS_CACHE_MAX_AGE);
  }
}
