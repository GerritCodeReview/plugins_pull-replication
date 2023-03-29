package com.googlesource.gerrit.plugins.replication.pull;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gerrit.entities.Project;
import com.google.gerrit.metrics.DisabledMetricMaker;
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
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.PackProtocolException;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.hamcrest.collection.IsIterableContainingInOrder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
public class FetchOneTest {
  private final String testProjectName = "FetchOneTest";
  private final String testRef = "refs/heads/refForReplicationTask";

  private final int maxLockRetries = 1;

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

  private FetchReplicationMetrics fetchReplicationMetrics;
  private URIish urIish;
  private Project.NameKey projectName = Project.NameKey.parse(testProjectName);
  private CountDownLatch isCallFinished = new CountDownLatch(1);

  private int nextId;

  private FetchOne objectUnderTest;

  @Before
  public void setup() throws Exception {
    fetchReplicationMetrics =
        new FetchReplicationMetrics("pull-replication", new DisabledMetricMaker());
    urIish = new URIish("http://test.com/" + testProjectName + ".git");

    when(sourceConfiguration.getRemoteConfig()).thenReturn(remoteConfig);
    nextId = new Random().nextInt(100);
    setupIdGenerator(nextId);
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
            projectName,
            urIish,
            Optional.of(pullReplicationApiRequestMetrics));
  }

  @Test
  public void shouldIncludeTheTaskIndexAndRetryCountInItsStringRepresentation() {
    objectUnderTest.setToRetry();
    String taskIdHex = objectUnderTest.getTaskIdHex();

    String actual = objectUnderTest.toString();
    String expected = "(retry 1) [" + taskIdHex + "] fetch http://test.com/FetchOneTest.git";

    assertThat(actual, is(expected));
  }

  @Test
  public void shouldAddARefToTheDeltaIfItsNotTheAllRefs() {
    Set<String> refs = Set.of(testRef);
    objectUnderTest.addRefs(refs);

    assertEquals(refs, objectUnderTest.getRefs());
  }

  @Test
  public void shouldIgnoreEveryRefButTheAllRefsWhenAddingARef() {
    Set<String> refs = Set.of(testRef, FetchOne.ALL_REFS, testRef + "1");
    objectUnderTest.addRefs(refs);

    assertEquals(Set.of(FetchOne.ALL_REFS), objectUnderTest.getRefs());
  }

  @Test
  public void shouldReturnExistingStates() {
    List<ReplicationState> testStates = createTestStates(testRef, 1);

    assertEquals(testStates, objectUnderTest.getStates().get(testRef));
  }

  @Test
  public void shouldKeepMultipleStatesInInsertionOrderForARef() {
    List<ReplicationState> states = createTestStates(testRef, 2);

    List<ReplicationState> actualStates = objectUnderTest.getStates().get(testRef);

    assertThat(actualStates, IsIterableContainingInOrder.contains(states.toArray()));
  }

  @Test
  public void shouldReturnStatesInAnArray() {
    List<ReplicationState> states = createTestStates(testRef, 2);

    ReplicationState[] actualStates = objectUnderTest.getStatesAsArray();

    assertThat(actualStates, arrayContainingInAnyOrder(states.toArray()));
  }

  @Test
  public void shouldClearTheStates() {
    createTestStates(testRef, 2);

    objectUnderTest.removeStates();

    assertThat(objectUnderTest.getStates().isEmpty(), is(true));
  }

  @Test
  public void shouldRemoveTaskFromPendingQueueWhenCancelled() {
    objectUnderTest.cancel();

    verify(source).fetchWasCanceled(objectUnderTest);
    assertThat(objectUnderTest.wasCanceled(), is(true));
  }

  @Test
  public void shouldNotCreateAReplicationTaskIfDeltaIsEmpty() throws Exception {
    setupMocks(true);

    objectUnderTest.run();

    verify(fetchFactory, never()).create(any(), any(), any());
    verify(source).notifyFinished(objectUnderTest);
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
  public void shouldRunNoFetchOperationWhenStateIsEmpty() throws Exception {
    setupMocks(true);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefSpecName(testRef)
                .withRemoteName("testRemote")
                .withResult(RefUpdate.Result.NEW)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
  }

  @Test
  public void
      shouldSetTheReplicationFetchResultStatusToNotAttemptedAndThenFailedForARefForWhichThereIsNoState()
          throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates("someRef", 1);
    setupFetchFactory(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(testRef).build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            "someRef",
            urIish,
            ReplicationState.RefFetchResult.NOT_ATTEMPTED,
            null);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, "someRef", urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test(expected = InternalError.class)
  public void shouldThrowAnExceptionForUnrecoverableErrors() {
    setupFailingScopeMock();

    objectUnderTest.run();
  }

  @Test
  public void shouldMarkTheReplicationStatusAsSucceededOnSuccessfulReplicationOfARef()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    setupFetchFactory(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(testRef).build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();
    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldMarkAllTheStatesOfARefAsReplicatedSuccessfullyOnASuccessfulReplication()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 2);
    setupFetchFactory(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(testRef).build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldUpdateTheStateOfAllRefsOnSuccessfulReplication() throws Exception {
    setupMocks(true);
    List<ReplicationState> states =
        Stream.concat(
                createTestStates(testRef, 1).stream(),
                createTestStates(FetchOne.ALL_REFS, 1).stream())
            .collect(Collectors.toList());
    setupFetchFactory(
        List.of(new FetchFactoryEntry.Builder().refSpecNameWithDefaults(testRef).build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            testProjectName,
            FetchOne.ALL_REFS,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldMarkReplicationStateAsRejectedWhenTheObjectIsNotInRepository()
      throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 2);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
                .withResult(RefUpdate.Result.REJECTED_MISSING_OBJECT)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_MISSING_OBJECT);
  }

  @Test
  public void shouldMarkReplicationStateOfAllRefsAsRejectedForAnyFailedTask() throws Exception {
    setupMocks(true);
    String failingRef = "failingRef";
    String forcedRef = "forcedRef";
    List<ReplicationState> states =
        Stream.of(
                createTestStates(testRef, 1),
                createTestStates(failingRef, 1),
                createTestStates(forcedRef, 1),
                createTestStates(FetchOne.ALL_REFS, 1))
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
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
    objectUnderTest.addRefs(Set.of(testRef, failingRef, forcedRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
    verify(states.get(1))
        .notifyRefReplicated(
            testProjectName,
            failingRef,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_MISSING_OBJECT);
    verify(states.get(2))
        .notifyRefReplicated(
            testProjectName,
            forcedRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.FORCED);
    verify(states.get(3))
        .notifyRefReplicated(
            testProjectName,
            FetchOne.ALL_REFS,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.FORCED);
  }

  @Test
  public void shouldRetryOnLockingFailures() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.TRANSPORT_ERROR);
  }

  @Test
  public void shouldNotRetryOnLockingFailuresIfTheTaskWasCancelledWhileRunning() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
                .withResult(RefUpdate.Result.LOCK_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));
    objectUnderTest.setCanceledWhileRunning();

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source, never()).reschedule(any(), any());
  }

  @Test
  public void shouldNotRetryForUnexpectedIOErrors() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
                .withResult(RefUpdate.Result.IO_FAILURE)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source, never()).reschedule(any(), any());
  }

  @Test
  public void shouldMarkReplicationStateAsRejectedWhenFailedForUnknownReason() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    setupFetchFactory(
        List.of(
            new FetchFactoryEntry.Builder()
                .withRefNames(testRef)
                .withResult(RefUpdate.Result.REJECTED_OTHER_REASON)
                .build()));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithEmptyStateAndNoFailures();
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.FAILED,
            RefUpdate.Result.REJECTED_OTHER_REASON);
  }

  @Test
  public void shouldTreatInexistentRefsAsFailures() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    Fetch fetch =
        setupFetchFactory(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(testRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList()))
        .thenThrow(new InexistentRefTransportException(testRef, new Throwable("boom")));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, false);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  @Test
  public void shouldRemoveAnInexistentRefFromTheDeltaAndCarryOn() throws Exception {
    setupMocks(true);
    String inexistentRef = "inexistentRef";
    List<ReplicationState> states =
        Stream.of(createTestStates(inexistentRef, 1), createTestStates(testRef, 1))
            .flatMap(Collection::stream)
            .collect(Collectors.toList());
    Fetch fetch =
        setupFetchFactory(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(inexistentRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build(),
                new FetchFactoryEntry.Builder()
                    .withRefNames(testRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList()))
        .thenThrow(new InexistentRefTransportException(testRef, new Throwable("boom")))
        .thenReturn(List.of(new RefUpdateState(testRef, RefUpdate.Result.NEW)));
    objectUnderTest.addRefs(Set.of(inexistentRef, testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, false);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName,
            inexistentRef,
            urIish,
            ReplicationState.RefFetchResult.NOT_ATTEMPTED,
            null);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, inexistentRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(states.get(1))
        .notifyRefReplicated(
            testProjectName,
            testRef,
            urIish,
            ReplicationState.RefFetchResult.SUCCEEDED,
            RefUpdate.Result.NEW);
  }

  @Test
  public void shouldRescheduleCertainTypesOfTransportException() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    Fetch fetch =
        setupFetchFactory(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(testRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(Set.of(testRef));

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0))
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
    verify(source).reschedule(objectUnderTest, Source.RetryReason.TRANSPORT_ERROR);
  }

  @Test
  public void shouldNotMarkReplicationTaskAsFailedIfItIsBeingRetried() throws Exception {
    setupMocks(true);
    List<ReplicationState> states = createTestStates(testRef, 1);
    Fetch fetch =
        setupFetchFactory(
            List.of(
                new FetchFactoryEntry.Builder()
                    .withRefNames(testRef)
                    .withResult(RefUpdate.Result.NEW)
                    .build()));
    when(fetch.fetch(anyList())).thenThrow(new PackProtocolException(urIish, "boom"));
    objectUnderTest.addRefs(Set.of(testRef));
    objectUnderTest.setToRetry();

    objectUnderTest.run();

    assertFinishedWithStateAndFailures(false, true);
    verify(states.get(0), never())
        .notifyRefReplicated(
            testProjectName, testRef, urIish, ReplicationState.RefFetchResult.FAILED, null);
  }

  private void setupRequestScopeMock() {
    when(scoper.scope(any()))
        .thenAnswer(
            (Answer<Callable<Object>>)
                invocation -> {
                  Callable<Object> originalCall = (Callable<Object>) invocation.getArguments()[0];
                  return () -> {
                    Object result = originalCall.call();
                    isCallFinished.countDown();
                    return result;
                  };
                });
  }

  private void setupFailingScopeMock() {
    when(scoper.scope(any())).thenThrow(new InternalError());
  }

  private void setupMocks(boolean allowed) throws Exception {
    setupRequestScopeMock();
    setupSourceMock(allowed);
    setupGitRepoManagerMock();
  }

  private void setupSourceMock(boolean allowed) {
    when(source.requestRunway(any())).thenReturn(allowed);
  }

  private void setupGitRepoManagerMock() throws IOException {
    when(grm.openRepository(projectName)).thenReturn(repository);
  }

  private void setupIdGenerator(int id) {
    when(idGenerator.next()).thenReturn(id);
  }

  private Fetch setupFetchFactoryMock(List<RefSpec> refSpecs, List<RefUpdateState> refUpdateStates)
      throws IOException {
    Fetch mockFetch = mock(Fetch.class);
    when(fetchFactory.create(objectUnderTest.getTaskIdHex(), urIish, repository))
        .thenReturn(mockFetch);
    when(mockFetch.fetch(argThat(rs -> rs.containsAll(refSpecs)))).thenReturn(refUpdateStates);
    return mockFetch;
  }

  private void setupRemoteConfigMock(List<RefSpec> refSpecs) {
    when(remoteConfig.getFetchRefSpecs()).thenReturn(refSpecs);
    when(remoteConfig.getName()).thenReturn(projectName.get());
  }

  private void assertFinishedWithEmptyStateAndNoFailures() {
    assertFinishedWithStateAndFailures(true, true);
  }

  private List<ReplicationState> createTestStates(String ref, int numberOfStates) {
    List<ReplicationState> states =
        IntStream.rangeClosed(1, numberOfStates)
            .mapToObj(i -> Mockito.mock(ReplicationState.class))
            .collect(Collectors.toList());
    states.forEach(rs -> objectUnderTest.addState(ref, rs));

    return states;
  }

  private Fetch setupFetchFactory(List<FetchFactoryEntry> fetchFactoryEntries) throws Exception {
    List<RefSpec> refSpecs =
        fetchFactoryEntries.stream()
            .map(ffe -> new RefSpec(ffe.getRefSpecName()))
            .collect(Collectors.toList());
    List<RefUpdateState> refUpdateStates =
        fetchFactoryEntries.stream()
            .map(ffe -> new RefUpdateState(ffe.getRemoteName(), ffe.getResult()))
            .collect(Collectors.toList());
    Fetch mockFetch = setupFetchFactoryMock(refSpecs, refUpdateStates);
    setupRemoteConfigMock(refSpecs);
    return mockFetch;
  }

  private void assertFinishedWithStateAndFailures(boolean emptyState, boolean noFailures) {
    assertThat(objectUnderTest.getStates().isEmpty(), is(emptyState));
    verify(source).notifyFinished(objectUnderTest);
    assertThat(objectUnderTest.getFetchFailures().isEmpty(), is(noFailures));
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
