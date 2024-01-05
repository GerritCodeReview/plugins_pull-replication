package com.googlesource.gerrit.plugins.replication.pull;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.ApiModule;

public class TestPullReplicationModule extends AbstractModule {

  private final PullReplicationModule pullReplicationModule;

  @Inject
  TestPullReplicationModule(PullReplicationModule pullReplicationModule){
    this.pullReplicationModule = pullReplicationModule;
  }

  @Override
  protected void configure() {
    install(new ApiModule());
    install(pullReplicationModule);
  }
}
