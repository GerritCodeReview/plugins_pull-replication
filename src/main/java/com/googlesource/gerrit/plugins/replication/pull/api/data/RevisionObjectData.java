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

package com.googlesource.gerrit.plugins.replication.pull.api.data;

import java.util.Base64;
import org.eclipse.jgit.lib.Constants;

public class RevisionObjectData {
  private final String sha1;
  private final Integer type;
  private final String content;

  public RevisionObjectData(String sha1, int type, byte[] content) {
    this.sha1 = sha1;
    this.type = type;
    this.content = content == null ? "" : Base64.getEncoder().encodeToString(content);
  }

  public Integer getType() {
    return type;
  }

  public byte[] getContent() {
    return Base64.getDecoder().decode(content);
  }

  public String getSha1() {
    return sha1;
  }

  @Override
  public String toString() {
    String typeStr;
    switch (type) {
      case Constants.OBJ_BLOB:
        typeStr = "BLOB";
        break;
      case Constants.OBJ_COMMIT:
        typeStr = "COMMIT";
        break;
      case Constants.OBJ_TREE:
        typeStr = "TREE";
        break;
      default:
        typeStr = "type:" + type;
        break;
    }

    return sha1 + " (" + typeStr + ")";
  }
}
