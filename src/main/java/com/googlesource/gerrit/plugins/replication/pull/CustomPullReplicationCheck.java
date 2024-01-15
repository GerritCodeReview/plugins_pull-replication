package com.googlesource.gerrit.plugins.replication.pull;

import com.googlesource.gerrit.plugins.healthcheck.check.HealthCheck;

public class CustomPullReplicationCheck implements HealthCheck {

  @Override
  public StatusSummary run() {
    return StatusSummary.INITIAL_STATUS;
  }

  @Override
  public String name() {
    return "my-custom-pull-replication-check";
  }
}
