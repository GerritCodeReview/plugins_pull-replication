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

package org.eclipse.jgit.transport;

import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URL;
import java.util.Set;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.http.HttpConnection;

public class TransportHttpWithBearerToken extends TransportHttp {

  private static final String SCHEME_HTTP = "http";
  private static final String SCHEME_HTTPS = "https";
  private static final Set<String> SCHEMES_ALLOWED = ImmutableSet.of(SCHEME_HTTP, SCHEME_HTTPS);

  private final String bearerToken;

  /*
  In the case of git push operation, this class cannot be used as a http transport because the push hook (PrePushHook prePush)
  is defined as a private and it could not be initialised.
   */
  public TransportHttpWithBearerToken(Repository local, URIish uri, String bearerToken)
      throws NotSupportedException {
    super(local, uri);
    this.bearerToken = bearerToken;
  }

  protected HttpConnection httpOpen(String method, URL u, AcceptEncoding acceptEncoding)
      throws IOException {
    HttpConnection conn = super.httpOpen(method, u, acceptEncoding);
    conn.setRequestProperty(HDR_AUTHORIZATION, "Bearer " + bearerToken); // $NON-NLS-1$
    return conn;
  }

  public static boolean canHandle(URIish uri) {
    return SCHEMES_ALLOWED.contains(uri.getScheme())
        && Strings.isNullOrEmpty(uri.getHost())
        && Strings.isNullOrEmpty(uri.getPath());
  }
}
