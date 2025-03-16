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
import static com.googlesource.gerrit.plugins.replication.pull.FetchOne.refsToDelete;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.acceptance.TestMetricMaker;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.PerThreadRequestScope;
import com.google.gerrit.server.util.IdGenerator;
import com.googlesource.gerrit.plugins.replication.pull.api.DeleteRefCommand;
import com.googlesource.gerrit.plugins.replication.pull.api.PullReplicationApiRequestMetrics;
import com.googlesource.gerrit.plugins.replication.pull.fetch.Fetch;
import com.googlesource.gerrit.plugins.replication.pull.fetch.FetchFactory;
import com.googlesource.gerrit.plugins.replication.pull.fetch.InexistentRefTransportException;
import com.googlesource.gerrit.plugins.replication.pull.fetch.RefUpdateState;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.Transport;
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
  private final String TEST_DELETE_REF = ":" + TEST_REF;
  private final FetchRefSpec TEST_REF_SPEC = FetchRefSpec.fromRef(TEST_REF);
  private final String URI_PATTERN = "http://test.com/" + TEST_PROJECT_NAME + ".git";
  private final TestMetricMaker testMetricMaker = new TestMetricMaker();

  private final RefSpec ALL_REFS_SPEC = FetchRefSpec.fromRef("refs/*:refs/*");

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
  @Mock private FetchRefsDatabase fetchRefsDatabase;
  @Mock private DeleteRefCommand deleteRefCommand;

  @Mock private Transport transport;

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
    fetchRefsDatabase = mock(FetchRefsDatabase.class);
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
            fetchRefsDatabase,
            deleteRefCommand,
            PROJECT_NAME,
            urIish,
            Optional.of(pullReplicationApiRequestMetrics));
  }

  @Test
  public void shouldIncludeTheTaskIndexInItsStringRepresentation() {
    objectUnderTest.addRefs(refSpecsSetOf("refs/heads/foo", "refs/heads/bar"));
    String expected =
        "["
            + objectUnderTest.getTaskIdHex()
            + "] fetch "
            + URI_PATTERN
            + " [refs/heads/bar, refs/heads/foo]";

    assertThat(objectUnderTest.toString()).isEqualTo(expected);
  }

  @Test
  public void shouldIncludeTheRetryCountInItsStringRepresentationWhenATaskIsRetried() {
    objectUnderTest.addRefs(refSpecsSetOf("refs/heads/bar", "refs/heads/foo"));
    objectUnderTest.setToRetry();
    String expected =
        "(retry 1) ["
            + objectUnderTest.getTaskIdHex()
            + "] fetch "
            + URI_PATTERN
            + " [refs/heads/bar, refs/heads/foo]";

    assertThat(objectUnderTest.toString()).isEqualTo(expected);
  }

  @Test
  public void shouldAddARefToTheDeltaIfItsNotTheAllRefs() {
    Set<FetchRefSpec> refs = Set.of(TEST_REF_SPEC);
    objectUnderTest.addRefs(refs);

    assertThat(refs).isEqualTo(objectUnderTest.getRefSpecs());
  }

  @Test
  public void shouldIgnoreEveryRefButTheAllRefsWhenAddingARef() {
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF, FetchOne.ALL_REFS));

    assertThat(Set.of(FetchOne.ALL_REFS)).isEqualTo(objectUnderTest.getRefs());
  }

  @Test
  public void shouldDeleteRefWhenAddingDeleteRefSpec() throws IOException {
    setupRemoteConfigMock(List.of(ALL_REFS_SPEC));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_DELETE_REF));

    assertThat(objectUnderTest.getFetchRefSpecs())
        .isEqualTo(List.of(FetchRefSpec.fromRef(TEST_DELETE_REF)));
  }

  @Test
  public void shouldAddRefWhenAddingRefSpecToDeleteRefSpec() throws IOException {
    setupRemoteConfigMock(List.of(ALL_REFS_SPEC));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_DELETE_REF));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

    assertThat(objectUnderTest.getFetchRefSpecs())
        .isEqualTo(List.of(FetchRefSpec.fromRef(TEST_REF + ":" + TEST_REF)));
  }

  @Test
  public void shouldReturnExistingStates() {
    assertThat(createTestStates(TEST_REF_SPEC, 1))
        .isEqualTo(objectUnderTest.getStates().get(TEST_REF_SPEC));
  }

  @Test
  public void shouldKeepMultipleStatesInInsertionOrderForARef() {
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 2);

    List<ReplicationState> actualStates = objectUnderTest.getStates().get(TEST_REF_SPEC);

    assertThat(actualStates).containsExactlyElementsIn(states).inOrder();
  }

  @Test
  public void shouldReturnStatesInAnArray() {
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 2);

    ReplicationState[] actualStates = objectUnderTest.getStatesAsArray();

    assertThat(actualStates).asList().containsExactly(states.toArray());
  }

  @Test
  public void shouldClearTheStates() {
    createTestStates(TEST_REF_SPEC, 2);

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
    List<ReplicationState> states = createTestStates(FetchRefSpec.fromRef(FetchOne.ALL_REFS), 1);
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
    objectUnderTest.addRefs(Set.of(TEST_REF_SPEC));

    objectUnderTest.run();

    verify(mockFetch).fetch(List.of(TEST_REF_SPEC));
  }

  @Test
  public void
      shouldSetTheReplicationFetchResultStatusToNotAttemptedAndThenFailedForARefForWhichThereIsNoState()
          throws Exception {
    setupMocks(true);
    String someRef = "refs/heads/someRef";
    List<ReplicationState> states = createTestStates(FetchRefSpec.fromRef(someRef), 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(Set.of(TEST_REF_SPEC));

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
    FetchRefSpec filteredRef = FetchRefSpec.fromRef("refs/heads/filteredRef");
    Set<FetchRefSpec> refSpecs = Set.of(TEST_REF_SPEC, filteredRef);
    Set<String> refs = Set.of(TEST_REF, filteredRef.refName());
    List<ReplicationState> states =
        Stream.concat(
                createTestStates(TEST_REF_SPEC, 1).stream(),
                createTestStates(filteredRef, 1).stream())
            .collect(Collectors.toList());
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = (projectName, fetchRefs) -> Set.of(TEST_REF);
    when(replicationFilter.get()).thenReturn(mockFilter);

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
            TEST_PROJECT_NAME,
            filteredRef.refName(),
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            null);
  }

  @Test
  public void fetchWithoutDelta_shouldPassNewRefsToFilter() throws Exception {
    setupMocks(true);
    String REMOTE_REF = "refs/heads/remote";
    Set<String> remoteRefs = Set.of(REMOTE_REF);
    Map<String, Ref> localRefsMap = Map.of();
    Map<String, Ref> remoteRefsMap = Map.of(REMOTE_REF, mock(Ref.class));
    setupRemoteConfigMock(List.of(ALL_REFS_SPEC));
    setupFetchRefsDatabaseMock(localRefsMap, remoteRefsMap);
    ReplicationFetchFilter mockFilter = setupReplicationFilterMock(remoteRefs);

    objectUnderTest.run();

    verify(mockFilter).filter(TEST_PROJECT_NAME, remoteRefs);
  }

  @Test
  public void fetchWithoutDelta_shouldPassUpdatedRefsToFilter() throws Exception {
    setupMocks(true);
    String REF = "refs/heads/someRef";
    Set<String> remoteRefs = Set.of(REF);
    Map<String, Ref> localRefsMap =
        Map.of(REF, mockRef("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));
    Map<String, Ref> remoteRefsMap =
        Map.of(REF, mockRef("badc0feebadc0feebadc0feebadc0feebadc0fee"));
    setupRemoteConfigMock(List.of(ALL_REFS_SPEC));
    setupFetchRefsDatabaseMock(localRefsMap, remoteRefsMap);
    ReplicationFetchFilter mockFilter = setupReplicationFilterMock(remoteRefs);

    objectUnderTest.run();

    verify(mockFilter).filter(TEST_PROJECT_NAME, remoteRefs);
  }

  @Test
  public void fetchWithoutDelta_shouldNotPassUpToDateRefsToFilter() throws Exception {
    setupMocks(true);
    String REF = "refs/heads/someRef";
    Set<String> remoteRefs = Set.of(REF);
    Ref refValue = mockRef("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
    Map<String, Ref> localRefsMap = Map.of(REF, refValue);
    Map<String, Ref> remoteRefsMap = Map.of(REF, refValue);
    setupRemoteConfigMock(List.of(ALL_REFS_SPEC));
    setupFetchRefsDatabaseMock(localRefsMap, remoteRefsMap);
    ReplicationFetchFilter mockFilter = setupReplicationFilterMock(remoteRefs);

    objectUnderTest.run();

    verify(mockFilter).filter(TEST_PROJECT_NAME, Set.of());
  }

  @Test
  public void fetchWithoutDelta_shouldNotPassRefsNonMatchingConfigToFilter() throws Exception {
    setupMocks(true);
    String REF = "refs/non-dev/someRef";
    Set<String> remoteRefs = Set.of(REF);
    RefSpec DEV_REFS_SPEC = FetchRefSpec.fromRef("refs/dev/*:refs/dev/*");

    setupRemoteConfigMock(List.of(DEV_REFS_SPEC));
    setupFetchRefsDatabaseMock(Map.of(), Map.of(REF, mock(Ref.class)));
    ReplicationFetchFilter mockFilter = setupReplicationFilterMock(remoteRefs);

    objectUnderTest.run();
  }

  @Test
  public void shouldMarkTheReplicationStatusAsSucceededOnSuccessfulReplicationOfARef()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 2);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
                createTestStates(TEST_REF_SPEC, 1).stream(),
                createTestStates(FetchRefSpec.fromRef(FetchOne.ALL_REFS), 1).stream())
            .collect(Collectors.toList());
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 2);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.REJECTED_MISSING_OBJECT)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.REJECTED_OTHER_REASON)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
                createTestStates(TEST_REF_SPEC, 1),
                createTestStates(FetchRefSpec.fromRef(failingRef), 1),
                createTestStates(FetchRefSpec.fromRef(forcedRef), 1),
                createTestStates(FetchRefSpec.fromRef(FetchOne.ALL_REFS), 1))
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
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF, failingRef, forcedRef));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));
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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    setupFetchFactoryMock(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(TEST_REF)
                .withResult(RefUpdate.Result.IO_FAILURE)
                .build()));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList()))
        .thenThrow(new InexistentRefTransportException(TEST_REF, new Throwable("boom")));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
        Stream.of(
                createTestStates(FetchRefSpec.fromRef(inexistentRef), 1),
                createTestStates(TEST_REF_SPEC, 1))
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
    objectUnderTest.addRefs(refSpecsSetOf(inexistentRef, TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));

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
    List<ReplicationState> states = createTestStates(TEST_REF_SPEC, 1);
    Fetch fetch =
        setupFetchFactoryMock(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(TEST_REF)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(refSpecsSetOf(TEST_REF));
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
    Set<FetchRefSpec> refSpecs = refSpecsSetOf(TEST_REF, filteredRef);
    createTestStates(TEST_REF_SPEC, 1);
    createTestStates(FetchRefSpec.fromRef(filteredRef), 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    setupReplicationFilterMock(Collections.emptySet());

    objectUnderTest.run();

    verify(pullReplicationApiRequestMetrics, never()).stop(any());
    assertThat(testMetricMaker.getTimer("replication_latency")).isEqualTo(0);
  }

  @Test
  public void shouldRecordReplicationLatencyMetricWhenAtLeastOneRefWasReplicated()
      throws Exception {
    setupMocks(true);
    String filteredRef = "refs/heads/filteredRef";
    Set<FetchRefSpec> refSpecs = refSpecsSetOf(TEST_REF, filteredRef);
    Set<String> refs = Set.of(TEST_REF, filteredRef);
    createTestStates(TEST_REF_SPEC, 1);
    createTestStates(FetchRefSpec.fromRef(filteredRef), 1);
    setupFetchFactoryMock(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(TEST_REF).build()),
        Optional.of(List.of(TEST_REF)));
    objectUnderTest.addRefs(refSpecs);
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = (projectName, refNames) -> Set.of(TEST_REF);
    when(replicationFilter.get()).thenReturn(mockFilter);

    objectUnderTest.run();

    verify(pullReplicationApiRequestMetrics).stop(any());
    assertThat(testMetricMaker.getTimer("replication_latency")).isGreaterThan(0);
  }

  @Test
  public void shouldSkipDeletionWhenDeleteAndCreateOfSameRef() {
    List<FetchRefSpec> fetchRefSpecs =
        List.of(
            FetchRefSpec.fromRef(":refs/something/someref"),
            FetchRefSpec.fromRef("refs/something/someref"));
    assertThat(refsToDelete(fetchRefSpecs)).isEmpty();
  }

  @Test
  public void shouldDeleteWhenCreateAndDeleteOfSameRef() {
    List<FetchRefSpec> fetchRefSpecs =
        List.of(
            FetchRefSpec.fromRef("refs/something/someref"),
            FetchRefSpec.fromRef(":refs/something/someref"));
    assertThat(refsToDelete(fetchRefSpecs)).isEqualTo(Set.of("refs/something/someref"));
  }

  @Test
  public void shouldNotDeleteWhenCreateRef() {
    List<FetchRefSpec> fetchRefSpecs = List.of(FetchRefSpec.fromRef("refs/something/someref"));
    assertThat(refsToDelete(fetchRefSpecs)).isEmpty();
  }

  @Test
  public void shouldDeleteWhenDeleteRef() {
    List<FetchRefSpec> fetchRefSpecs = List.of(FetchRefSpec.fromRef(":refs/something/someref"));
    assertThat(refsToDelete(fetchRefSpecs)).isEqualTo(Set.of("refs/something/someref"));
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

  private void setupGitRepoManagerMock() throws Exception {
    when(grm.openRepository(PROJECT_NAME)).thenReturn(repository);
  }

  private Ref mockRef(String objectIdStr) {
    Ref r = mock(Ref.class);
    when(r.getObjectId()).thenReturn(ObjectId.fromString(objectIdStr));
    return r;
  }

  private void setupFetchRefsDatabaseMock(Map<String, Ref> local, Map<String, Ref> remote)
      throws IOException {
    when(fetchRefsDatabase.getLocalRefsMap(repository)).thenReturn(local);
    when(fetchRefsDatabase.getRemoteRefsMap(repository, urIish)).thenReturn(remote);
  }

  private ReplicationFetchFilter setupReplicationFilterMock(Set<String> inRefs) {
    objectUnderTest.setReplicationFetchFilter(replicationFilter);
    ReplicationFetchFilter mockFilter = (projectName, refs) -> inRefs;
    when(replicationFilter.get()).thenReturn(mockFilter);
    return mockFilter;
  }

  private List<ReplicationState> createTestStates(FetchRefSpec refSpec, int numberOfStates) {
    List<ReplicationState> states =
        IntStream.rangeClosed(1, numberOfStates)
            .mapToObj(i -> Mockito.mock(ReplicationState.class))
            .collect(Collectors.toList());
    states.forEach(rs -> objectUnderTest.addState(refSpec, rs));

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
            .map(ffe -> FetchRefSpec.fromRef(ffe.getRefSpecName()))
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

  private Set<FetchRefSpec> refSpecsSetOf(String... refs) {
    return Stream.of(refs).map(FetchRefSpec::fromRef).collect(Collectors.toUnmodifiableSet());
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
