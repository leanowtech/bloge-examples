package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionProgress;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStopRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityRunRepositoryTest {
    private static final String REQUEST_FINGERPRINT =
            TestSuiteStabilityProtocolFixtures.fingerprint('9');

    private ObjectMapper mapper;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseTestSuiteStabilityRunRepository repository;
    private TestSuiteStabilityAttestationService attestations;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new DatabaseTestSuiteStabilityRunRepository(jdbc, mapper);
        repository.init();
        attestations = new TestSuiteStabilityAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
    }

    @Test
    void signedTerminalAnalysisRoundTripsOnlyInsideItsScope() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = checkpointed(
                record, acquired(record, "owner-a"));

        assertThat(repository.complete(record, lease)).isEqualTo(record);
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findByClientRequestId(
                "tenant-a", "test", "stability-request")).contains(record);
        assertThat(repository.find("tenant-b", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.find("tenant-a", "staging", record.stabilityRunId())).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void claimCreatesAnEmptyScopedProgressJournalWithALiveOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        TestSuiteStabilityLeaseClaim claim = repository.claim(
                leaseRequest(record, "owner-a"));

        assertThat(claim.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(claim.progress().stabilityRunId()).isEqualTo(record.stabilityRunId());
        assertThat(claim.progress().suiteRef()).isEqualTo(record.evidence().suiteRef());
        assertThat(claim.progress().plannedAttempts())
                .isEqualTo(record.evidence().requestedAttempts());
        assertThat(claim.progress().attempts()).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.liveOwner()).isTrue();
                    assertThat(snapshot.progress()).isEqualTo(claim.progress());
                });
        assertThat(repository.findProgress("tenant-b", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void checkpointAppendsOnlyTheNextAttemptAndRenewsTheExactFenceAtomically() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");
        TestSuiteStabilityExecutionProgress.AttemptReference first = reference(record, 0);

        var checkpoint = repository.checkpoint(lease, first,
                Duration.ofSeconds(60), Duration.ofDays(30));

        assertThat(checkpoint.lease().expiresAt()).isAfter(lease.expiresAt());
        assertThat(checkpoint.progress().attempts()).containsExactly(first);
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.progress().attempts())
                .isEqualTo(List.of(first));

        assertThatThrownBy(() -> repository.checkpoint(checkpoint.lease(), reference(record, 2),
                Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT));
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.progress().attempts())
                .isEqualTo(List.of(first));
        assertThat(repository.renew(checkpoint.lease(), Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void takeoverResumesTheDurablePrefixAndFencesTheExpiredOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease old = acquired(record, "owner-a");
        TestSuiteStabilityExecutionLease afterFirst = repository.checkpoint(
                old, reference(record, 0), Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, old.stabilityRunId());

        TestSuiteStabilityLeaseClaim takeover = repository.claim(
                leaseRequest(record, "owner-b"));

        assertThat(takeover.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(takeover.lease().epoch()).isEqualTo(1);
        assertThat(takeover.progress().attempts()).containsExactly(reference(record, 0));
        assertThatThrownBy(() -> repository.checkpoint(
                afterFirst, reference(record, 1), Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));

        TestSuiteStabilityExecutionLease completeLease = checkpointRemaining(
                record, takeover.lease(), 1);
        assertThat(repository.complete(record, completeLease)).isEqualTo(record);
    }

    @Test
    void incompleteProgressCannotPublishAndTheTransactionLeavesItRecoverable() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");
        lease = repository.checkpoint(lease, reference(record, 0),
                Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        TestSuiteStabilityExecutionLease incomplete = lease;

        assertThatThrownBy(() -> repository.complete(record, incomplete))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .get().satisfies(snapshot -> {
                    assertThat(snapshot.liveOwner()).isTrue();
                    assertThat(snapshot.progress().completedAttempts()).isEqualTo(1);
                });
        assertThat(repository.renew(incomplete, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void statisticalV3AnalysisRoundTripsWithoutLosingItsSignedAssessment() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        repository.complete(record, checkpointed(record, acquired(record, "owner-a")));

        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.evidence().statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
    }

    @Test
    void scopedIdempotencyKeyCannotBeReboundAfterTerminalPublication() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.complete(record, checkpointed(record, acquired(record, "owner-a")));
        TestSuiteStabilityLeaseRequest changed = new TestSuiteStabilityLeaseRequest(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), TestSuiteStabilityProtocolFixtures.fingerprint('8'),
                record.evidence().suiteRef(), record.classification(),
                record.evidence().requestedAttempts(), "owner-b", Duration.ofSeconds(30),
                Duration.ofDays(30));

        assertThatThrownBy(() -> repository.claim(changed))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void rejectsTamperedFingerprintOrUnsignedMaterialBeforePersistence() {
        TestSuiteStabilityRunRecord valid = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityRunRecord tampered = new TestSuiteStabilityRunRecord(
                valid.stabilityRunId(), valid.clientRequestId(), valid.requestFingerprint(),
                valid.tenantId(), valid.organizationId(), valid.projectId(),
                valid.environmentId(), valid.actorId(), valid.classification(),
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), valid.evidence(),
                valid.attestation(), valid.createdAt(), valid.expiresAt());
        TestSuiteStabilityExecutionLease lease = acquired(valid, "owner-a");

        assertThatThrownBy(() -> repository.complete(tampered, lease))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed terminal stability record");
    }

    @Test
    void activeOwnerIsObservedAndExactRenewalAndReleaseAreFenced() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease first = acquired(record, "owner-a");

        TestSuiteStabilityLeaseClaim duplicate = repository.claim(
                leaseRequest(record, "owner-b"));
        assertThat(duplicate.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.IN_PROGRESS);
        assertThat(duplicate.retryAfterSeconds()).isBetween(1L, 30L);

        TestSuiteStabilityExecutionLease renewed = repository.renew(
                first, Duration.ofSeconds(60)).orElseThrow();
        assertThat(renewed.expiresAt()).isAfter(first.expiresAt());
        TestSuiteStabilityExecutionLease staleEpoch = new TestSuiteStabilityExecutionLease(
                renewed.stabilityRunId(), renewed.tenantId(), renewed.environmentId(),
                renewed.clientRequestId(), renewed.requestFingerprint(), renewed.ownerId(),
                renewed.epoch() + 1, renewed.expiresAt());
        assertThat(repository.release(staleEpoch)).isFalse();
        assertThat(repository.release(renewed)).isTrue();
        assertThat(repository.claim(leaseRequest(record, "owner-c")).state())
                .isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
    }

    @Test
    void expiredLeaseTakeoverIncrementsEpochAndOldOwnerCannotPublish() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease old = acquired(record, "owner-a");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, old.stabilityRunId());

        TestSuiteStabilityLeaseClaim takeover = repository.claim(
                leaseRequest(record, "owner-b"));
        assertThat(takeover.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        assertThat(takeover.lease().epoch()).isEqualTo(1);
        assertThatThrownBy(() -> repository.complete(record, old))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.complete(record, checkpointed(record, takeover.lease())))
                .isEqualTo(record);
    }

    @Test
    void orphanCleanupIsBoundedAndCannotDeleteAReacquiredLiveLease() {
        TestSuiteStabilityRunRecord first = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease expired = acquired(first, "owner-a");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_leases
                SET lease_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP)
                WHERE stability_run_id = ?
                """, expired.stabilityRunId());

        assertThat(repository.purgeExpiredLeases(1)).isEqualTo(1);
        TestSuiteStabilityExecutionLease live = acquired(first, "owner-b");
        assertThat(repository.purgeExpiredLeases(1)).isZero();
        assertThat(repository.renew(live, Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void stopAtomicallyConsumesResumableStateAndPermanentlyFencesTheOldOwner() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionLease owner = acquired(record, "owner-a");
        owner = repository.checkpoint(owner, reference(record, 0),
                Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        TestSuiteStabilityExecutionLease stoppedOwner = owner;

        TestSuiteStabilityExecutionStop stop = repository.stop(stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED"));

        assertThat(repository.findStop("tenant-a", "test", record.stabilityRunId()))
                .contains(stop);
        assertThat(repository.findProgress("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
        assertThat(repository.renew(stoppedOwner, Duration.ofSeconds(30))).isEmpty();
        assertThat(repository.claim(leaseRequest(record, "owner-b")))
                .satisfies(claim -> {
                    assertThat(claim.state())
                            .isEqualTo(TestSuiteStabilityLeaseClaim.State.STOPPED);
                    assertThat(claim.stop()).isEqualTo(stop);
                });
        assertThatThrownBy(() -> repository.checkpoint(stoppedOwner, reference(record, 1),
                Duration.ofSeconds(30), Duration.ofDays(30)))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.LEASE_LOST));
        assertThatThrownBy(() -> repository.complete(record, stoppedOwner))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT));
    }

    @Test
    void stopBeforeClaimIsStrictlyIdempotentAndCannotBeRebound() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        TestSuiteStabilityExecutionStopRequest request = stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED,
                "RG.TEST.STABILITY_DEADLINE_EXCEEDED");

        TestSuiteStabilityExecutionStop first = repository.stop(request);

        assertThat(repository.stop(request)).isEqualTo(first);
        assertThat(repository.claim(leaseRequest(record, "owner-a")).stop())
                .isEqualTo(first);
        TestSuiteStabilityExecutionStopRequest changed =
                stopRequest(record, TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED,
                        "RG.TEST.STABILITY_WORKER_FAILED");
        assertThatThrownBy(() -> repository.stop(changed))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void signedTerminalEvidenceCannotBeReplacedByALateStop() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.complete(record, checkpointed(record, acquired(record, "owner-a")));

        assertThatThrownBy(() -> repository.stop(stopRequest(
                record, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED")))
                .isInstanceOfSatisfying(TestSuiteStabilityRunConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityRunConflictException.Reason.TERMINAL_CONFLICT));
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findStop("tenant-a", "test", record.stabilityRunId()))
                .isEmpty();
    }

    @Test
    void corruptedStopMaterialFailsClosedBeforeItCanFenceAClaim() throws Exception {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.stop(stopRequest(record,
                TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_CANCELLED"));
        String stored = jdbc.queryForObject("""
                SELECT stop_json FROM rg_test_suite_stability_execution_stops
                WHERE stability_run_id = ?
                """, String.class, record.stabilityRunId());
        var tampered = mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered)
                .put("failureCode", "RG.TEST.STABILITY_WORKER_FAILED");
        jdbc.update("""
                UPDATE rg_test_suite_stability_execution_stops SET stop_json = ?
                WHERE stability_run_id = ?
                """, mapper.writeValueAsString(tampered), record.stabilityRunId());

        assertThatThrownBy(() -> repository.findStop(
                "tenant-a", "test", record.stabilityRunId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
        assertThatThrownBy(() -> repository.claim(leaseRequest(record, "owner-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fingerprint is invalid");
    }

    @Test
    void twoRepositoryInstancesProduceOneCrossReplicaOwner() throws Exception {
        DatabaseTestSuiteStabilityRunRepository replica =
                new DatabaseTestSuiteStabilityRunRepository(new JdbcTemplate(dataSource), mapper);
        replica.init();
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<TestSuiteStabilityLeaseClaim> left = pool.submit(() -> {
                start.await();
                return repository.claim(leaseRequest(record, "owner-a"));
            });
            Future<TestSuiteStabilityLeaseClaim> right = pool.submit(() -> {
                start.await();
                return replica.claim(leaseRequest(record, "owner-b"));
            });
            start.countDown();

            assertThat(List.of(left.get().state(), right.get().state()))
                    .containsExactlyInAnyOrder(TestSuiteStabilityLeaseClaim.State.ACQUIRED,
                            TestSuiteStabilityLeaseClaim.State.IN_PROGRESS);
        } finally {
            pool.shutdownNow();
        }
    }

    private TestSuiteStabilityExecutionLease acquired(
            TestSuiteStabilityRunRecord record,
            String owner) {
        TestSuiteStabilityLeaseClaim claim = repository.claim(leaseRequest(record, owner));
        assertThat(claim.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.ACQUIRED);
        return claim.lease();
    }

    private static TestSuiteStabilityLeaseRequest leaseRequest(
            TestSuiteStabilityRunRecord record,
            String owner) {
        return new TestSuiteStabilityLeaseRequest(record.stabilityRunId(), record.tenantId(),
                record.environmentId(), record.clientRequestId(), record.requestFingerprint(),
                record.evidence().suiteRef(), record.classification(),
                record.evidence().requestedAttempts(), owner, Duration.ofSeconds(30),
                Duration.ofDays(30));
    }

    private static TestSuiteStabilityExecutionStopRequest stopRequest(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode) {
        return new TestSuiteStabilityExecutionStopRequest(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), record.requestFingerprint(), record.classification(),
                reason, failureCode, "test-worker", Duration.ofDays(30));
    }

    private TestSuiteStabilityExecutionLease checkpointed(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease) {
        return checkpointRemaining(record, lease, 0);
    }

    private TestSuiteStabilityExecutionLease checkpointRemaining(
            TestSuiteStabilityRunRecord record,
            TestSuiteStabilityExecutionLease lease,
            int completedAttempts) {
        TestSuiteStabilityExecutionLease current = lease;
        for (TestSuiteStabilityEvidence.AttemptResult attempt
                : record.evidence().attempts().subList(
                completedAttempts, record.evidence().attempts().size())) {
            current = repository.checkpoint(current,
                    new TestSuiteStabilityExecutionProgress.AttemptReference(
                            attempt.attempt(), attempt.suiteRunId(),
                            attempt.aggregateEvidenceFingerprint()),
                    Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        }
        return current;
    }

    private static TestSuiteStabilityExecutionProgress.AttemptReference reference(
            TestSuiteStabilityRunRecord record,
            int zeroBasedIndex) {
        TestSuiteStabilityEvidence.AttemptResult attempt =
                record.evidence().attempts().get(zeroBasedIndex);
        return new TestSuiteStabilityExecutionProgress.AttemptReference(
                attempt.attempt(), attempt.suiteRunId(),
                attempt.aggregateEvidenceFingerprint());
    }

    private TestSuiteStabilityRunRecord record(
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.stableEvidence();
        return record(evidence, tenantId, environmentId, clientRequestId, expiresAt);
    }

    private TestSuiteStabilityRunRecord record(
            TestSuiteStabilityEvidence evidence,
            String tenantId,
            String environmentId,
            String clientRequestId,
            Instant expiresAt) {
        var seal = attestations.seal(evidence, REQUEST_FINGERPRINT);
        Instant createdAt = Instant.now();
        return new TestSuiteStabilityRunRecord(evidence.stabilityRunId(), clientRequestId,
                REQUEST_FINGERPRINT, tenantId, "org-a", "project-a", environmentId,
                "runner", "INTERNAL", seal.attestation().evidenceFingerprint(), evidence,
                seal.attestation(), createdAt, expiresAt);
    }
}
