// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

/* Weak dependency with the named annotation used in the global-refdb,
 * for avoiding the hassle of depending from another repository just for a
 * single string of an optional annotated binding.
 */
public interface GlobalRefDbConstants {
  public static final String LOCAL_DISK_REPOSITORY_MANAGER = "local_disk_repository_manager";
}
