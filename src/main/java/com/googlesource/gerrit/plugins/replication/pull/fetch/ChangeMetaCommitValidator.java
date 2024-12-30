// Copyright (C) 2023 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.RefNames;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;

class ChangeMetaCommitValidator {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final FooterKey FOOTER_CHANGE_META_PATCH_SET = new FooterKey("Patch-set");

  public static boolean isValid(
      Repository repo, String refName, RevCommit commit, Consumer<String> errorCallback)
      throws IOException {
    if (!refName.startsWith(RefNames.REFS_CHANGES) || !refName.endsWith(RefNames.META_SUFFIX)) {
      return true;
    }

    List<String> patchSetFooter = commit.getFooterLines(FOOTER_CHANGE_META_PATCH_SET);
    OptionalInt latestPatchSet = patchSetFooter.stream().mapToInt(Integer::parseInt).max();

    if (latestPatchSet.isEmpty()) {
      return true;
    }

    String patchSetRef = refName.replace(RefNames.META_SUFFIX, "/" + latestPatchSet.getAsInt());
    Optional<ObjectId> patchSetObjectId =
        Optional.ofNullable(repo.exactRef(patchSetRef)).map(Ref::getObjectId);

    if (patchSetObjectId.isEmpty()) {
      errorCallback.accept("Unable to find latest patch-set ref " + patchSetRef);
      return false;
    }

    RevCommit patchSetCommit = repo.parseCommit(patchSetObjectId.get());
    logger.atFine().log(
        "Change on repository %s ref %s has latest patch-set %d and is successfully resolved to %s"
            + " with commit %s",
        repo, refName, latestPatchSet.getAsInt(), patchSetObjectId.get().getName(), patchSetCommit);

    return true;
  }
}
