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

import com.google.common.collect.ListMultimap;
import com.google.gerrit.server.git.ProjectRunnable;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

public interface ReplicationRunnable extends ProjectRunnable {
  URIish getURI();

  boolean wasCanceled();

  boolean isRetrying();

  Set<String> getRefs();

  void addRefs(Set<String> refs);

  void addRef(String ref);

  String getTaskIdHex();

  void canceledByReplication();

  boolean setToRetry();

  void addState(String ref, ReplicationState state);

  void run();

  void runSync();

  ReplicationState[] getStatesAsArray();

  void addStates(ListMultimap<String, ReplicationState> states);

  ListMultimap<String, ReplicationState> getStates();

  void removeStates();

  Set<TransportException> getFetchFailures();

  List<RefSpec> getFetchRefSpecs();

  boolean hasSucceeded();
}
