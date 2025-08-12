package com.googlesource.gerrit.plugins.replication.pull;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.Project;

import java.util.Map;

public class QueueInfo {
	public final Map<Project.NameKey, FetchOne> pending;
	public final Map<Project.NameKey, FetchOne> inFlight;

	public QueueInfo(
		Map<Project.NameKey, FetchOne> pending, Map<Project.NameKey, FetchOne> inFlight) {
		this.pending = ImmutableMap.copyOf(pending);
		this.inFlight = ImmutableMap.copyOf(inFlight);
	}
}
