package com.googlesource.gerrit.plugins.replication.pull.auth;

import static com.google.common.truth.Truth.assertThat;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.acceptance.UseLocalDisk;
import com.google.gerrit.entities.GroupDescription;
import org.junit.Test;

@SkipProjectClone
@UseLocalDisk
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupModule")
public class PullReplicationGroupBackendIT extends LightweightPluginDaemonTest {

  @Test
  public void shouldResolvePullReplicationInternalUser() {
    GroupDescription.Basic group =
        groupBackend.get(PullReplicationGroupBackend.INTERNAL_GROUP_UUID);

    assertThat(group).isNotNull();
  }
}
