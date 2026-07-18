package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionLease;
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
        TestSuiteStabilityExecutionLease lease = acquired(record, "owner-a");

        assertThat(repository.complete(record, lease)).isEqualTo(record);
        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .contains(record);
        assertThat(repository.findByClientRequestId(
                "tenant-a", "test", "stability-request")).contains(record);
        assertThat(repository.find("tenant-b", "test", record.stabilityRunId())).isEmpty();
        assertThat(repository.find("tenant-a", "staging", record.stabilityRunId())).isEmpty();
    }

    @Test
    void statisticalV3AnalysisRoundTripsWithoutLosingItsSignedAssessment() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence();
        TestSuiteStabilityRunRecord record = record(evidence, "tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));

        repository.complete(record, acquired(record, "owner-a"));

        assertThat(repository.find("tenant-a", "test", record.stabilityRunId()))
                .get().extracting(value -> value.evidence().statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
    }

    @Test
    void scopedIdempotencyKeyCannotBeReboundAfterTerminalPublication() {
        TestSuiteStabilityRunRecord record = record("tenant-a", "test",
                "stability-request", Instant.now().plusSeconds(30));
        repository.complete(record, acquired(record, "owner-a"));
        TestSuiteStabilityLeaseRequest changed = new TestSuiteStabilityLeaseRequest(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), TestSuiteStabilityProtocolFixtures.fingerprint('8'),
                "owner-b", Duration.ofSeconds(30));

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
        assertThat(repository.complete(record, takeover.lease())).isEqualTo(record);
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
                owner, Duration.ofSeconds(30));
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
