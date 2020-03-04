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

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import java.io.IOException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

/** Apache HTTP client implementation based on Source-specific parameters */
public class SourceHttpClient implements HttpClient {
  private final Source source;

  public interface Factory {
    public HttpClient create(Source source);
  }

  @Inject
  public SourceHttpClient(@Assisted Source source) {
    this.source = source;
  }

  @Override
  public <T> T execute(
      HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context)
      throws ClientProtocolException, IOException {
    return source
        .memoize(
            () ->
                HttpClients.custom()
                    .setConnectionManager(customConnectionManager(source))
                    .setDefaultRequestConfig(customRequestConfig(source))
                    .build())
        .execute(request, responseHandler, context);
  }

  private static RequestConfig customRequestConfig(Source source) {
    int connectionTimeout = source.getConnectionTimeout();
    return RequestConfig.custom()
        .setConnectTimeout(connectionTimeout)
        .setSocketTimeout(connectionTimeout)
        .setConnectionRequestTimeout(connectionTimeout)
        .build();
  }

  private static HttpClientConnectionManager customConnectionManager(Source source) {
    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();

    connManager.setDefaultMaxPerRoute(source.getMaxConnectionsPerRoute());
    connManager.setMaxTotal(source.getMaxConnections());
    connManager.setValidateAfterInactivity(source.getIdleTimeout());
    return connManager;
  }
}
