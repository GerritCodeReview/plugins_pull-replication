/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.replication.pull;


import com.google.gson.Gson;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction.RefInput;
import com.googlesource.gerrit.plugins.replication.pull.api.HttpPayloadGsonProvider;
import java.util.Arrays;
import java.util.stream.Collectors;

public class FetchActionTestUtil {

  private static final Gson gson = HttpPayloadGsonProvider.get();

  public static FetchAction.BatchInput createBatchInput(
      String label, boolean async, RefInput... refInputs) {
    FetchAction.BatchInput batchInput = new FetchAction.BatchInput();
    batchInput.label = label;
    batchInput.async = async;
    batchInput.refInputs = Arrays.stream(refInputs).collect(Collectors.toSet());
    return batchInput;
  }

  public static FetchAction.BatchInput createBatchInput(
      String label, boolean async, String... refNames) {
    return createBatchInput(
        label, false, Arrays.stream(refNames).map(RefInput::create).toArray(RefInput[]::new));
  }

  public static <T> String toJsonString(T obj) {
    return gson.toJson(obj);
  }
}
