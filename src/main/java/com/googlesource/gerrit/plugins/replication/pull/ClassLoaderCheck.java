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

public class ClassLoaderCheck {
  public static boolean isLoaded(String className) {
    ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

    try {
      Class<?> clazz = Class.forName(className);
      ClassLoader classLoader = clazz.getClassLoader();

      if (classLoader == null
          || classLoader == systemClassLoader
          || isLoadedByParent(classLoader, systemClassLoader)) {
        return true;
      }
    } catch (ClassNotFoundException ignored) {
    }
    return false;
  }

  private static boolean isLoadedByParent(ClassLoader classLoader, ClassLoader parent) {
    if (classLoader == null) {
      return false;
    }
    if (classLoader == parent) {
      return true;
    }
    return isLoadedByParent(classLoader.getParent(), parent);
  }
}
