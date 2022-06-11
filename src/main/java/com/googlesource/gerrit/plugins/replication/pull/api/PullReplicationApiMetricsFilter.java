// Copyright (C) 2022 The Android Open Source Project
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

import com.google.gerrit.httpd.AllRequestFilter;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class PullReplicationApiMetricsFilter extends AllRequestFilter {
  private final Provider<PullReplicationApiRequestMetrics> apiRequestMetrics;

  @Inject
  public PullReplicationApiMetricsFilter(
      Provider<PullReplicationApiRequestMetrics> apiRequestMetrics) {
    this.apiRequestMetrics = apiRequestMetrics;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
      chain.doFilter(request, response);
      return;
    }

    PullReplicationApiRequestMetrics requestMetrics = apiRequestMetrics.get();
    requestMetrics.start((HttpServletRequest) request);
    PullReplicationApiRequestMetrics.set(requestMetrics);

    chain.doFilter(request, response);
  }
}
