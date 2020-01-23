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
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResponseHandler.HttpResult;
import java.io.IOException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.util.EntityUtils;

public class HttpResponseHandler implements ResponseHandler<HttpResult> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static class HttpResult {
    private final boolean successful;
    private final String message;

    HttpResult(boolean successful, String message) {
      this.successful = successful;
      this.message = message;
    }

    public boolean isSuccessful() {
      return successful;
    }

    public String getMessage() {
      return message;
    }

    public boolean isProjectMissing(Project.NameKey projectName) {
      String projectMissingMessage = String.format("Not found: %s", projectName.get());
      return message.contains(projectMissingMessage);
    }
  }

  @Override
  public HttpResult handleResponse(HttpResponse response) {
    return new HttpResult(isSuccessful(response), parseResponse(response));
  }

  private static boolean isSuccessful(HttpResponse response) {
    int sc = response.getStatusLine().getStatusCode();
    return sc == SC_CREATED || sc == SC_NO_CONTENT || sc == SC_OK;
  }

  private static String parseResponse(HttpResponse response) {
    HttpEntity entity = response.getEntity();
    if (entity != null) {
      try {
        return EntityUtils.toString(entity);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Error parsing entity");
      }
    }
    return "";
  }
}
