package com.googlesource.gerrit.plugins.replication.pull.index;

import com.google.common.flogger.FluentLogger;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class IndexingHandler<T> {
    protected static final FluentLogger log = FluentLogger.forEnclosingClass();
    private final Set <T> inFlightIndexing = Collections.newSetFromMap(new ConcurrentHashMap <>());

    public enum Operation {
        INDEX,
        DELETE;

        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    protected abstract void doIndex(T id, Optional <IndexEvent> indexEvent) throws IOException;

    protected abstract void doDelete(T id, Optional<IndexEvent> indexEvent) throws IOException;

    public void index(T id, Operation operation, Optional<IndexEvent> indexEvent) throws IOException {
        log.atFine().log("%s %s %s", operation, id, indexEvent);
        if (inFlightIndexing.add(id)) {
            try {
                switch (operation) {
                    case INDEX:
                        doIndex(id, indexEvent);
                        break;
                    case DELETE:
                        doDelete(id, indexEvent);
                        break;
                    default:
                        log.atSevere().log("unexpected operation: %s", operation);
                        break;
                }
            } finally {
                inFlightIndexing.remove(id);
            }
        } else {
            //XXX Throw some exception
        }
    }
}
