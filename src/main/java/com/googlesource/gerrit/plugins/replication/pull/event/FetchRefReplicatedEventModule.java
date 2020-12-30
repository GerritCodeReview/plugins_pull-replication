package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventListener;
import java.util.concurrent.Executor;

public class FetchRefReplicatedEventModule extends LifecycleModule {

    @Override
    protected void configure() {
        bind(Executor.class).annotatedWith(EventExecutor.class).toProvider(EventExecutorProvider.class);
        listener().to(EventExecutorProvider.class);
        DynamicSet.bind(binder(), EventListener.class).to(FetchRefReplicatedEventHandler.class);
    }
}
