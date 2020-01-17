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

import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

/** Provides an HTTP client with SSL capabilities. */
public class HttpClientProvider implements Provider<CloseableHttpClient> {
  private static final int CONNECTIONS_PER_ROUTE = 100;

  // Up to 2 target instances with the max number of connections per host:
  private static final int MAX_CONNECTIONS = 2 * CONNECTIONS_PER_ROUTE;

  private static final int MAX_CONNECTION_INACTIVITY_MS = 10000;
  private static final int DEFAULT_TIMEOUT_MS = 5000;

  @Override
  public CloseableHttpClient get() {
    try {
      return HttpClients.custom()
          .setConnectionManager(customConnectionManager())
          .setDefaultRequestConfig(customRequestConfig())
          .build();
    } catch (Exception e) {
      throw new ProvisionException("Couldn't create CloseableHttpClient", e);
    }
  }

  private RequestConfig customRequestConfig() {
    return RequestConfig.custom()
        .setConnectTimeout(DEFAULT_TIMEOUT_MS)
        .setSocketTimeout(DEFAULT_TIMEOUT_MS)
        .setConnectionRequestTimeout(DEFAULT_TIMEOUT_MS)
        .build();
  }

  private HttpClientConnectionManager customConnectionManager() throws Exception {
    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
    connManager.setDefaultMaxPerRoute(CONNECTIONS_PER_ROUTE);
    connManager.setMaxTotal(MAX_CONNECTIONS);
    connManager.setValidateAfterInactivity(MAX_CONNECTION_INACTIVITY_MS);
    return connManager;
  }
}
