package com.googlesource.gerrit.plugins.replication.pull;

public interface Command {
  public void writeStdOutSync(String message);

  public void writeStdErrSync(String message);
}
