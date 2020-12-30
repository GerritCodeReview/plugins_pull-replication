package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Provider;

import java.util.concurrent.ScheduledExecutorService;

public abstract class ExecutorProvider
        implements Provider <ScheduledExecutorService>, LifecycleListener {
    private ScheduledExecutorService executor;

    protected ExecutorProvider(WorkQueue workQueue, int threadPoolSize, String threadNamePrefix) {
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