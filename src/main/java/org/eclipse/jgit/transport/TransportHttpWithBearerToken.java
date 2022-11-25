package org.eclipse.jgit.transport;

import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.net.URL;
import java.util.Set;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.http.HttpConnection;

public class TransportHttpWithBearerToken extends TransportHttp {

  private static final String EMPTY_STRING = "";
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
        && uri.getHost() != null
        && uri.getHost().trim() != EMPTY_STRING
        && uri.getPath() != null
        && uri.getPath().trim() != EMPTY_STRING;
  }
}
