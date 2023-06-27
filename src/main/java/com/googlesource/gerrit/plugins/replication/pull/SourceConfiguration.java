// Copyright (C) 2016 The Android Open Source Project
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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.server.config.ConfigUtil;
import com.googlesource.gerrit.plugins.replication.RemoteConfiguration;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.RemoteConfig;

public class SourceConfiguration implements RemoteConfiguration {
  static final int DEFAULT_REPLICATION_DELAY = 4;
  static final int DEFAULT_RESCHEDULE_DELAY = 3;
  static final int DEFAULT_SLOW_LATENCY_THRESHOLD_SECS = 900;
  static final int DEFAULT_MAX_CONNECTION_INACTIVITY_MS = 10000;
  static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;
  static final int DEFAULT_CONNECTIONS_PER_ROUTE = 100;

  private final int delay;
  private final int rescheduleDelay;
  private final int retryDelay;
  private final int lockErrorMaxRetries;
  private final ImmutableList<String> adminUrls;
  private final int poolThreads;
  private final boolean replicatePermissions;
  private final boolean replicateHiddenProjects;
  private final boolean createMissingRepositories;
  private final boolean replicateProjectDeletions;
  private final String remoteNameStyle;
  private final ImmutableList<String> urls;
  private final ImmutableList<String> projects;
  private final ImmutableList<String> authGroupNames;
  private final RemoteConfig remoteConfig;
  private final ImmutableList<String> apis;
  private final int connectionTimeout;
  private final int idleTimeout;
  private final int maxConnectionsPerRoute;
  private final int maxConnections;
  private final int maxRetries;
  private int slowLatencyThreshold;
  private boolean useCGitClient;
  private int refsBatchSize;
  private boolean enableBatchedRefs;

  public SourceConfiguration(RemoteConfig remoteConfig, Config cfg) {
    this.remoteConfig = remoteConfig;
    String name = remoteConfig.getName();
    urls = ImmutableList.copyOf(cfg.getStringList("remote", name, "url"));
    apis = ImmutableList.copyOf(cfg.getStringList("remote", name, "apiUrl"));
    connectionTimeout =
        cfg.getInt("remote", name, "connectionTimeout", DEFAULT_CONNECTION_TIMEOUT_MS);
    idleTimeout = cfg.getInt("remote", name, "idleTimeout", DEFAULT_MAX_CONNECTION_INACTIVITY_MS);
    maxConnectionsPerRoute =
        cfg.getInt("replication", "maxConnectionsPerRoute", DEFAULT_CONNECTIONS_PER_ROUTE);
    maxConnections = cfg.getInt("replication", "maxConnections", 2 * maxConnectionsPerRoute);
    delay = Math.max(0, getInt(remoteConfig, cfg, "replicationdelay", DEFAULT_REPLICATION_DELAY));
    rescheduleDelay =
        Math.max(3, getInt(remoteConfig, cfg, "rescheduledelay", DEFAULT_RESCHEDULE_DELAY));
    projects = ImmutableList.copyOf(cfg.getStringList("remote", name, "projects"));
    adminUrls = ImmutableList.copyOf(cfg.getStringList("remote", name, "adminUrl"));
    retryDelay = Math.max(0, getInt(remoteConfig, cfg, "replicationretry", 1));
    poolThreads = Math.max(0, getInt(remoteConfig, cfg, "threads", 1));
    authGroupNames = ImmutableList.copyOf(cfg.getStringList("remote", name, "authGroup"));
    lockErrorMaxRetries = cfg.getInt("replication", "lockErrorMaxRetries", 0);

    createMissingRepositories = cfg.getBoolean("remote", name, "createMissingRepositories", true);
    replicateProjectDeletions = cfg.getBoolean("remote", name, "replicateProjectDeletions", true);
    replicatePermissions = cfg.getBoolean("remote", name, "replicatePermissions", true);
    replicateHiddenProjects = cfg.getBoolean("remote", name, "replicateHiddenProjects", false);
    useCGitClient = cfg.getBoolean("replication", "useCGitClient", false);
    refsBatchSize = cfg.getInt("replication", "refsBatchSize", 50);
    if (refsBatchSize <= 0)
      throw new IllegalArgumentException("refsBatchSize must be greater than zero");
    remoteNameStyle =
        MoreObjects.firstNonNull(cfg.getString("remote", name, "remoteNameStyle"), "slash");
    maxRetries =
        getInt(
            remoteConfig, cfg, "replicationMaxRetries", cfg.getInt("replication", "maxRetries", 0));
    slowLatencyThreshold =
        (int)
            ConfigUtil.getTimeUnit(
                cfg,
                "remote",
                remoteConfig.getName(),
                "slowLatencyThreshold",
                DEFAULT_SLOW_LATENCY_THRESHOLD_SECS,
                TimeUnit.SECONDS);
    enableBatchedRefs = cfg.getBoolean("replication", "enableBatchedRefs", false);
  }

  @Override
  public int getDelay() {
    return delay;
  }

  @Override
  public int getRescheduleDelay() {
    return rescheduleDelay;
  }

  @Override
  public int getRetryDelay() {
    return retryDelay;
  }

  public int getPoolThreads() {
    return poolThreads;
  }

  public int getLockErrorMaxRetries() {
    return lockErrorMaxRetries;
  }

  @Override
  public ImmutableList<String> getUrls() {
    return urls;
  }

  public ImmutableList<String> getApis() {
    return apis;
  }

  public int getConnectionTimeout() {
    return connectionTimeout;
  }

  public int getIdleTimeout() {
    return idleTimeout;
  }

  public int getMaxConnectionsPerRoute() {
    return maxConnectionsPerRoute;
  }

  public int getMaxConnections() {
    return maxConnections;
  }

  @Override
  public ImmutableList<String> getAdminUrls() {
    return adminUrls;
  }

  @Override
  public ImmutableList<String> getProjects() {
    return projects;
  }

  @Override
  public ImmutableList<String> getAuthGroupNames() {
    return authGroupNames;
  }

  @Override
  public String getRemoteNameStyle() {
    return remoteNameStyle;
  }

  @Override
  public boolean replicatePermissions() {
    return replicatePermissions;
  }

  public boolean replicateHiddenProjects() {
    return replicateHiddenProjects;
  }

  public boolean useCGitClient() {
    return useCGitClient;
  }

  public int getRefsBatchSize() {
    return refsBatchSize;
  }

  @Override
  public RemoteConfig getRemoteConfig() {
    return remoteConfig;
  }

  @Override
  public int getMaxRetries() {
    return maxRetries;
  }

  public boolean createMissingRepositories() {
    return createMissingRepositories;
  }

  public boolean replicateProjectDeletions() {
    return replicateProjectDeletions;
  }

  private static int getInt(RemoteConfig rc, Config cfg, String name, int defValue) {
    return cfg.getInt("remote", rc.getName(), name, defValue);
  }

  @Override
  public int getSlowLatencyThreshold() {
    return slowLatencyThreshold;
  }

  @Override
  public int getPushBatchSize() {
    return 0;
  }

  @Override
  public boolean replicateNoteDbMetaRefs() {
    return true;
  }

  public boolean enableBatchedRefs() {
    return enableBatchedRefs;
  }
}
