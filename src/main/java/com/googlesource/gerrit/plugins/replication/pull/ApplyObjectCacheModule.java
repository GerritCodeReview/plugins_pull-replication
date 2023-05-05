package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.server.cache.CacheModule;

public class ApplyObjectCacheModule extends CacheModule {
  public static final String APPLY_OBJECTS_CACHE = "apply_objects";

  @Override
  protected void configure() {
    cache(APPLY_OBJECTS_CACHE, ApplyObjectsCacheKey.class, Boolean.class);
  }
}
