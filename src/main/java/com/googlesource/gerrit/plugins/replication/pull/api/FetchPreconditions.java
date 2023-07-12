// Copyright (C) 2020 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.googlesource.gerrit.plugins.replication.pull.api.FetchApiCapability.CALL_FETCH_ACTION;

import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.access.PluginPermission;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackend.ForRef;
import com.google.gerrit.server.permissions.PermissionBackend.WithUser;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.UnauthorizedAuthException;

public class FetchPreconditions {
  private final String pluginName;
  private final PermissionBackend permissionBackend;
  private final Provider<CurrentUser> userProvider;

  @Inject
  public FetchPreconditions(
      @PluginName String pluginName,
      Provider<CurrentUser> userProvider,
      PermissionBackend permissionBackend) {
    this.pluginName = pluginName;
    this.userProvider = userProvider;
    this.permissionBackend = permissionBackend;
  }

  public Boolean canCallFetchApi() throws UnauthorizedAuthException {
    CurrentUser currentUser = currentUser();
    PermissionBackend.WithUser userPermission = permissionBackend.user(currentUser);
    return currentUser.isInternalUser()
        || userPermission.testOrFalse(GlobalPermission.ADMINISTRATE_SERVER)
        || userPermission.testOrFalse(new PluginPermission(pluginName, CALL_FETCH_ACTION));
  }

  public Boolean canCallUpdateHeadApi(Project.NameKey projectNameKey, String ref)
      throws PermissionBackendException, UnauthorizedAuthException {
    WithUser userAcls = permissionBackend.user(currentUser());
    ForRef refAcls = userAcls.project(projectNameKey).ref(ref);

    return canCallFetchApi() || refAcls.test(RefPermission.SET_HEAD);
  }

  private CurrentUser currentUser() throws UnauthorizedAuthException {
    CurrentUser currentUser = userProvider.get();
    if (!currentUser.isIdentifiedUser() && !currentUser.isInternalUser()) {
      throw new UnauthorizedAuthException();
    }
    return currentUser;
  }
}
