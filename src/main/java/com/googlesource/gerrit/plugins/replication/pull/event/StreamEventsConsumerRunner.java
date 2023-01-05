package com.googlesource.gerrit.plugins.replication.pull.event;

import com.gerritforge.gerrit.eventbroker.BrokerApi;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.util.OneOffRequestContext;
import javax.inject.Inject;

public class StreamEventsConsumerRunner  implements LifecycleListener {

  private DynamicItem<BrokerApi> brokerApi;
  private StreamEventListener eventListener;
  private DynamicItem<EventDispatcher> dispatcher;
  private OneOffRequestContext oneOffCtx;

  @Inject
  public StreamEventsConsumerRunner(DynamicItem<BrokerApi> brokerApi, StreamEventListener eventListener) {
    this.brokerApi = brokerApi;
    this.eventListener = eventListener;
  }

  @Override
  public void start() {
    brokerApi.get().receiveAsync("gerrit", (Event event) -> {
        eventListener.onEvent(event);
    });
  }

  @Override
  public void stop() {

  }
}
