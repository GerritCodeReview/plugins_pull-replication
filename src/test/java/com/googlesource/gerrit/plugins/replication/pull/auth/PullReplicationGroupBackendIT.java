package com.googlesource.gerrit.plugins.replication.pull.auth;

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_DESCRIPTION;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_NAME;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_UUID;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.NAME_PREFIX;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.group.SystemGroupBackend;
import java.util.Collection;
import org.junit.Test;

@SkipProjectClone
@TestPlugin(
    name = "pull-replication",
    sysModule = "com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupModule")
public class PullReplicationGroupBackendIT extends LightweightPluginDaemonTest {

  @Test
  public void shouldResolvePullReplicationInternalGroup() {
    GroupDescription.Basic group = groupBackend.get(INTERNAL_GROUP_UUID);

    assertThat(group).isNotNull();
    assertThat(group).isEqualTo(INTERNAL_GROUP_DESCRIPTION);
  }

  @Test
  public void shouldSuggestPullReplicationInternalGroup() {
    Collection<GroupReference> groups = groupBackend.suggest(NAME_PREFIX, null);

    assertThat(groups).isNotNull();
    assertThat(groups).hasSize(1);

    GroupReference groupReference = groups.iterator().next();
    assertThat(groupReference.getName()).isEqualTo(INTERNAL_GROUP_NAME);
    assertThat(groupReference.getUUID()).isEqualTo(INTERNAL_GROUP_UUID);
  }

  @Test
  public void pullReplicationInternalUserShouldHaveMembershipOfInternalGroupAndAnonymousUsers() {
    assertMemberOfInternalAndAnonymousUsers(
        groupBackend.membershipsOf(getPullReplicationInternalUser()));
  }

  @Test
  public void pullReplicationInternalUserShouldHaveEffectiveGroups() {
    assertMemberOfInternalAndAnonymousUsers(getPullReplicationInternalUser().getEffectiveGroups());
  }

  private CurrentUser getPullReplicationInternalUser() {
    CurrentUser user = plugin.getSysInjector().getInstance(PullReplicationInternalUser.class);
    return user;
  }

  private void assertMemberOfInternalAndAnonymousUsers(GroupMembership userMembership) {
    assertThat(userMembership.contains(INTERNAL_GROUP_UUID)).isTrue();
    assertThat(userMembership.contains(SystemGroupBackend.ANONYMOUS_USERS)).isTrue();
  }
}
