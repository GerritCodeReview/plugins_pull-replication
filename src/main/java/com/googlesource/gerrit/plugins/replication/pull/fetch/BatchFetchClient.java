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

package com.googlesource.gerrit.plugins.replication.pull.fetch;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.SourceConfiguration;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

public class BatchFetchClient implements Fetch {
  private int batchSize;
  private Fetch fetchClient;

  @Inject
  public BatchFetchClient(
      SourceConfiguration config,
      FetchFactory factory,
      @Assisted String taskHexId,
      @Assisted URIish uri,
      @Assisted Repository git) {
    this.batchSize = config.getRefsBatchSize();
    this.fetchClient = factory.createPlainImpl(taskHexId, uri, git);
  }

  @Override
  public List<RefUpdateState> fetch(List<RefSpec> refs) throws IOException {
    List<RefUpdateState> results = Lists.newArrayList();
    for (List<RefSpec> refsBatch : Lists.partition(refs, batchSize)) {
      results.addAll(fetchClient.fetch(refsBatch));
    }
    return results;
  }
}
