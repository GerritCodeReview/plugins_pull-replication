package com.googlesource.gerrit.plugins.replication.pull.index;

import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.inject.Scopes;

import java.util.concurrent.ScheduledExecutorService;

public class IndexModule extends LifecycleModule {

    @Override
    protected void configure() {
        bind(ScheduledExecutorService.class)
                .annotatedWith(IndexExecutor.class)
                .toProvider(IndexExecutorProvider.class);

//        bind(IndexEventLocks.class).in(Scopes.SINGLETON);
        listener().to(IndexExecutorProvider.class);

    }
}
