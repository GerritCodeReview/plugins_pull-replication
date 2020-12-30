package com.googlesource.gerrit.plugins.replication.pull;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;

public class Module extends AbstractModule {
    private final SourceConfiguration config;

    @Inject
    Module(SourceConfiguration config) {
        this.config = config;
    }

    @Override
    protected void configure() {
        install(new IndexModule());
    }
}