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

package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

public class HttpPayloadGsonProvider {

  public static Gson get() {
    return OutputFormat.JSON
        .newGsonBuilder()
        .registerTypeAdapter(FetchAction.RefInput.class, new RefInputTypeAdapter())
        .create();
  }

  private static class RefInputTypeAdapter extends TypeAdapter<FetchAction.RefInput> {
    @Override
    public void write(JsonWriter out, FetchAction.RefInput value) throws IOException {
      out.beginObject();
      out.name("ref_name").value(value.refName());
      out.name("is_delete").value(value.isDelete());
      out.endObject();
    }

    @Override
    public FetchAction.RefInput read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      in.beginObject();
      String refName = "";
      boolean isDelete = false;

      while (in.hasNext()) {
        String name = in.nextName();
        switch (name) {
          case "ref_name":
            refName = in.nextString();
            break;
          case "is_delete":
            isDelete = in.nextBoolean();
            break;
          default:
            in.skipValue(); // Ignore unknown properties
            break;
        }
      }

      in.endObject();
      return FetchAction.RefInput.create(refName, isDelete);
    }
  }
}
