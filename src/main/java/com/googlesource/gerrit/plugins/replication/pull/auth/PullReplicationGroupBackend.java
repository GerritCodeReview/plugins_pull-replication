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

import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.GroupDescription;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.AbstractGroupBackend;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.ListGroupMembership;
import com.google.gerrit.server.group.SystemGroupBackend;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.Singleton;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Backend to expose the pull-replication internal user group membership. */
@Singleton
public class PullReplicationGroupBackend extends AbstractGroupBackend {
  public static final AccountGroup.UUID INTERNAL_GROUP_UUID =
      AccountGroup.uuid("pullreplication:internal-user");
  public static final String INTERNAL_GROUP_NAME = "Pull-replication Internal User";
  public static final String NAME_PREFIX = "pullreplication/";
  public static final GroupDescription.Basic INTERNAL_GROUP_DESCRIPTION =
      new GroupDescription.Basic() {

        @Override
        public String getUrl() {
          return null;
        }

        @Override
        public String getName() {
          return INTERNAL_GROUP_NAME;
        }

        @Override
        public AccountGroup.UUID getGroupUUID() {
          return INTERNAL_GROUP_UUID;
        }

        @Override
        public String getEmailAddress() {
          return null;
        }
      };
  static final ListGroupMembership INTERNAL_GROUP_MEMBERSHIP =
      new ListGroupMembership(
          Arrays.asList(INTERNAL_GROUP_UUID, SystemGroupBackend.ANONYMOUS_USERS));

  @Override
  public boolean handles(AccountGroup.UUID uuid) {
    return INTERNAL_GROUP_UUID.equals(uuid);
  }

  @Override
  public GroupDescription.Basic get(AccountGroup.UUID uuid) {
    return handles(uuid) ? INTERNAL_GROUP_DESCRIPTION : null;
  }

  @Override
  public Collection<GroupReference> suggest(String name, ProjectState project) {
    return NAME_PREFIX.contains(name.toLowerCase())
        ? List.of(GroupReference.create(INTERNAL_GROUP_UUID, INTERNAL_GROUP_NAME))
        : Collections.emptyList();
  }

  @Override
  public GroupMembership membershipsOf(CurrentUser user) {
    if (user instanceof PullReplicationInternalUser) {
      return INTERNAL_GROUP_MEMBERSHIP;
    }

    return ListGroupMembership.EMPTY;
  }
}
