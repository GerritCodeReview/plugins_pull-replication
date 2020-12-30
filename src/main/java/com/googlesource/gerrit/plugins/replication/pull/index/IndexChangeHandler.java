package com.googlesource.gerrit.plugins.replication.pull.index;

import java.io.IOException;
import java.util.Optional;
import com.google.gerrit.server.index.change.ChangeIndexer;
import com.google.gerrit.server.notedb.ChangeNotes;

public class IndexChangeHandler extends IndexingHandler<String> {
    private final ChangeIndexer indexer;

    @Override
    protected void doIndex(String id, Optional <IndexEvent> indexEvent) throws IOException {
        doIndex(id, indexEvent, 0);
    }

    private void doIndex(String id, Optional<IndexEvent> indexEvent, int retryCount)
            throws IOException {
        try {
            //XXX Somehow get the ChangeNotes and reindex
            ChangeNotes notes = changeNotes.get();
            reindex(notes);
        } catch (Exception e) {
            throw e;
        }
    }

    private void reindex(ChangeNotes notes) {
        notes.reload();
        indexer.index(notes.getChange());
    }

    @Override
    protected void doDelete(String id, Optional <IndexEvent> indexEvent) throws IOException {

    }
}
