package com.googlesource.gerrit.plugins.replication.pull.fetch;

import static org.eclipse.jgit.transport.ReceiveCommand.Type.DELETE;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.RefValidationHelper;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceiveCommand;

public class DeleteRef {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final GitReferenceUpdated referenceUpdated;
  private final Provider<IdentifiedUser> identifiedUser;
  private final RefValidationHelper refDeletionValidator;

  private final GitRepositoryManager repoManager;

  @Inject
  public DeleteRef(
      GitReferenceUpdated referenceUpdated,
      Provider<IdentifiedUser> identifiedUser,
      RefValidationHelper.Factory refDeletionValidatorFactory,
      GitRepositoryManager repoManager) {

    this.referenceUpdated = referenceUpdated;
    this.identifiedUser = identifiedUser;
    this.repoManager = repoManager;
    this.refDeletionValidator = refDeletionValidatorFactory.create(DELETE);
  }

  public void deleteSingleRef(ProjectState projectState, String ref)
      throws IOException, ResourceConflictException {
    try (Repository git = repoManager.openRepository(projectState.getNameKey())) {
      RefUpdate.Result result;
      RefUpdate u = git.updateRef(ref);
      u.setExpectedOldObjectId(git.exactRef(ref).getObjectId());
      u.setNewObjectId(ObjectId.zeroId());
      u.setForceUpdate(true);
      refDeletionValidator.validateRefOperation(projectState.getName(), identifiedUser.get(), u);
      result = u.delete();

      switch (result) {
        case NEW:
        case NO_CHANGE:
        case FAST_FORWARD:
        case FORCED:
          referenceUpdated.fire(
              projectState.getNameKey(),
              u,
              ReceiveCommand.Type.DELETE,
              identifiedUser.get().state());
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
