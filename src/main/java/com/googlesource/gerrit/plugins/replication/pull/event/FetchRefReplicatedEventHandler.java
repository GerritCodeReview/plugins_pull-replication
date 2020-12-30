package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;

public class FetchRefReplicatedEventHandler implements EventListener {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final String pluginName;

    @Inject
    FetchRefReplicatedEventHandler(
            @PluginName String pluginName) {
        this.pluginName = pluginName;
    }

    @Override
    public void onEvent(Event event) {

        if (event instanceof FetchRefReplicatedEvent) {
            FetchRefReplicatedEvent fetchRefReplicatedEvent = (FetchRefReplicatedEvent) event;
            logger.atSevere().log("Caught event for project %s, ref %s", fetchRefReplicatedEvent.getProjectNameKey().get(), fetchRefReplicatedEvent.getRefName());
        }
        logger.atSevere().log("===>> Event type %s", event.type);
    }
}
