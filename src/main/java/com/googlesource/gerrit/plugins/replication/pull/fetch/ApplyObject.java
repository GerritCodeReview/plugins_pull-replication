package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import java.io.IOException;
import org.eclipse.jgit.transport.RefSpec;

public interface ApplyObject {

  RefUpdateState apply(Project.NameKey name, RefSpec refSpec, RevisionData[] revisionsData)
      throws MissingParentObjectException, IOException;
}
