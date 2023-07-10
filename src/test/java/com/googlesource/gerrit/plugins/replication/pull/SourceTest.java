/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.replication.pull;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
            eventDispatcherDynamicItem);
  }

  @Test
  public void shouldIncreaseReplicationStateCountersWhenARefIsScheduled()
      throws URISyntaxException {
    URIish host1 = new URIish("host1");
    String first = "test-ref";
    String second = "test-2-ref";

    when(fetchOne.getURI()).thenReturn(host1);
    when(fetchOne.getRefs()).thenReturn(Set.of(first));

    source.start(workQueue);
    source.schedule(
        project, first, host1, replicationState, ReplicationType.ASYNC, Optional.empty());
    source.schedule(
        project, second, host1, replicationState, ReplicationType.ASYNC, Optional.empty());

    verify(replicationState).increaseFetchTaskCount(project.get(), first);
    verify(replicationState).increaseFetchTaskCount(project.get(), second);
  }

  @Test
  public void shouldNotIncreaseReplicationStateCountersWhenRefIsAlreadyScheduled()
      throws URISyntaxException {
    URIish host1 = new URIish("host1");
    String first = "test-ref";
    String second = "test-2-ref";

    when(fetchOne.getURI()).thenReturn(host1);
    when(fetchOne.getRefs()).thenReturn(Set.of(second));

    source.start(workQueue);
    source.schedule(
        project, first, host1, replicationState, ReplicationType.ASYNC, Optional.empty());
    source.schedule(
        project, second, host1, replicationState, ReplicationType.ASYNC, Optional.empty());

    verify(replicationState).increaseFetchTaskCount(project.get(), first);
    verify(replicationState, never()).increaseFetchTaskCount(project.get(), second);
  }

  @Test
  public void shouldScheduleFetchTaskForNewRef() throws URISyntaxException {
    URIish host1 = new URIish("host1");
    String ref = "test-ref";

    when(fetchOne.getURI()).thenReturn(host1);

    source.start(workQueue);
    source.schedule(project, ref, host1, replicationState, ReplicationType.ASYNC, Optional.empty());

    verify(executorService).schedule(fetchOne, 0L, TimeUnit.SECONDS);
  }

  @Test
  public void shouldReturnAFutureWithNullValueWhenRefNotScheduled()
      throws URISyntaxException, InterruptedException, ExecutionException {
    URIish host1 = new URIish("host1");
    String first = "test-ref";
    String second = "test-2-ref";

    when(fetchOne.getURI()).thenReturn(host1);
    when(fetchOne.getRefs()).thenReturn(Set.of(second));

    source.start(workQueue);
    // primer, to ensure the pending Map is pre-populated.
    source.schedule(
        project, first, host1, replicationState, ReplicationType.ASYNC, Optional.empty());

    Future<?> res =
        source.schedule(
            project, second, host1, replicationState, ReplicationType.ASYNC, Optional.empty());

    assertThat(res.get()).isNull();
    verify(executorService).schedule(fetchOne, 0L, TimeUnit.SECONDS);
  }

  @Test
  public void shouldUpdateStateOfExistingFetchTaskWithNewRef() throws URISyntaxException {
    URIish host1 = new URIish("host1");
    String first = "test-ref";
    String second = "test-2-ref";

    when(fetchOne.getURI()).thenReturn(host1);
    when(fetchOne.getRefs()).thenReturn(Set.of(first));

    source.start(workQueue);
    List.of(first, second)
        .forEach(
            ref ->
                source.schedule(
                    project,
                    ref,
                    host1,
                    replicationState,
                    ReplicationType.ASYNC,
                    Optional.empty()));

    verify(fetchOne).addRef(first);
    verify(fetchOne).addState(first, replicationState);

    verify(fetchOne).addRef(second);
    verify(fetchOne).addState(second, replicationState);
  }
}
