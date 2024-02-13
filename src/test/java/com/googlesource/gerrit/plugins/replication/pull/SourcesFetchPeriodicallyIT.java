// Copyright (C) 2024 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.acceptance.config.GerritConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.TestPullReplicationModule",
    httpModule = "com.googlesource.gerrit.plugins.replication.pull.api.HttpModule")
public class SourcesFetchPeriodicallyIT extends PullReplicationSetupBase {
  private static final String TEST_FETCH_FREQUENCY = "1s";

  @Override
  protected boolean useBatchRefUpdateEvent() {
    return false;
  }

  @Override
  protected void setReplicationSource(
      String remoteName, List<String> replicaSuffixes, Optional<String> project)
      throws IOException {
    List<String> fetchUrls =
        buildReplicaURLs(replicaSuffixes, s -> gitPath.resolve("${name}" + s + ".git").toString());
    config.setStringList("remote", remoteName, "url", fetchUrls);
    project.ifPresent(prj -> config.setString("remote", remoteName, "projects", prj));
    config.setString("remote", remoteName, "fetchEvery", TEST_FETCH_FREQUENCY);
    config.save();
  }

  @Override
  public void setUpTestPlugin() throws Exception {
    setUpTestPlugin(false);
  }

  @Test
  @GerritConfig(name = "gerrit.instanceId", value = TEST_REPLICATION_REMOTE)
  public void shouldFetchChangesPeriodically() throws Exception {
    testRepo = cloneProject(createTestProject(project + TEST_REPLICATION_SUFFIX));

    Result pushChangeResult = createChange();
    RevCommit changeCommit = pushChangeResult.getCommit();
    String sourceChangeRef = pushChangeResult.getPatchSet().refName();

    try (Repository repo = repoManager.openRepository(project)) {
      waitUntil(() -> checkedGetRef(repo, sourceChangeRef) != null);

      Ref targetChangeRef = getRef(repo, sourceChangeRef);
      assertThat(targetChangeRef).isNotNull();
      assertThat(targetChangeRef.getObjectId()).isEqualTo(changeCommit.getId());

      // ensure that previous fetch was finished
      Thread.sleep(Duration.ofSeconds(TEST_REPLICATION_DELAY).toMillis());

      Ref sourceNewRef = createNewRef();

      waitUntil(() -> checkedGetRef(repo, sourceNewRef.getName()) != null);
      Ref targetNewRef = getRef(repo, sourceNewRef.getName());
      assertThat(targetNewRef).isNotNull();
      assertThat(targetNewRef.getObjectId()).isEqualTo(sourceNewRef.getObjectId());
    }
  }
}
