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

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.common.base.MoreObjects;
import java.util.List;
import org.eclipse.jgit.transport.RefSpec;

public class FetchRefSpec extends RefSpec {

  public static FetchRefSpec fromRefSpec(RefSpec refSpec) {
    return new FetchRefSpec(refSpec.toString());
  }

  public static FetchRefSpec fromRef(String refName) {
    return new FetchRefSpec(refName);
  }

  public static List<RefSpec> toListOfRefSpec(List<FetchRefSpec> fetchRefSpecsList) {
    return List.copyOf(fetchRefSpecsList);
  }

  private FetchRefSpec(String refSpecString) {
    super(refSpecString);
  }

  public boolean equalsToRef(String cmpRefName) {
    return cmpRefName.equals(refName());
  }

  public String refName() {
    return MoreObjects.firstNonNull(getSource(), getDestination());
  }

  public boolean isDelete() {
    return getSource() == null;
  }

  @Override
  public String toString() {
    return getSource() == null ? "<<DELETED>>:" + getDestination() : super.toString();
  }
}
