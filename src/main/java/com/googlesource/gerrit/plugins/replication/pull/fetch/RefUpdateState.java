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

import org.eclipse.jgit.lib.RefUpdate;

public class RefUpdateState {

  private String remoteName;
  private RefUpdate.Result result;

  public RefUpdateState(String remoteName, RefUpdate.Result result) {
    this.remoteName = remoteName;
    this.result = result;
  }

  public String getRemoteName() {
    return remoteName;
  }

  public RefUpdate.Result getResult() {
    return result;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("RefUpdateState[");
    sb.append(remoteName);
    sb.append(" ");
    sb.append(result);
    sb.append("]");
    return sb.toString();
  }
}
