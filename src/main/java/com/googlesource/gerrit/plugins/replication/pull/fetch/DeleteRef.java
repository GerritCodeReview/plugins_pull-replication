package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.LocalDiskRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;

public class DeleteRef {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final GitRepositoryManager repoManager;

  @Inject
  public DeleteRef(LocalDiskRepositoryManager repoManager) {
    this.repoManager = repoManager;
  }

  public void deleteSingleRef(ProjectState projectState, String ref)
      throws IOException, ResourceConflictException {
    try (Repository git = repoManager.openRepository(projectState.getNameKey())) {
      RefUpdate.Result result;
      RefUpdate u = git.updateRef(ref);
      u.setExpectedOldObjectId(git.exactRef(ref).getObjectId());
      u.setNewObjectId(ObjectId.zeroId());
      u.setForceUpdate(true);
      result = u.delete();

      switch (result) {
        case NEW:
        case NO_CHANGE:
        case FAST_FORWARD:
        case FORCED:
          break;

        case REJECTED_CURRENT_BRANCH:
          logger.atFine().log("Cannot delete current branch %s: %s", ref, result.name());
          throw new ResourceConflictException("cannot delete current branch");

        case LOCK_FAILURE:
          throw new LockFailureException(String.format("Cannot delete %s", ref), u);
        case IO_FAILURE:
        case NOT_ATTEMPTED:
        case REJECTED:
        case RENAMED:
        case REJECTED_MISSING_OBJECT:
        case REJECTED_OTHER_REASON:
        default:
          throw new StorageException(String.format("Cannot delete %s: %s", ref, result.name()));
      }
    }
  }
}
