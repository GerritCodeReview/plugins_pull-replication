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
import static org.mockito.Mockito.*;

import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.util.IdGenerator;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.fetch.Fetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import com.googlesource.gerrit.plugins.replication.pull.fetch.InexistentRefTransportException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class FetchOneTest {
  private final String TEST_PROJECT_NAME = "FetchOneTest";
  private final Project.NameKey PROJECT_NAME = Project.NameKey.parse(TEST_PROJECT_NAME);
  private final String TEST_REF = "refs/heads/refForReplicationTask";
  private final String URI_PATTERN = "http://test.com/" + TEST_PROJECT_NAME + ".git";
  private final TestMetricMaker testMetricMaker = new TestMetricMaker();

  @Mock private GitRepositoryManager grm;
  @Mock private Repository repository;
  @Mock private Source source;
  @Mock private SourceConfiguration sourceConfiguration;
  @Mock private PerThreadRequestScope.Scoper scoper;
  @Mock private IdGenerator idGenerator;
  @Mock private ReplicationStateListeners replicationStateListeners;
  @Mock private FetchFactory fetchFactory;
  @Mock private PullReplicationApiRequestMetrics pullReplicationApiRequestMetrics;
  @Mock private RemoteConfig remoteConfig;
  @Mock private DynamicItem<ReplicationFetchFilter> replicationFilter;

  private URIish urIish;
  private FetchOne objectUnderTest;

  @Before
  public void setup() throws Exception {
    testMetricMaker.reset();
    FetchReplicationMetrics fetchReplicationMetrics =
        new FetchReplicationMetrics("pull-replication", testMetricMaker);
    urIish = new URIish(URI_PATTERN);

    grm = mock(GitRepositoryManager.class);
    source = mock(Source.class);
    sourceConfiguration = mock(SourceConfiguration.class);
    scoper = mock(PerThreadRequestScope.Scoper.class);
    idGenerator = mock(IdGenerator.class);
    replicationStateListeners = mock(ReplicationStateListeners.class);
    fetchFactory = mock(FetchFactory.class);
    pullReplicationApiRequestMetrics = mock(PullReplicationApiRequestMetrics.class);
    remoteConfig = mock(RemoteConfig.class);
    replicationFilter = mock(DynamicItem.class);

    when(sourceConfiguration.getRemoteConfig()).thenReturn(remoteConfig);
    when(idGenerator.next()).thenReturn(1);
    int maxLockRetries = 1;
    when(source.getLockErrorMaxRetries()).thenReturn(maxLockRetries);

    objectUnderTest =
        new FetchOne(
            grm,
            source,
            sourceConfiguration,
            scoper,
            idGenerator,
            replicationStateListeners,
            fetchReplicationMetrics,
            fetchFactory,
            PROJECT_NAME,
            urIish,
            Optional.of(pullReplicationApiRequestMetrics));
  }

  @Test
  public void shouldIncludeTheTaskIndexInItsStringRepresentation() {
    String expected = "[" + objectUnderTest.getTaskIdHex() + "] fetch " + URI_PATTERN;

    assertThat(objectUnderTest.toString()).isEqualTo(expected);
  }

  @Test
  public void shouldIncludeTheRetryCountInItsStringRepresentationWhenATaskIsRetried() {
    objectUnderTest.setToRetry();
    String expected = "(retry 1) [" + objectUnderTest.getTaskIdHex() + "] fetch " + URI_PATTERN;

    assertThat(objectUnderTest.toString()).isEqualTo(expected);
  }

  @Test
  public void shouldAddARefToTheDeltaIfItsNotTheAllRefs() {
    Set<String> refs = Set.of(TEST_REF);
    objectUnderTest.addRefs(refs);

    assertThat(refs).isEqualTo(objectUnderTest.getRefs());
  }

  @Test
  public void shouldIgnoreEveryRefButTheAllRefsWhenAddingARef() {
    objectUnderTest.addRefs(Set.of(TEST_REF, FetchOne.ALL_REFS));

    assertThat(Set.of(FetchOne.ALL_REFS)).isEqualTo(objectUnderTest.getRefs());
  }

  @Test
  public void shouldReturnExistingStates() {
    assertThat(createTestStates(TEST_REF, 1)).isEqualTo(objectUnderTest.getStates().get(TEST_REF));
  }

  @Test
  public void shouldKeepMultipleStatesInInsertionOrderForARef() {
    List<ReplicationState> states = createTestStates(TEST_REF, 2);

    List<ReplicationState> actualStates = objectUnderTest.getStates().get(TEST_REF);

    assertThat(actualStates).containsExactlyElementsIn(states).inOrder();
  }

  @Test
  public void shouldReturnStatesInAnArray() {
    List<ReplicationState> states = createTestStates(TEST_REF, 2);

    ReplicationState[] actualStates = objectUnderTest.getStatesAsArray();

    assertThat(actualStates).asList().containsExactly(states.toArray());
  }

  @Test
  public void shouldClearTheStates() {
    createTestStates(TEST_REF, 2);

    objectUnderTest.removeStates();

    assertThat(objectUnderTest.getStates().isEmpty()).isTrue();
  }

  @Test
  public void shouldNotifyTheSourceWhenTaskIsCancelled() {
    objectUnderTest.cancel();

    verify(source).fetchWasCanceled(objectUnderTest);
    assertThat(objectUnderTest.wasCanceled()).isTrue();
  }

  @Test
  public void shouldRunAReplicationTaskForAllRefsIfDeltaIsEmpty() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(FetchOne.ALL_REFS, 1);
    setupFetchFactoryMock(Collections.emptyList());

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            FetchOne.ALL_REFS,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NO_CHANGE);
  }

  @Test
  public void shouldRescheduleReplicationTaskAndExitIfTheQueueLockCantBeObtained()
      throws Exception {
    setupMocks(false);

    objectUnderTest.run();

    verify(source, never()).notifyFinished(objectUnderTest);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.COLLISION);
  }

  @Test
  public void shouldNotRescheduleAnAlreadyCancelledReplicationTaskIfTheQueueLockCantBeObtained()
      throws Exception {
    setupMocks(false);
    objectUnderTest.canceledByReplication();

    objectUnderTest.run();

    verify(source, never()).notifyFinished(objectUnderTest);
    verify(source, never()).reschedule(objectUnderTest, Source.RetryReason.COLLISION);
  }

  @Test
  public void shouldRunTheFetchOperationEvenWhenStateIsEmpty() throws Exception {
    setupMocks(true);
    Fetch mockFetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefSpecName(TEST_REF)
                    .withRemoteName("testRemote")
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    verify(mockFetch).fetch(List.of(new RefSpec(TEST_REF)));
  }

  @Test
  public void
      shouldSetTheReplicationFetchResultStatusToNotAttemptedAndThenFailedForARefForWhichThereIsNoState()
          throws Exception {
    setupMocks(true);
    String someRef = "refs/heads/someRef";
    List<ReplicationState> states = createTestStates(someRef, 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            someRef,
            urIish,
            ReplicationState.RefFetchResult.NOT_ATTEMPTED,
            null);
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, someRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test(expected = InternalError.class)
  public void shouldThrowAnExceptionForUnrecoverableErrors() {
    setupFailingScopeMock();

    objectUnderTest.run();
  }

  @Test
  public void shouldFilterOutRefsFromFetchReplicationDelta() throws Exception {
    setupMocks(true);
    String filteredRef = "refs/heads/filteredRef";
    Set<String> refSpecs = Set.of(TEST_REF, filteredRef);
    List<ReplicationState> states =
        Stream.concat(
                createTestStates(TEST_REF, 1).stream(), createTestStates(filteredRef, 1).stream())
            .collect(Collectors.toList());
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = mock(ReplicationFetchFilter.class);
    when(replicationFilter.get()).thenReturn(mockFilter);
    when(mockFilter.filter(TEST_PROJECT_NAME, refSpecs)).thenReturn(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, filteredRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test
  public void shouldMarkTheReplicationStatusAsSucceededOnSuccessfulReplicationOfARef()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();
    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldMarkAllTheStatesOfARefAsReplicatedSuccessfullyOnASuccessfulReplication()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 2);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldUpdateTheStateOfAllRefsOnSuccessfulReplication() throws Exception {
    setupMocks(true);
    List<ReplicationState> states =
        Stream.concat(
                createTestStates(TEST_REF, 1).stream(),
                createTestStates(FetchOne.ALL_REFS, 1).stream())
            .collect(Collectors.toList());
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            FetchOne.ALL_REFS,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldMarkReplicationStateAsRejectedWhenTheObjectIsNotInRepository()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 2);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.REJECTED_MISSING_OBJECT)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_MISSING_OBJECT);
  }

  @Test
  public void shouldMarkReplicationStateAsRejectedWhenFailedForUnknownReason() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.REJECTED_OTHER_REASON)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_OTHER_REASON);
  }

  @Test
  public void shouldMarkReplicationStateOfAllRefsAsRejectedForAnyFailedTask() throws Exception {
    setupMocks(true);
    String failingRef = "refs/heads/failingRef";
    String forcedRef = "refs/heads/forcedRef";
    List<ReplicationState> states =
        Stream.of(
                createTestStates(TEST_REF, 1),
                createTestStates(failingRef, 1),
                createTestStates(forcedRef, 1),
                createTestStates(FetchOne.ALL_REFS, 1))
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.NEW)
                .build(),
            new FetchFactoryEntry.Builder()
                .withRefNames(failingRef)
                .withResult(RefUpdate.Result.REJECTED_MISSING_OBJECT)
                .build(),
            new FetchFactoryEntry.Builder()
                .withRefNames(forcedRef)
                .withResult(RefUpdate.Result.FORCED)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF, failingRef, forcedRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            failingRef,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_MISSING_OBJECT);
    verify(states.get(2))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            forcedRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FORCED);
    verify(states.get(3))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            FetchOne.ALL_REFS,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.FORCED);
  }

  @Test
  public void shouldRetryOnLockingFailures() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.TRANSPORT_ERROR);
  }

  @Test
  public void shouldNotRetryWhenMaxLockRetriesLimitIsReached() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    Stream.of(1, 1).forEach(e -> objectUnderTest.run());

    verify(source, times(2)).notifyFinished(objectUnderTest);
    verify(states.get(0), times(2))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.TRANSPORT_ERROR);
  }

  @Test
  public void shouldNotRetryOnLockingFailuresIfTheTaskWasCancelledWhileRunning() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));
    objectUnderTest.setCanceledWhileRunning();

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source, never()).reschedule(any(), any());
  }

  @Test
  public void shouldNotRetryForUnexpectedIOErrors() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.IO_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source, never()).reschedule(any(), any());
  }

  @Test
  public void shouldTreatInexistentRefsAsFailures() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList()))
        .thenThrow(new InexistentRefTransportException(TEST_REF, new Throwable("boom")));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test
  public void shouldRemoveAnInexistentRefFromTheDeltaAndCarryOn() throws Exception {
    setupMocks(true);
    String inexistentRef = "refs/heads/inexistentRef";
    List<ReplicationState> states =
        Stream.of(createTestStates(inexistentRef, 1), createTestStates(TEST_REF, 1))
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(inexistentRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build(),
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList()))
        .thenThrow(new InexistentRefTransportException(TEST_REF, new Throwable("boom")))
        .thenReturn(List.of(new RefUpdateState(TEST_REF, RefUpdate.Result.NEW)));
    objectUnderTest.addRefs(Set.of(inexistentRef, TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            inexistentRef,
            urIish,
            ReplicationState.RefFetchResult.NOT_ATTEMPTED,
            null);
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, inexistentRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(states.get(1))
        .notifyRefReplicated(
            TEST_PROJECT_NAME,
            TEST_REF,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldRescheduleCertainTypesOfTransportException() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(Set.of(TEST_REF));

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.TRANSPORT_ERROR);
  }

  @Test
  public void shouldNotMarkReplicationTaskAsFailedIfItIsBeingRetried() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(Set.of(TEST_REF));
    objectUnderTest.setToRetry();

    objectUnderTest.run();

    assertFinishedWithNonEmptyStateAndNoFailures();
    verify(states.get(0), never())
        .notifyRefReplicated(
            TEST_PROJECT_NAME, TEST_REF, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test
  public void shouldNotRecordReplicationLatencyMetricIfAllRefsAreExcluded() throws Exception {
    setupMocks(true);
    String filteredRef = "refs/heads/filteredRef";
    Set<String> refSpecs = Set.of(TEST_REF, filteredRef);
    createTestStates(TEST_REF, 1);
    createTestStates(filteredRef, 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = mock(ReplicationFetchFilter.class);
    when(replicationFilter.get()).thenReturn(mockFilter);
    when(mockFilter.filter(TEST_PROJECT_NAME, refSpecs)).thenReturn(Collections.emptySet());

    objectUnderTest.run();

    verify(pullReplicationApiRequestMetrics, never()).stop(any());
    assertThat(testMetricMaker.getTimer("replication_latency")).isEqualTo(0);
  }

  @Test
  public void shouldRecordReplicationLatencyMetricWhenAtLeastOneRefWasReplicated()
      throws Exception {
    setupMocks(true);
    String filteredRef = "refs/heads/filteredRef";
    Set<String> refSpecs = Set.of(TEST_REF, filteredRef);
    createTestStates(TEST_REF, 1);
    createTestStates(filteredRef, 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = mock(ReplicationFetchFilter.class);
    when(replicationFilter.get()).thenReturn(mockFilter);
    when(mockFilter.filter(TEST_PROJECT_NAME, refSpecs)).thenReturn(Set.of(TEST_REF));

    objectUnderTest.run();

    verify(pullReplicationApiRequestMetrics).stop(any());
    assertThat(testMetricMaker.getTimer("replication_latency")).isGreaterThan(0);
  }

  private void setupRequestScopeMock() {
    when(scoper.scope(any()))
        .thenAnswer(
            (Answer<Callable<Object>>)
                invocation -> {
                  Callable<Object> originalCall = (Callable<Object>) invocation.getArguments()[0];
                  return originalCall;
                });
  }

  private void setupFailingScopeMock() {
    when(scoper.scope(any())).thenThrow(new InternalError());
  }

  private void setupMocks(boolean runawayAllowed) throws Exception {
    setupRequestScopeMock();
    setupSourceMock(runawayAllowed);
    setupGitRepoManagerMock();
  }

  private void setupSourceMock(boolean allowed) {
    when(source.requestRunway(any())).thenReturn(allowed);
  }

  private void setupGitRepoManagerMock() throws IOException {
    when(grm.openRepository(PROJECT_NAME)).thenReturn(repository);
  }

  private List<ReplicationState> createTestStates(String ref, int numberOfStates) {
    List<ReplicationState> states =
        IntStream.rangeClosed(1, numberOfStates)
            .mapToObj(i -> Mockito.mock(ReplicationState.class))
            .collect(Collectors.toList());
    states.forEach(rs -> objectUnderTest.addState(ref, rs));

    return states;
  }

  private void setupRemoteConfigMock(List<RefSpec> refSpecs) {
    when(remoteConfig.getFetchRefSpecs()).thenReturn(refSpecs);
    when(remoteConfig.getName()).thenReturn(PROJECT_NAME.get());
  }

  private Fetch setupFetchFactoryMock(List<FetchFactoryEntry> fetchFactoryEntries)
      throws Exception {
    return setupFetchFactoryMock(fetchFactoryEntries, Optional.empty());
  }

  private Fetch setupFetchFactoryMock(
      List<FetchFactoryEntry> fetchFactoryEntries, Optional<List<String>> filteredRefs)
      throws Exception {
    List<RefSpec> refSpecs =
        fetchFactoryEntries.stream()
            .map(ffe -> new RefSpec(ffe.getRefSpecName()))
            .collect(Collectors.toList());
    List<RefUpdateState> refUpdateStates =
        fetchFactoryEntries.stream()
            .map(ffe -> new RefUpdateState(ffe.getRemoteName(), ffe.getResult()))
            .collect(Collectors.toList());
    List<RefSpec> filteredRefSpecs =
        filteredRefs
            .map(refList -> refList.stream().map(RefSpec::new).collect(Collectors.toList()))
            .orElse(refSpecs);

    setupRemoteConfigMock(refSpecs);
    Fetch mockFetch = mock(Fetch.class);
    when(fetchFactory.create(objectUnderTest.getTaskIdHex(), urIish, repository))
        .thenReturn(mockFetch);
    when(mockFetch.fetch(argThat(rs -> rs.containsAll(filteredRefSpecs))))
        .thenReturn(refUpdateStates);
    return mockFetch;
  }

  private void assertFinishedWithEmptyStateAndNoFailures() {
    assertFinishedWithStateAndFailures(true, true);
  }

  private void assertFinishedWithNonEmptyStateAndNoFailures() {
    assertFinishedWithStateAndFailures(false, true);
  }

  private void assertFinishedWithNonEmptyStateAndFailures() {
    assertFinishedWithStateAndFailures(false, false);
  }

  private void assertFinishedWithStateAndFailures(boolean emptyState, boolean noFailures) {
    assertThat(objectUnderTest.getStates().isEmpty()).isEqualTo(emptyState);
    verify(source).notifyFinished(objectUnderTest);
    assertThat(objectUnderTest.getFetchFailures().isEmpty()).isEqualTo(noFailures);
  }
}

class FetchFactoryEntry {
  private String refSpecName;
  private String remoteName;
  private RefUpdate.Result result;

  public String getRefSpecName() {
    return refSpecName;
  }

  public String getRemoteName() {
    return remoteName;
  }

  public RefUpdate.Result getResult() {
    return result;
  }

  private FetchFactoryEntry(Builder builder) {
    this.refSpecName = builder.refSpecName;
    this.remoteName = builder.remoteName;
    this.result = builder.result;
  }

  public static class Builder {
    private String refSpecName;
    private String remoteName;
    private RefUpdate.Result result;

    public Builder withRefSpecName(String refSpecName) {
      this.refSpecName = refSpecName;
      return this;
    }

    public Builder withRemoteName(String remoteName) {
      this.remoteName = remoteName;
      return this;
    }

    public Builder withResult(RefUpdate.Result result) {
      this.result = result;
      return this;
    }

    public Builder refSpecNameWithDefaults(String refSpecName) {
      this.refSpecName = refSpecName;
      this.remoteName = refSpecName;
      this.result = RefUpdate.Result.NEW;
      return this;
    }

    public Builder withRefNames(String refSpecName) {
      this.refSpecName = refSpecName;
      this.remoteName = refSpecName;
      return this;
    }

    public FetchFactoryEntry build() {
      return new FetchFactoryEntry(this);
    }
  }
}
