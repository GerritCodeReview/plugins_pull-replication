package com.googlesource.gerrit.plugins.replication.pull;

import com.google.common.collect.ImmutableMap;
import com.google.gerrit.entities.Project;
import com.google.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class QueueInfo {
	public final Map<Project.NameKey, FetchOne> pending;
	public final Map<Project.NameKey, FetchOne> inFlight;

	@Inject
	public QueueInfo() {
		this.pending = new ConcurrentHashMap<>();
		this.inFlight = new ConcurrentHashMap<>();
	}
}
