// Copyright (C) 2021 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.event;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.FetchRefReplicatedEvent;

public class FetchRefReplicatedEventHandler implements EventListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final String pluginName;
  private GerritApi gApi;

  @Inject
  FetchRefReplicatedEventHandler(GerritApi gApi, @PluginName String pluginName) {
    this.pluginName = pluginName;
    this.gApi = gApi;
  }

  @Override
  public void onEvent(Event event) {

    if (event instanceof FetchRefReplicatedEvent) {
      FetchRefReplicatedEvent fetchRefReplicatedEvent = (FetchRefReplicatedEvent) event;
      logger.atInfo().log(
          "Indexing ref '%s' for project %s",
          fetchRefReplicatedEvent.getRefName(), fetchRefReplicatedEvent.getProjectNameKey().get());
      String project = fetchRefReplicatedEvent.getProjectNameKey().toString();
      int changeNumber = getChangeNumber(fetchRefReplicatedEvent.getRefName());
      try {
        gApi.changes().id(project, changeNumber).index();
      } catch (RestApiException restApiException) {
        logger.atWarning().withCause(restApiException).log(
            "Couldn't index change %d for project %s", changeNumber, project);
      }
    }
  }

  private int getChangeNumber(String ref) {
    // Refs have this format: refs/changes/24/124/meta
    return Integer.parseInt(ref.split("/")[3]);
  }
}
