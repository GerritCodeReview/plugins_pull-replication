package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.ExecutorProvider;

@Singleton
public
class EventExecutorProvider extends ExecutorProvider {

    @Inject
    EventExecutorProvider(WorkQueue workQueue) {
        super(workQueue, 1, "Index-RefReplicated-Event");
    }
}
