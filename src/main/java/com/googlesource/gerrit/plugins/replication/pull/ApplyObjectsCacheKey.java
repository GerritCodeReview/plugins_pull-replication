package com.googlesource.gerrit.plugins.replication.pull;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class ApplyObjectsCacheKey {

  public static ApplyObjectsCacheKey create(String newRev, String refName, String project) {
    return new AutoValue_ApplyObjectsCacheKey(newRev, refName, project);
  }

  public abstract String newRev();

  public abstract String refName();

  public abstract String project();
}
