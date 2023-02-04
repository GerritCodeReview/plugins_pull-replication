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

package com.googlesource.gerrit.plugins.replication.pull.auth;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gerrit.entities.AccountGroup.UUID;
import com.google.gerrit.server.account.GroupMembership;
import java.util.Set;

class GroupMembershipUnion implements GroupMembership {
  private final GroupMembership membershipA;
  private final GroupMembership membershipB;

  GroupMembershipUnion(GroupMembership membershipA, GroupMembership membershipB) {
    this.membershipA = membershipA;
    this.membershipB = membershipB;
  }

  @Override
  public boolean contains(UUID groupId) {
    return membershipA.contains(groupId) || membershipB.contains(groupId);
  }

  @Override
  public boolean containsAnyOf(Iterable<UUID> groupIds) {
    return membershipA.containsAnyOf(groupIds) || membershipB.containsAnyOf(groupIds);
  }

  @Override
  public Set<UUID> intersection(Iterable<UUID> groupIds) {
    return Sets.intersection(
        ImmutableSet.copyOf(membershipA.intersection(groupIds)),
        ImmutableSet.copyOf(membershipB.intersection(groupIds)));
  }

  @Override
  public Set<UUID> getKnownGroups() {
    return Sets.union(membershipA.getKnownGroups(), membershipB.getKnownGroups());
  }
}
