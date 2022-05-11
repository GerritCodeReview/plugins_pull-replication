// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import org.eclipse.jgit.lib.Config;

public class EventsBrokerConsumerModule extends LifecycleModule {
  public static final String STREAM_EVENTS_TOPIC_NAME = "stream_events_topic_name";
  private static final String STREAM_EVENTS_TOPIC_NAME_DEF = "gerrit";

  private final String topicName;

  public EventsBrokerConsumerModule(Config replicationConfig) {
    topicName =
        replicationConfig.getString(
            "replication", "streamEventsTopic", STREAM_EVENTS_TOPIC_NAME_DEF);
  }

  @Override
  protected void configure() {
    bind(EventsBrokerMessageConsumer.class).in(Scopes.SINGLETON);
    bind(String.class).annotatedWith(Names.named(STREAM_EVENTS_TOPIC_NAME)).toInstance(topicName);

    listener().to(EventsBrokerMessageConsumer.class);
  }
}
