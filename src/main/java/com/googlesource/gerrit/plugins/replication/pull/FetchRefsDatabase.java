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

import static java.util.stream.Collectors.toMap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.replication.pull.transport.TransportProvider;
import java.io.IOException;
import java.util.Map;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class FetchRefsDatabase {
  private final TransportProvider transportProvider;

  @Inject
  public FetchRefsDatabase(TransportProvider transportProvider) {
    this.transportProvider = transportProvider;
  }

  public Map<String, Ref> getRemoteRefsMap(Repository repository, URIish uri) throws IOException {
    try (Transport tn = transportProvider.open(repository, uri);
        FetchConnection fc = tn.openFetch()) {
      return fc.getRefsMap();
    }
  }

  public Map<String, Ref> getLocalRefsMap(Repository repository) throws IOException {
    return repository.getRefDatabase().getRefs().stream().collect(toMap(Ref::getName, r -> r));
  }
}
