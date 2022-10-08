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
