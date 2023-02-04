// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.auth;

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_DESCRIPTION;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_NAME;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_UUID;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.NAME_PREFIX;

import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.SkipProjectClone;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.entities.AccountGroup.UUID;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.group.SystemGroupBackend;
import java.util.Collection;
import java.util.Set;
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
  public void shouldSuggestEmptyListIfNameNotMatched() {
    Collection<GroupReference> groups = groupBackend.suggest("nonMatchablePrefix", null);

    assertThat(groups).isEmpty();
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

  @Test
  public void pullReplicationInternalUserShouldBePartOfNestedGroups() throws RestApiException {
    String parentGroupName = "parentGroupName";
    createGroup(parentGroupName);

    GroupMembership userMembership = groupBackend.membershipsOf(getPullReplicationInternalUser());
    assertThat(userMembership.contains(groupUuid(parentGroupName))).isTrue();
  }

  @Test
  public void pullReplicationInternalUserEffectiveGroupsShouldIncludeNestedGroups()
      throws RestApiException {
    String parentGroupName = "parentGroupName";
    createGroup(parentGroupName);

    Set<UUID> internalUserEffectiveGroups =
        getPullReplicationInternalUser().getEffectiveGroups().getKnownGroups();

    assertThat(internalUserEffectiveGroups).contains(groupUuid(parentGroupName));
  }

  private void createGroup(String groupName) throws RestApiException {
    GroupInfo groupInfo = gApi.groups().create(groupName).get();
    gApi.groups().id(groupInfo.id).addGroups(INTERNAL_GROUP_UUID.get());
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
