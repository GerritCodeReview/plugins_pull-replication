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

import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_MEMBERSHIP;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.account.IncludingGroupMembership;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class PullReplicationInternalUser extends PluginUser {

  private final boolean expandGroups;
  private final String pluginName;
  private final IncludingGroupMembership.Factory includingGroupMembership;

  @Inject
  protected PullReplicationInternalUser(
      @PluginName String pluginName, IncludingGroupMembership.Factory includingGroupMembership) {
    this(pluginName, includingGroupMembership, true);
  }

  private PullReplicationInternalUser(
      @PluginName String pluginName,
      IncludingGroupMembership.Factory includingGroupMembership,
      boolean expandGroups) {
    super(pluginName);
    this.pluginName = pluginName;
    this.includingGroupMembership = includingGroupMembership;
    this.expandGroups = expandGroups;
  }

  @Override
  public GroupMembership getEffectiveGroups() {
    if (expandGroups) {
      return new GroupMembershipUnion(
          INTERNAL_GROUP_MEMBERSHIP,
          includingGroupMembership.create(
              new PullReplicationInternalUser(pluginName, includingGroupMembership, false)));
    }

    return INTERNAL_GROUP_MEMBERSHIP;
  }
}
