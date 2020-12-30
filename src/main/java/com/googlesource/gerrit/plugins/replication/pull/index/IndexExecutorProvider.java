package com.googlesource.gerrit.plugins.replication.pull.index;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import java.util.concurrent.ScheduledExecutorService;

@Singleton
class IndexExecutorProvider implements Provider <ScheduledExecutorService>, LifecycleListener {
    private ScheduledExecutorService executor;

    protected IndexExecutorProvider(WorkQueue workQueue, int threadPoolSize, String threadNamePrefix) {
        executor = workQueue.createQueue(threadPoolSize, threadNamePrefix);
    }

    @Override
    public void start() {
        // do nothing
    }

    @Override
    public void stop() {
        executor.shutdown();
        executor = null;
    }

    @Override
    public ScheduledExecutorService get() {
        return executor;
    }
}

