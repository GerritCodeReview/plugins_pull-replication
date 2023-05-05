package com.googlesource.gerrit.plugins.replication.pull;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class ApplyObjectsCacheKey {

  public static ApplyObjectsCacheKey create(
      String oldRev, String newRev, String refName, String project) {
    return new AutoValue_ApplyObjectsCacheKey(oldRev, newRev, refName, project);
  }

  public abstract String oldRev();

  public abstract String newRev();

  public abstract String refName();

  public abstract String project();
}
