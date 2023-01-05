package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.gerrit.lifecycle.LifecycleModule;

public class StreamEventsConsumerModule extends LifecycleModule {

  @Override
  protected void configure() {
    listener().to(StreamEventsConsumerRunner.class);
  }
}
