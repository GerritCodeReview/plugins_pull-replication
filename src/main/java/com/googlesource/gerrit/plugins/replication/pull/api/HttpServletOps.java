package com.googlesource.gerrit.plugins.replication.pull.api;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static org.eclipse.jgit.util.HttpSupport.TEXT_PLAIN;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HttpServletOps {
  static boolean checkAcceptHeader(HttpServletRequest req, HttpServletResponse rsp)
      throws IOException {
    if (req.getHeader(ACCEPT) != null
        && !Arrays.asList("text/plain", "text/*", "*/*").contains(req.getHeader(ACCEPT))) {
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
