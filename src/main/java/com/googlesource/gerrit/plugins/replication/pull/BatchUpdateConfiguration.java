package com.googlesource.gerrit.plugins.replication.pull;

import org.eclipse.jgit.lib.Config;

/**
 * @author: jianghaozhe
 * @create_date: 2023/3/17 20:40
 */
public class BatchUpdateConfiguration {
  private boolean useAsync;
  private int batchUpdateCorePoolSize;
  private int batchUpdateMaxPoolSize;
  private int batchUpdateQueueSize;
  private int batchUpdateAliveTime;

  public BatchUpdateConfiguration(Config cfg) {
    useAsync = cfg.getBoolean("batchupdate", "useAsync", true);
    batchUpdateCorePoolSize = cfg.getInt("batchupdate", "corePoolSize", 32);
    batchUpdateMaxPoolSize = cfg.getInt("batchupdate", "maxPoolSize", 128);
    batchUpdateQueueSize = cfg.getInt("batchupdate", "queueSize", 512);
    batchUpdateAliveTime = cfg.getInt("batchupdate", "aliveTime", 20_000);
  }
  public boolean useAsync(){
    return useAsync;
  }
  public int getBatchUpdateCorePoolSize() {
    return batchUpdateCorePoolSize;
  }

  public int getBatchUpdateMaxPoolSize() {
    return batchUpdateMaxPoolSize;
  }

  public int getBatchUpdateQueueSize() {
    return batchUpdateQueueSize;
  }

  public int getBatchUpdateAliveTime() {
    return batchUpdateAliveTime;
  }
}
