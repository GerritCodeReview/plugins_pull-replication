// Copyright (C) 2015 The Android Open Source Project
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

import com.google.common.base.Strings;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import java.util.Collection;
import java.util.List;
import org.kohsuke.args4j.Option;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name = "list", description = "List remote source information")
final class ListCommand extends SshCommand {
  @Option(name = "--remote", metaVar = "PATTERN", usage = "pattern to match remote name on")
  private String remote;

  @Option(name = "--detail", usage = "output detailed information")
  private boolean detail;

  @Option(name = "--json", usage = "output in json format")
  private boolean json;

  @Inject private SourcesCollection sourcesCollection;

  @Override
  protected void run() {
    for (Source s : sourcesCollection.getAll()) {
      if (matches(s.getRemoteConfigName())) {
        printRemote(s);
      }
    }
  }

  private boolean matches(String name) {
    return (Strings.isNullOrEmpty(remote) || name.contains(remote) || name.matches(remote));
  }

  private void addProperty(JsonObject obj, String key, List<String> values) {
    if (!values.isEmpty()) {
      JsonArray list = new JsonArray();
      for (String v : values) {
        list.add(new JsonPrimitive(v));
      }
      obj.add(key, list);
    }
  }

  private void addQueueDetails(StringBuilder out, Collection<ReplicationRunnable> values) {
    for (ReplicationRunnable f : values) {
      out.append("  ").append(f.toString()).append("\n");
    }
  }

  private void addQueueDetails(JsonObject obj, String key, Collection<ReplicationRunnable> values) {
    if (values.size() > 0) {
      JsonArray list = new JsonArray();
      for (ReplicationRunnable f : values) {
        list.add(new JsonPrimitive(f.toString()));
      }
      obj.add(key, list);
    }
  }

  private void printRemote(Source s) {
    if (json) {
      JsonObject obj = new JsonObject();
      obj.addProperty("Remote", s.getRemoteConfigName());
      addProperty(obj, "Url", s.getUrls());
      if (detail) {
        addProperty(obj, "AdminUrl", s.getAdminUrls());
        addProperty(obj, "AuthGroup", s.getAuthGroupNames());
        addProperty(obj, "Project", s.getProjects());
        Source.QueueInfo q = s.getQueueInfo();
        addQueueDetails(obj, "InFlight", q.inFlight.values());
        addQueueDetails(obj, "Pending", q.pending.values());
      }
      stdout.print(obj.toString() + "\n");
    } else {
      StringBuilder out = new StringBuilder();
      out.append("Remote: ").append(s.getRemoteConfigName()).append("\n");
      for (String url : s.getUrls()) {
        out.append("Url: ").append(url).append("\n");
      }

      if (detail) {
        for (String adminUrl : s.getAdminUrls()) {
          out.append("AdminUrl: ").append(adminUrl).append("\n");
        }

        for (String authGroup : s.getAuthGroupNames()) {
          out.append("AuthGroup: ").append(authGroup).append("\n");
        }

        for (String project : s.getProjects()) {
          out.append("Project: ").append(project).append("\n");
        }

        Source.QueueInfo q = s.getQueueInfo();
        out.append("In Flight: ").append(q.inFlight.size()).append("\n");
        addQueueDetails(out, q.inFlight.values());
        out.append("Pending: ").append(q.pending.size()).append("\n");
        addQueueDetails(out, q.pending.values());
      }
      stdout.print(out.toString() + "\n");
    }
  }
}
