package com.googlesource.gerrit.plugins.replication.pull;

import com.google.gerrit.entities.Project;
import com.google.inject.Singleton;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Singleton
public class QueueInfo {
  //TODO: Concurrent hashmap
  private Map<Project.NameKey, FetchOne> pending;
  private Map<Project.NameKey, FetchOne> inFlight;

  public QueueInfo() {
    this.pending = Collections.emptyMap();
    this.inFlight =  Collections.emptyMap();
  }

  public void addPending(Project.NameKey projectName, FetchOne op) {
    this.pending.put(projectName, op);
  }

  public void addInFlight(Project.NameKey projectName, FetchOne op) {
    this.inFlight.put(projectName, op);
  }

  public FetchOne getInFlight(Project.NameKey projectName) {
    return inFlight.get(projectName);
  }

  public FetchOne getPending(Project.NameKey projectName) {
    return pending.get(projectName);
  }

  public Collection<FetchOne> getAllPending() {
    return pending.values();
  }

  public Collection<FetchOne> getAllInFlight() {
    return inFlight.values();
  }

  public void removePending(Project.NameKey projectName) {
    pending.remove(projectName);
  }

  public void removeInFlight(Project.NameKey projectName) {
    inFlight.remove(projectName);
  }

  public int pendingSize() {
    return this.pending.size();
  }

  public int inFlightSize() {
    return this.inFlight.size();
  }

  public boolean isInFlight(Project.NameKey projectName) {
    return this.inFlight.containsKey(projectName);
  }

  public boolean isPending(Project.NameKey projectName) {
    return this.pending.containsKey(projectName);
  }
}
