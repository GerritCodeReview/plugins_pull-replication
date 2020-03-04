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
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jgit.lib.Config;

/** Provides an HTTP client with SSL capabilities. */
public class HttpClientFactory {
  private static final int DEFAULT_CONNECTIONS_PER_ROUTE = 100;

  private int maxConnectionsPerRoute;
  private int maxConnections;
  private Config cfg;

  @Inject
  HttpClientFactory(ReplicationConfig replicationConfig) {
    cfg = replicationConfig.getConfig();
    this.maxConnectionsPerRoute =
        cfg.getInt("replication", "maxConnectionsPerRoute", DEFAULT_CONNECTIONS_PER_ROUTE);
    this.maxConnections = cfg.getInt("replication", "maxConnections", 2 * maxConnectionsPerRoute);
  }

  public CloseableHttpClient create(Source source) {
    try {
      return HttpClients.custom()
          .setConnectionManager(customConnectionManager(source))
          .setDefaultRequestConfig(customRequestConfig(source))
          .build();
    } catch (Exception e) {
      throw new ProvisionException("Couldn't create CloseableHttpClient", e);
    }
  }

  private RequestConfig customRequestConfig(Source source) {
    int connectionTimeout = source.getConnectionTimeout();
    return RequestConfig.custom()
        .setConnectTimeout(connectionTimeout)
        .setSocketTimeout(connectionTimeout)
        .setConnectionRequestTimeout(connectionTimeout)
        .build();
  }

  private HttpClientConnectionManager customConnectionManager(Source source) throws Exception {
    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
    int idleTimeout = source.getIdleTimeout();

    connManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
    connManager.setMaxTotal(maxConnections);
    connManager.setValidateAfterInactivity(idleTimeout);
    return connManager;
  }
}
