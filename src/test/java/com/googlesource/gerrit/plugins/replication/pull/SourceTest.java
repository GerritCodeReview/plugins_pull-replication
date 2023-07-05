// Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupIncludeCache;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SourceTest {

  @Mock private Injector injector;
  @Mock private Injector childInjector;
  @Mock private Binding<FetchFactory> binding;
  @Mock private FetchOne fetchOne;
  @Mock private PerThreadRequestScope.Scoper scoper;
  @Mock private SourceConfiguration cfg;
  @Mock private RemoteConfig remoteCfg;
  @Mock private PluginUser pluginUser;
  @Mock private GitRepositoryManager gitRepositoryManager;
  @Mock private PermissionBackend permissionBackend;
  @Mock private Provider<CurrentUser> currentUserProvider;
  @Mock private ProjectCache projectCache;
  @Mock private GroupBackend groupBackend;
  @Mock private ReplicationStateListeners stateLog;
  @Mock private GroupIncludeCache groupIncludeCache;
  @Mock private DynamicItem<EventDispatcher> eventDispatcherDynamicItem;
  @Mock private ReplicationQueueMetrics replicationQueueMetrics;
  @Mock private EventDispatcher eventDispatcher;

  @Mock private ReplicationState replicationState;

  @Mock private Callable<Boolean> scoperCallable;
  @Mock private WorkQueue workQueue;
  @Mock private ScheduledExecutorService executorService;

  private final Project.NameKey project = Project.nameKey("test-project");
  private Source source;

  @Before
  public void setup() throws Exception {
    when(cfg.getAuthGroupNames()).thenReturn(ImmutableList.of());
    when(injector.createChildInjector(Matchers.<Module>anyVararg())).thenReturn(childInjector);
    when(childInjector.getBinding(FetchFactory.class)).thenReturn(binding);
    when(childInjector.getInstance(FetchOne.Factory.class))
        .thenReturn(
            new FetchOne.Factory() {
              @Override
              public FetchOne create(
                  Project.NameKey d,
                  URIish u,
                  Optional<PullReplicationApiRequestMetrics> apiRequestMetrics) {
                return fetchOne;
              }
            });
    when(childInjector.getInstance(PerThreadRequestScope.Scoper.class)).thenReturn(scoper);

    when(cfg.getRemoteConfig()).thenReturn(remoteCfg);
    when(cfg.getDelay()).thenReturn(1);
    when(remoteCfg.getName()).thenReturn("remote");
    when(workQueue.createQueue(anyInt(), anyString())).thenReturn(executorService);

    when(fetchOne.getProjectNameKey()).thenReturn(project);

    when(scoper.scope((Callable<Boolean>) any())).thenReturn(scoperCallable);
    when(scoperCallable.call()).thenReturn(true);
    when(cfg.replicatePermissions()).thenReturn(true);
    when(eventDispatcherDynamicItem.get()).thenReturn(eventDispatcher);

    source =
        new Source(
            injector,
            cfg,
            pluginUser,
            gitRepositoryManager,
            permissionBackend,
            currentUserProvider,
            projectCache,
            groupBackend,
            stateLog,
            groupIncludeCache,
            eventDispatcherDynamicItem,
            replicationQueueMetrics);
  }

  @Test
  public void shouldReturnEmptyFutureWhenListOfRefsIsEmpty()
      throws URISyntaxException, ExecutionException, InterruptedException {
    URIish host = new URIish("host");

    source.start(workQueue);
    Future<?> res =
        source.schedule(
            project, List.of(), host, replicationState, ReplicationType.ASYNC, Optional.empty());

    verify(replicationState, never()).increaseFetchTaskCount(eq(project.get()), anySet());
    verify(replicationQueueMetrics).incrementTaskNotScheduled(any());
    assertThat(res.get()).isNull();
  }

  @Test
  public void shouldReturnEmptyFutureWhenAllRefsInListAreExcludedFromReplication()
      throws Exception {
    URIish host = new URIish("host");
    String testRef = "test-ref";

    when(scoperCallable.call()).thenReturn(false);

    source.start(workQueue);
    Future<?> res =
        source.schedule(
            project,
            List.of(testRef),
            host,
            replicationState,
            ReplicationType.ASYNC,
            Optional.empty());

    verify(replicationState, never()).increaseFetchTaskCount(eq(project.get()), anySet());
    verify(replicationQueueMetrics).incrementTaskNotScheduled(any());
    assertThat(res.get()).isNull();
  }

  @Test
  public void shouldScheduleOneTaskForAllRefsInBatch() throws Exception {
    URIish host = new URIish("host");
    String ref1 = "ref-1";
    String ref2 = "ref-2";

    when(fetchOne.getURI()).thenReturn(host);

    source.start(workQueue);
    source.schedule(
        project,
        List.of(ref1, ref2),
        host,
        replicationState,
        ReplicationType.ASYNC,
        Optional.empty());

    verify(replicationQueueMetrics).incrementTaskScheduled(any());
    verify(replicationState).increaseFetchTaskCount(project.get(), Set.of(ref1, ref2));
    verify(fetchOne).addState(ref1, replicationState);
    verify(fetchOne).addState(ref2, replicationState);
    verify(executorService)
        .schedule(
            replicationQueueMetrics.runWithMetrics(any(), eq(fetchOne)), 1L, TimeUnit.SECONDS);
  }

  @Test
  public void shouldAddMissingRefsInPendingTask() throws Exception {
    URIish host = new URIish("host");
    String ref1 = "ref-1";
    String ref2 = "ref-2";

    when(fetchOne.getURI()).thenReturn(host);
    when(fetchOne.getRefs()).thenReturn(Set.of(ref1));

    source.start(workQueue);
    source.schedule(
        project, List.of(ref1), host, replicationState, ReplicationType.ASYNC, Optional.empty());
    source.schedule(
        project,
        List.of(ref1, ref2),
        host,
        replicationState,
        ReplicationType.ASYNC,
        Optional.empty());

    verify(replicationQueueMetrics).incrementTaskScheduled(any());
    verify(replicationQueueMetrics).incrementTaskMerged(any());
    verify(replicationState).increaseFetchTaskCount(project.get(), Set.of(ref1));
    verify(replicationState).increaseFetchTaskCount(project.get(), Set.of(ref2));
    verify(fetchOne).addState(ref1, replicationState);
    verify(fetchOne).addState(ref2, replicationState);
    verify(executorService)
        .schedule(
            replicationQueueMetrics.runWithMetrics(any(), eq(fetchOne)), 1L, TimeUnit.SECONDS);
  }

  @Test
  public void shouldNotScheduleTaskIfAllRefsInBatchAreAlreadyIncludedInPendingTask()
      throws Exception {
    URIish host = new URIish("host");
    String ref1 = "ref-1";
    String ref2 = "ref-2";
    String ref3 = "ref-3";

    when(fetchOne.getURI()).thenReturn(host);
    when(fetchOne.getRefs()).thenReturn(Set.of(ref1, ref2, ref3));

    source.start(workQueue);
    source.schedule(
        project, List.of(ref3), host, replicationState, ReplicationType.ASYNC, Optional.empty());
    source.schedule(
        project,
        List.of(ref1, ref2),
        host,
        replicationState,
        ReplicationType.ASYNC,
        Optional.empty());

    verify(replicationQueueMetrics).incrementTaskScheduled(any());
    verify(replicationQueueMetrics).incrementTaskNotScheduled(any());
    verify(replicationState).increaseFetchTaskCount(project.get(), Set.of(ref3));
    verify(replicationState, never()).increaseFetchTaskCount(project.get(), Set.of(ref1, ref2));
    verify(fetchOne).addState(ref3, replicationState);
    verify(fetchOne, never()).addState(ref1, replicationState);
    verify(fetchOne, never()).addState(ref2, replicationState);
    verify(executorService)
        .schedule(
            replicationQueueMetrics.runWithMetrics(any(), eq(fetchOne)), 1L, TimeUnit.SECONDS);
  }
}
