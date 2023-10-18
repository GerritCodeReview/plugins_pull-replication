package com.googlesource.gerrit.plugins.replication.pull.api.util;

import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.api.projects.HeadInput;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.httpd.restapi.RestApiServlet;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.googlesource.gerrit.plugins.replication.pull.api.FetchAction;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionInput;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionsInput;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class PayloadSerDes {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Gson gson = OutputFormat.JSON.newGsonBuilder().create();

  public RevisionInput parseRevisionInput(HttpServletRequest httpRequest)
      throws BadRequestException, IOException {
    return parse(httpRequest, TypeLiteral.get(RevisionInput.class));
  }

  public RevisionsInput parseRevisionsInput(HttpServletRequest httpRequest)
      throws BadRequestException, IOException {
    return parse(httpRequest, TypeLiteral.get(RevisionsInput.class));
  }

  public HeadInput parseHeadInput(HttpServletRequest httpRequest)
      throws BadRequestException, IOException {
    return parse(httpRequest, TypeLiteral.get(HeadInput.class));
  }

  public FetchAction.Input parseInput(HttpServletRequest httpRequest)
      throws BadRequestException, IOException {
    return parse(httpRequest, TypeLiteral.get(FetchAction.Input.class));
  }

  public <T> void writeResponse(HttpServletResponse httpResponse, Response<T> response)
      throws IOException {
    String responseJson = gson.toJson(response);
    if (response.statusCode() == SC_OK || response.statusCode() == SC_CREATED) {

      httpResponse.setContentType("application/json");
      httpResponse.setStatus(response.statusCode());
      PrintWriter writer = httpResponse.getWriter();
      writer.print(new String(RestApiServlet.JSON_MAGIC));
      writer.print(responseJson);
    } else {
      httpResponse.sendError(response.statusCode(), responseJson);
    }
  }

  private <T> T parse(HttpServletRequest httpRequest, TypeLiteral<T> typeLiteral)
      throws IOException, BadRequestException {

    try (BufferedReader br = httpRequest.getReader();
        JsonReader json = new JsonReader(br)) {
      try {
        json.setLenient(true);

        try {
          json.peek();
        } catch (EOFException e) {
          throw new BadRequestException("Expected JSON object", e);
        }

        return gson.fromJson(json, typeLiteral.getType());
      } finally {
        try {
          // Reader.close won't consume the rest of the input. Explicitly consume the request
          // body.
          br.skip(Long.MAX_VALUE);
        } catch (Exception e) {
          // ignore, e.g. trying to consume the rest of the input may fail if the request was
          // cancelled
          logger.atFine().withCause(e).log("Exception during the parsing of the request json");
        }
      }
    }
  }
}
