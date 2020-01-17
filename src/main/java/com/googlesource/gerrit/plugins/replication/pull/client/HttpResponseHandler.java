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

package com.googlesource.gerrit.plugins.replication.pull.client;

import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResponseHandler.HttpResult;
import java.io.IOException;
import java.util.Optional;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.util.EntityUtils;

public class HttpResponseHandler implements ResponseHandler<HttpResult> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int UNPROCESSABLE_ENTITY = 422;

  public static class HttpResult {
    private final Optional<String> message;
    private final int responseCode;

    HttpResult(int responseCode, Optional<String> message) {
      this.message = message;
      this.responseCode = responseCode;
    }

    public Optional<String> getMessage() {
      return message;
    }

    public boolean isSuccessful() {
      return responseCode == SC_CREATED || responseCode == SC_NO_CONTENT || responseCode == SC_OK;
    }

    public boolean shouldRetry() {
      return !isSuccessful() && responseCode != UNPROCESSABLE_ENTITY;
    }
  }

  @Override
  public HttpResult handleResponse(HttpResponse response) {
    return new HttpResult(response.getStatusLine().getStatusCode(), parseResponse(response));
  }

  private static Optional<String> parseResponse(HttpResponse response) {
    HttpEntity entity = response.getEntity();
    if (entity != null) {
      try {
        return Optional.ofNullable(EntityUtils.toString(entity));
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error parsing entity");
      }
    }
    return Optional.empty();
  }
}
