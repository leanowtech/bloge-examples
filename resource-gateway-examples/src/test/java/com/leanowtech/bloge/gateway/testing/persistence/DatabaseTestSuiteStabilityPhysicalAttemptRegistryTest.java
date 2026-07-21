package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCancellationCommand;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptIdentity;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRegistry;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityPhysicalAttemptRegistryTest {

    private static final String PROVIDER_ID = "isolated-runtime-a";
    private static final String DEPLOYMENT_ID = "isolated-runtime-a.generation-7";
    private static final TestSuiteStabilityQueuePolicy POLICY = new TestSuiteStabilityQueuePolicy(
            1, 20, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
            Duration.ofSeconds(1), Duration.ofMinutes(1), 3, Duration.ofDays(1),
            Duration.ofDays(7));

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseTestSuiteStabilityJobRepository jobs;
    private DatabaseTestSuiteStabilityPhysicalAttemptRegistry registry;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:physical-attempt-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        jobs = new DatabaseTestSuiteStabilityJobRepository(
                jdbc, mapper, new StoppedParentAuthority(),
                new TestSuiteStabilityJobRequestKeyProtector(
                        "request-key-a", Map.of("request-key-a", new byte[32])),
                "retention-a", Duration.ofSeconds(30), transactions);
        jobs.init();
        registry = new DatabaseTestSuiteStabilityPhysicalAttemptRegistry(
                jdbc, mapper, jobs, transactions);
        registry.init();
    }

    @Test
    void reservesExactLiveQueueRuntimeBindingAndReplaysIt() {
        TestSuiteStabilityJobClaim claim = claimed('a');
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claim.lease(), '1');

        TestSuiteStabilityPhysicalAttemptRegistry.Reservation first =
                registry.reserve(identity);
        TestSuiteStabilityPhysicalAttemptRegistry.Reservation replay =
                registry.reserve(identity);

        assertThat(first.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRegistry.ReservationStatus.RESERVED);
        assertThat(replay.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRegistry.ReservationStatus.REPLAYED);
        assertThat(replay.entry()).isEqualTo(first.entry());
        assertThat(registry.find("tenant-a", "test", identity.attemptId()))
                .contains(first.entry());
        registry.authorizeDispatch(identity.attemptId());
    }

    @Test
    void identityIsDeterministicAndChangesWithExecutableRuntimeBinding() {
        TestSuiteStabilityJobLease lease = claimed('a').lease();

        TestSuiteStabilityPhysicalAttemptIdentity first = identity(lease, '1');
        TestSuiteStabilityPhysicalAttemptIdentity replay = identity(lease, '1');
        TestSuiteStabilityPhysicalAttemptIdentity changed = identity(lease, '2');

        assertThat(replay).isEqualTo(first);
        assertThat(changed.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(changed.identityFingerprint()).isNotEqualTo(first.identityFingerprint());
    }

    @Test
    void canonicalIdentityMutationCannotReuseADerivedAttemptId() {
        TestSuiteStabilityPhysicalAttemptIdentity original = identity(claimed('a').lease(), '1');
        TestSuiteStabilityPhysicalAttemptIdentity mutated =
                new TestSuiteStabilityPhysicalAttemptIdentity(
                        original.schemaVersion(), original.attemptId(),
                        original.identityFingerprint(), original.tenantId(),
                        original.environmentId(), original.jobId(),
                        original.requestFingerprint(), original.ownerId(),
                        original.leaseEpoch(), original.runtimeBindingFingerprint(),
                        "isolated-runtime-b", original.deploymentId(),
                        original.isolationMode());

        assertConflict(() -> registry.reserve(mutated),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason
                        .IDEMPOTENCY_CONFLICT);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_stability_physical_attempts", Integer.class))
                .isZero();
    }

    @Test
    void exactConcurrentReservationCommitsOnceAndReturnsOneReplay() throws Exception {
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claimed('a').lease(), '1');

        List<TestSuiteStabilityPhysicalAttemptRegistry.ReservationStatus> results =
                concurrently(() -> registry.reserve(identity).status(),
                        () -> registry.reserve(identity).status());

        assertThat(results).containsExactlyInAnyOrder(
                TestSuiteStabilityPhysicalAttemptRegistry.ReservationStatus.RESERVED,
                TestSuiteStabilityPhysicalAttemptRegistry.ReservationStatus.REPLAYED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_stability_physical_attempts", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameLeaseEpochCannotBindTwoRuntimeGenerationsUnderConcurrency() throws Exception {
        TestSuiteStabilityJobLease lease = claimed('a').lease();
        TestSuiteStabilityPhysicalAttemptIdentity first = identity(lease, '1');
        TestSuiteStabilityPhysicalAttemptIdentity second = identity(lease, '2');

        List<String> results = concurrently(
                () -> reservationOutcome(first), () -> reservationOutcome(second));

        assertThat(results).containsExactlyInAnyOrder(
                "RESERVED", "CONFLICT:FENCE_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_stability_physical_attempts", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cancelledQueueFenceCannotBeReservedOrReauthorizedForDispatch() {
        TestSuiteStabilityJobClaim claim = claimed('a');
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claim.lease(), '1');
        registry.reserve(identity);
        TestSuiteStabilityJobRecord job = claim.job();
        jobs.cancel(new TestSuiteStabilityJobCancellationCommand(
                        job.tenantId(), job.environmentId(), job.jobId(), "cancel-a",
                        fingerprint('c'), job.principal()),
                POLICY, ignored -> TestRuntimeTransactionMutation.noop());

        assertConflict(() -> registry.reserve(identity),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason.LEASE_NOT_ACTIVE);
        assertConflict(() -> registry.authorizeDispatch(identity.attemptId()),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason.LEASE_NOT_ACTIVE);
        assertThat(registry.find("tenant-a", "test", identity.attemptId())).isPresent();
    }

    @Test
    void releasedOldLeaseCannotAuthorizeAReservedOrNewDispatch() {
        TestSuiteStabilityJobClaim claim = claimed('a');
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claim.lease(), '1');
        registry.reserve(identity);
        jobs.retry(claim.lease(), "RG.TEST.RUNTIME_UNAVAILABLE", POLICY);

        assertConflict(() -> registry.authorizeDispatch(identity.attemptId()),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason.LEASE_NOT_ACTIVE);
        assertConflict(() -> registry.reserve(identity),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason.LEASE_NOT_ACTIVE);
    }

    @Test
    void queuedJobCannotManufactureAPhysicalAttemptWithoutAClaim() {
        TestSuiteStabilityJobRecord queued = jobs.submit(submission('a'), POLICY);
        TestSuiteStabilityJobLease fabricated = new TestSuiteStabilityJobLease(
                queued.jobId(), queued.tenantId(), queued.environmentId(),
                queued.requestFingerprint(), "worker-a", 1, Instant.now().plusSeconds(30));

        assertConflict(() -> registry.reserve(identity(fabricated, '1')),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason.LEASE_NOT_ACTIVE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_stability_physical_attempts", Integer.class))
                .isZero();
    }

    @Test
    void scopeLookupDoesNotRevealAnotherTenantOrEnvironment() {
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claimed('a').lease(), '1');
        registry.reserve(identity);

        assertThat(registry.find("tenant-b", "test", identity.attemptId())).isEmpty();
        assertThat(registry.find("tenant-a", "staging", identity.attemptId())).isEmpty();
        assertThat(registry.find("tenant-a", "test", identity.attemptId())).isPresent();
    }

    @Test
    void retainedColumnTamperingFailsClosedBeforeProjection() {
        TestSuiteStabilityPhysicalAttemptIdentity identity = identity(claimed('a').lease(), '1');
        registry.reserve(identity);
        assertThat(jdbc.update("""
                UPDATE rg_test_stability_physical_attempts
                SET deployment_id = 'isolated-runtime-a.generation-tampered'
                WHERE attempt_id = ?
                """, identity.attemptId())).isEqualTo(1);

        assertThatThrownBy(() -> registry.find("tenant-a", "test", identity.attemptId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Physical-attempt reservation integrity failed");
    }

    @Test
    void absentReservationCannotBeAuthorized() {
        assertConflict(() -> registry.authorizeDispatch(
                        "stability-attempt-" + "a".repeat(64)),
                TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason
                        .ATTEMPT_NOT_RESERVED);
    }

    private TestSuiteStabilityJobClaim claimed(char id) {
        jobs.submit(submission(id), POLICY);
        TestSuiteStabilityJobClaim claim = jobs.claimNext("test", "worker-a", POLICY);
        assertThat(claim.outcome()).isEqualTo(TestSuiteStabilityJobClaim.Outcome.ACQUIRED);
        assertThat(claim.lease().epoch()).isPositive();
        return claim;
    }

    private TestSuiteStabilityPhysicalAttemptIdentity identity(
            TestSuiteStabilityJobLease lease, char runtime) {
        return TestSuiteStabilityPhysicalAttemptIdentity.create(
                mapper, lease, fingerprint(runtime), PROVIDER_ID, DEPLOYMENT_ID,
                TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
    }

    private String reservationOutcome(TestSuiteStabilityPhysicalAttemptIdentity identity) {
        try {
            return registry.reserve(identity).status().name();
        } catch (TestSuiteStabilityPhysicalAttemptRegistry.ConflictException conflict) {
            return "CONFLICT:" + conflict.reason();
        }
    }

    private static void assertConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            TestSuiteStabilityPhysicalAttemptRegistry.ConflictReason reason) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptRegistry.ConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    @SafeVarargs
    private static <T> List<T> concurrently(
            java.util.concurrent.Callable<T>... operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent reservation start timed out");
                    }
                    return operation.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return List.copyOf(results);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static TestSuiteStabilityJobSubmission submission(char id) {
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                "request-" + id, 3, Map.of("pipeline", "nightly"));
        return new TestSuiteStabilityJobSubmission(
                "stability-job-" + String.valueOf(id).repeat(64), request,
                fingerprint('9'), "INTERNAL",
                new TestSuiteStabilityJobPrincipal(
                        "tenant-a", "org-a", "project-a", "test", "sg-1", "SERVICE",
                        "ci-runner", "", "TEST_EXECUTION", "correlation-" + id,
                        Set.of("test-runners"), "INTERNAL", ""),
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                Instant.now().plus(Duration.ofHours(1)));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class StoppedParentAuthority
            implements TestSuiteStabilityJobParentAuthority {

        @Override
        public Resolution stop(
                TestSuiteStabilityJobRecord job,
                TestSuiteStabilityExecutionStop.Reason reason,
                String failureCode,
                Duration retention) {
            return Resolution.stopped();
        }

        @Override
        public Resolution requireCompleted(
                TestSuiteStabilityJobRecord job,
                String stabilityRunId,
                String evidenceFingerprint) {
            return Resolution.stopped();
        }
    }
}
