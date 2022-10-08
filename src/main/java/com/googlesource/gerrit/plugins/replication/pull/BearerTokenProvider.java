package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;

@Singleton
public class BearerTokenProvider implements Provider<Optional<String>> {

  private final Optional<String> bearerToken;

  @Inject
  public BearerTokenProvider(@GerritServerConfig Config gerritConfig) {
    this.bearerToken = Optional.ofNullable(gerritConfig.getString("auth", null, "bearerToken"));
  }

  @Override
  public Optional<String> get() {
    return bearerToken;
  }
}
