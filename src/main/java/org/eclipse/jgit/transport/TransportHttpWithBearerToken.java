package org.eclipse.jgit.transport;

import static org.eclipse.jgit.util.HttpSupport.HDR_AUTHORIZATION;

import java.io.IOException;
import java.net.URL;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.http.HttpConnection;

public class TransportHttpWithBearerToken extends TransportHttp {

  private final String bearerToken;

  public TransportHttpWithBearerToken(Repository local, URIish uri, String bearer)
      throws NotSupportedException {
    super(local, uri);
    this.bearerToken = bearer;
  }

  protected HttpConnection httpOpen(String method, URL u, AcceptEncoding acceptEncoding)
      throws IOException {
    HttpConnection conn = super.httpOpen(method, u, acceptEncoding);
    conn.setRequestProperty(HDR_AUTHORIZATION, "Bearer " + bearerToken); // $NON-NLS-1$
    return conn;
  }
}
