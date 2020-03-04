// Copyright (C) 2018 The Android Open Source Project
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
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.googlesource.gerrit.plugins.replication.ReplicationFileBasedConfig;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.eclipse.jgit.lib.Config;

/** Provides an HTTP client with SSL capabilities. */
public class HttpClientProvider implements Provider<CloseableHttpClient> {
  private static final int DEFAULT_CONNECTIONS_PER_ROUTE = 100;
  private static final int DEFAULT_MAX_CONNECTION_INACTIVITY_MS = 10000;
  private static final int DEFAULT_TIMEOUT_MS = 5000;

  private int maxConnectionsPerRoute;
  private int maxConnections;
  private int connectionTimeout;
  private int idleTimeout;

  @Inject
  HttpClientProvider(ReplicationFileBasedConfig replicationConfig) {
	  Config cfg = replicationConfig.getConfig();
	  this.maxConnectionsPerRoute = cfg.getInt("replication", "maxConnectionsPerRoute", DEFAULT_CONNECTIONS_PER_ROUTE);
	  this.maxConnections = cfg.getInt("replication", "maxConnections", 2 * maxConnectionsPerRoute);
	  this.connectionTimeout = cfg.getInt("replication", "connectionTimeout", DEFAULT_TIMEOUT_MS);
	  this.idleTimeout = cfg.getInt("replication", "idleTimeout", DEFAULT_MAX_CONNECTION_INACTIVITY_MS);
  }
  
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
        .setConnectTimeout(connectionTimeout)
        .setSocketTimeout(connectionTimeout)
        .setConnectionRequestTimeout(connectionTimeout)
        .build();
  }

  private HttpClientConnectionManager customConnectionManager() throws Exception {
    PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
    connManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
    connManager.setMaxTotal(maxConnections);
    connManager.setValidateAfterInactivity(idleTimeout);
    return connManager;
  }
}
