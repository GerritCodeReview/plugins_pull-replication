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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static org.eclipse.jgit.util.HttpSupport.TEXT_PLAIN;

import com.google.common.net.MediaType;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class HttpServletOps {

  static boolean checkAcceptHeader(HttpServletRequest req, HttpServletResponse rsp)
      throws IOException {
    if (req.getHeader(ACCEPT) == null
        || (req.getHeader(ACCEPT) != null
            && !Arrays.asList(
                    MediaType.PLAIN_TEXT_UTF_8.toString(),
                    MediaType.ANY_TEXT_TYPE.toString(),
                    MediaType.ANY_TYPE.toString())
                .contains(req.getHeader(ACCEPT)))) {
      setResponse(
          rsp,
          HttpServletResponse.SC_BAD_REQUEST,
          "No advertised 'Accept' headers can be honoured. 'text/plain' should be provided in the request 'Accept' header.");
      return false;
    }

    return true;
  }

  static void setResponse(HttpServletResponse httpResponse, int statusCode, String value)
      throws IOException {
    httpResponse.setContentType(TEXT_PLAIN);
    httpResponse.setStatus(statusCode);
    PrintWriter writer = httpResponse.getWriter();
    writer.print(value);
  }
}
