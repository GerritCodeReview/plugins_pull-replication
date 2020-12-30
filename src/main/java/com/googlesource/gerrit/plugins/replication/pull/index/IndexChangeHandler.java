package com.googlesource.gerrit.plugins.replication.pull.index;

import java.io.IOException;
import java.util.Optional;

public class IndexChangeHandler extends IndexingHandler<String> {

    @Override
    protected void doIndex(String id, Optional <IndexEvent> indexEvent) throws IOException {

    }

    @Override
    protected void doDelete(String id, Optional <IndexEvent> indexEvent) throws IOException {

    }
}
