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
package com.googlesource.gerrit.plugins.replication.pull;

import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_NAME;
import static com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationGroupBackend.INTERNAL_GROUP_UUID;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.pgm.init.api.AllProjectsConfig;
import com.google.gerrit.pgm.init.api.ConsoleUI;
import com.google.gerrit.pgm.init.api.InitStep;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

public class InitPlugin implements InitStep {

  private final String pluginName;
  private final BearerTokenProvider bearerTokenProvider;
  private final ConsoleUI ui;
  private final AllProjectsConfig allProjectsConfig;

  private static final String CAPABILITY_SECTION = "capability";

  @Inject
  InitPlugin(
      @PluginName String pluginName,
      BearerTokenProvider bearerTokenProvider,
      ConsoleUI ui,
      AllProjectsConfig allProjectsConfig) {
    this.pluginName = pluginName;
    this.bearerTokenProvider = bearerTokenProvider;
    this.ui = ui;
    this.allProjectsConfig = allProjectsConfig;
  }

  @Override
  public void run() {}

  @Override
  public void postRun() throws Exception {
    ui.header("%s initialization", pluginName);

    if (!bearerTokenProvider.get().isPresent()) {
      ui.message(
          "The %s plugin is not configured to use bearer token. If you are using basic auth,"
              + " remember to grant the '%s' global capability to all relevant users\n",
          pluginName, GlobalCapability.ACCESS_DATABASE);
      return;
    }

    ui.message(
        "Granting '%s' global capability to the '%s' user\n",
        GlobalCapability.ACCESS_DATABASE, INTERNAL_GROUP_NAME);

    upsertCapability();

    ui.message(
        "'%s' global capability granted to user '%s'\n",
        GlobalCapability.ACCESS_DATABASE, INTERNAL_GROUP_NAME);
  }

  private void upsertCapability() throws ConfigInvalidException, IOException {
    String pullReplicationGroup = "group " + INTERNAL_GROUP_NAME;

    Config cfg = allProjectsConfig.load().getConfig();
    String[] groupsWithAccessDatabase =
        cfg.getStringList(CAPABILITY_SECTION, null, GlobalCapability.ACCESS_DATABASE);

    if (Arrays.stream(groupsWithAccessDatabase).noneMatch(pullReplicationGroup::equals)) {
      cfg.setStringList(
          CAPABILITY_SECTION,
          null,
          GlobalCapability.ACCESS_DATABASE,
          Arrays.stream(ArrayUtils.add(groupsWithAccessDatabase, pullReplicationGroup))
              .collect(Collectors.toList()));

      allProjectsConfig
          .getGroups()
          .put(
              INTERNAL_GROUP_UUID, GroupReference.create(INTERNAL_GROUP_UUID, INTERNAL_GROUP_NAME));

      allProjectsConfig.save(pluginName, "Init step");
    }
  }
}
