package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.inject.BindingAnnotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@BindingAnnotation
@Retention(RetentionPolicy.RUNTIME)
public @interface FetchClientImplementation {}
