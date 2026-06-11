// Copyright (C) 2024 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api.exception;

import java.io.IOException;
import org.eclipse.jgit.lib.RefUpdate;

public class DeleteRefException extends IOException {

  private static final long serialVersionUID = 1L;
  private final RefUpdate.Result result;

  public DeleteRefException(String msg, RefUpdate.Result result) {
    super(msg);
    this.result = result;
  }

  public RefUpdate.Result getResult() {
    return result;
  }
}
