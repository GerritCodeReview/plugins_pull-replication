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

package com.googlesource.gerrit.plugins.replication.pull;

/**
 * Allows to tag event as local to avoid consuming remote events.
 *
 * <p>TODO: Gerrit v3.1 doesn't have concept of the instanceId so ThreadLocal must be used. From
 * Gerrit v3.2 replace ThreadLocal with instanceId.
 */
public class Context {
  private static final ThreadLocal<Boolean> localEvent = ThreadLocal.withInitial(() -> false);

  private Context() {}

  public static Boolean isLocalEvent() {
    return localEvent.get();
  }

  public static void setLocalEvent(Boolean b) {
    localEvent.set(b);
  }

  public static void unsetLocalEvent() {
    localEvent.remove();
  }
}
