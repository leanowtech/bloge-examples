package com.leanowtech.bloge.gateway.integration.mirror;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMirrorRunRequestRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "project-a", "test", "region-a");
    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private DatabaseMirrorRunRequestRepository repository;
    private AtomicReference<Instant> databaseTime;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        databaseTime = new AtomicReference<>(NOW);
        repository = new DatabaseMirrorRunRequestRepository(jdbc, databaseTime::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsOnlyPayloadFreeCoordinationAndSurvivesRepositoryRestart() {
        var first = claim(registration("request-1", '1', '2'), "owner-a", NOW,
                NOW.plusSeconds(30));

        assertThat(first.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.ACQUIRED);
        assertThat(complete(first.lease(), "run-1", fingerprint('3'),
                NOW.plusSeconds(2))).isTrue();

        var restarted = new DatabaseMirrorRunRequestRepository(jdbc, databaseTime::get);
        restarted.init();
        databaseTime.set(NOW.plusSeconds(3));
        var retry = transactions.execute(status -> restarted.claim(
                registration("request-1", '1', '2'), "owner-b", Duration.ofSeconds(30)));

        assertThat(retry).isNotNull();
        assertThat(retry.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.COMPLETED);
        assertThat(retry.state().runId()).isEqualTo("run-1");
        assertThat(retry.state().evidenceBundleFingerprint()).isEqualTo(fingerprint('3'));
        assertThat(restarted.find(new CapabilitySnapshot.Scope(
                "tenant-b", "org-a", "project-a", "test", "region-a"), "request-1"))
                .isEmpty();

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_RUN_REQUESTS'
                ORDER BY ORDINAL_POSITION
                """, String.class);
        assertThat(columns).doesNotContain("CONTEXT", "CONTEXT_JSON", "REQUEST_JSON",
                "INPUT", "OUTPUT", "FIXTURE", "REPLAY_PAYLOAD");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_RUN_REQUESTS'
                  AND DATA_TYPE IN ('CHARACTER LARGE OBJECT', 'BINARY LARGE OBJECT')
                """, Integer.class)).isZero();
    }

    @Test
    void returnsInProgressThenFencesAnExpiredLeaseTakeover() {
        var first = claim(registration("request-2", '4', '5'), "owner-a", NOW,
                NOW.plusSeconds(10));
        var busy = claim(registration("request-2", '4', '5'), "owner-b",
                NOW.plusMillis(1), NOW.plusSeconds(11));
        var takeover = claim(registration("request-2", '4', '5'), "owner-c",
                NOW.plusSeconds(10), NOW.plusSeconds(40));

        assertThat(busy.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.IN_PROGRESS);
        assertThat(busy.retryAfterSeconds()).isEqualTo(10);
        assertThat(busy.state().leaseOwner()).isEqualTo("owner-a");
        assertThat(takeover.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.ACQUIRED);
        assertThat(takeover.lease().leaseEpoch()).isEqualTo(2);
        assertThat(complete(first.lease(), "run-stale", fingerprint('6'),
                NOW.plusSeconds(11))).isFalse();
        assertThat(release(first.lease(), "RG.MIRROR.STALE",
                NOW.plusSeconds(11))).isFalse();
        assertThat(complete(takeover.lease(), "run-current", fingerprint('7'),
                NOW.plusSeconds(12))).isTrue();
        assertThat(repository.find(SCOPE, "request-2")).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MirrorRunRequestRepository.Status.COMPLETED);
            assertThat(state.runId()).isEqualTo("run-current");
            assertThat(state.leaseEpoch()).isEqualTo(2);
        });
    }

    @Test
    void rejectsRequestIdReuseWithDifferentPlanOrContext() {
        claim(registration("request-3", '8', '9'), "owner-a", NOW,
                NOW.plusSeconds(10));

        assertThatThrownBy(() -> claim(registration("request-3", 'a', '9'),
                "owner-b", NOW.plusSeconds(1), NOW.plusSeconds(11)))
                .isInstanceOf(MirrorRunRequestConflictException.class);
        assertThatThrownBy(() -> claim(registration("request-3", '8', 'b'),
                "owner-c", NOW.plusSeconds(1), NOW.plusSeconds(11)))
                .isInstanceOf(MirrorRunRequestConflictException.class);
    }

    @Test
    void persistsStableTrustDecisionAndLeaseAttemptAcrossRepositoryRestart() {
        MirrorDeploymentIsolationRunTrust.Admission admission =
                MirrorPersistenceTestFixtures.trustAdmission(SCOPE);
        MirrorRunRequestRepository.Registration registration =
                certificationRegistration("request-trust-persist", admission);

        MirrorRunRequestRepository.Claim claim = claim(registration, "owner-a", NOW,
                NOW.plusSeconds(30), MirrorRunRequestRepository.TrustAttempt.from(admission));

        assertThat(claim.state().registration().trustDecision())
                .isEqualTo(MirrorRunRequestRepository.TrustDecision.certification(admission));
        assertThat(claim.lease().trustAttempt())
                .isEqualTo(MirrorRunRequestRepository.TrustAttempt.from(admission));

        DatabaseMirrorRunRequestRepository restarted =
                new DatabaseMirrorRunRequestRepository(jdbc, databaseTime::get);
        restarted.init();

        assertThat(restarted.find(SCOPE, registration.requestId())).get()
                .satisfies(state -> {
                    assertThat(state.registration().trustDecision())
                            .isEqualTo(registration.trustDecision());
                    assertThat(state.trustAttempt()).isEqualTo(claim.lease().trustAttempt());
                });
    }

    @Test
    void takeoverKeepsDecisionButReplacesAttemptWithNewerAgentObservation() {
        MirrorDeploymentIsolationRunTrust.Admission firstAdmission =
                MirrorPersistenceTestFixtures.trustAdmission(SCOPE);
        MirrorDeploymentIsolationRunTrust.Admission secondAdmission =
                new MirrorDeploymentIsolationRunTrust.Admission(SCOPE,
                        firstAdmission.decisionRef(), firstAdmission.authorityKeySetRef(),
                        firstAdmission.attestationRef(), firstAdmission.statusRef(),
                        new MirrorArtifactRef(MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                                firstAdmission.admittedSnapshotRef().id(), 13,
                                fingerprint('4')),
                        firstAdmission.admittedAt().plusSeconds(10),
                        firstAdmission.validUntil().plusSeconds(10));
        MirrorRunRequestRepository.Registration registration =
                certificationRegistration("request-trust-takeover", firstAdmission);
        claim(registration, "owner-a", NOW, NOW.plusSeconds(10),
                MirrorRunRequestRepository.TrustAttempt.from(firstAdmission));

        MirrorRunRequestRepository.Claim takeover = claim(registration, "owner-b",
                NOW.plusSeconds(10), NOW.plusSeconds(40),
                MirrorRunRequestRepository.TrustAttempt.from(secondAdmission));

        assertThat(takeover.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.ACQUIRED);
        assertThat(takeover.lease().leaseEpoch()).isEqualTo(2);
        assertThat(takeover.state().registration().trustDecision().decisionRef())
                .isEqualTo(firstAdmission.decisionRef());
        assertThat(takeover.lease().trustAttempt())
                .isEqualTo(MirrorRunRequestRepository.TrustAttempt.from(secondAdmission));
    }

    @Test
    void rejectsTrustRequiredClaimWithoutAttemptAndRequestReuseUnderAnotherDecision() {
        MirrorDeploymentIsolationRunTrust.Admission admission =
                MirrorPersistenceTestFixtures.trustAdmission(SCOPE);
        MirrorRunRequestRepository.Registration registration =
                certificationRegistration("request-trust-conflict", admission);

        assertThatThrownBy(() -> transactions.execute(status -> repository.claim(
                registration, "owner-missing", Duration.ofSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a trust attempt");

        claim(registration, "owner-a", NOW, NOW.plusSeconds(30),
                MirrorRunRequestRepository.TrustAttempt.from(admission));
        MirrorArtifactRef changedDecision = new MirrorArtifactRef(
                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                admission.decisionRef().id(), admission.decisionRef().revision() + 1,
                fingerprint('5'));
        MirrorRunRequestRepository.Registration conflicting =
                new MirrorRunRequestRepository.Registration(SCOPE, registration.requestId(),
                        registration.requestFingerprint(), registration.contextFingerprint(),
                        registration.planId(), registration.planFingerprint(),
                        registration.retainUntil(),
                        new MirrorRunRequestRepository.TrustDecision(true, changedDecision));

        assertThatThrownBy(() -> claim(conflicting, "owner-b", NOW.plusSeconds(1),
                NOW.plusSeconds(31), MirrorRunRequestRepository.TrustAttempt.from(admission)))
                .isInstanceOf(MirrorRunRequestConflictException.class);
    }

    @Test
    void releasedFailureCanBeRetriedImmediatelyWithoutWaitingForTheOriginalLease() {
        var first = claim(registration("request-4", 'c', 'd'), "owner-a", NOW,
                NOW.plusSeconds(300));
        assertThat(release(first.lease(), "RG.MIRROR.RUNTIME_REJECTED",
                NOW.plusSeconds(1))).isTrue();

        var retry = claim(registration("request-4", 'c', 'd'), "owner-b",
                NOW.plusSeconds(1), NOW.plusSeconds(301));

        assertThat(retry.outcome()).isEqualTo(MirrorRunRequestRepository.Outcome.ACQUIRED);
        assertThat(retry.lease().leaseEpoch()).isEqualTo(2);
        assertThat(retry.state().lastFailureCode()).isEmpty();
    }

    @Test
    void completionAtOrAfterLeaseExpiryIsRejectedWithoutWaitingForTakeover() {
        var claim = claim(registration("request-expired", '1', '2'), "owner-a", NOW,
                NOW.plusSeconds(10));

        assertThat(complete(claim.lease(), "run-expired", fingerprint('3'),
                NOW.plusSeconds(10))).isFalse();
        assertThat(repository.find(SCOPE, "request-expired")).get().satisfies(state -> {
            assertThat(state.status()).isEqualTo(MirrorRunRequestRepository.Status.ACTIVE);
            assertThat(state.runId()).isEmpty();
        });
    }

    @Test
    void concurrentFirstClaimsProduceExactlyOneExecutionAuthority() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                databaseTime.set(NOW);
                return transactions.execute(status -> repository.claim(
                        registration("request-5", 'e', 'f'), "owner-left",
                        Duration.ofSeconds(30)));
            });
            var right = executor.submit(() -> {
                start.await();
                databaseTime.set(NOW);
                return transactions.execute(status -> repository.claim(
                        registration("request-5", 'e', 'f'), "owner-right",
                        Duration.ofSeconds(30)));
            });
            start.countDown();

            Set<MirrorRunRequestRepository.Outcome> outcomes = Set.of(
                    left.get().outcome(), right.get().outcome());
            assertThat(outcomes).containsExactlyInAnyOrder(
                    MirrorRunRequestRepository.Outcome.ACQUIRED,
                    MirrorRunRequestRepository.Outcome.IN_PROGRESS);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_run_requests", Integer.class)).isEqualTo(1);
    }

    @Test
    void samplesDatabaseTimeAfterWaitingForTheAuthorityRowLock() throws Exception {
        MirrorRunRequestRepository.Registration registration =
                registration("request-lock-wait", '1', '2');
        claim(registration, "owner-a", NOW, NOW.plusSeconds(10));
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        CountDownLatch claimStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("""
                        SELECT lease_epoch FROM mirror_run_requests
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND request_id = ?
                        FOR UPDATE
                        """, Long.class, SCOPE.tenantId(), SCOPE.organizationId(),
                        SCOPE.projectId(), SCOPE.environmentId(), SCOPE.region(),
                        registration.requestId());
                rowLocked.countDown();
                await(releaseRow);
            }));
            assertThat(rowLocked.await(5, TimeUnit.SECONDS)).isTrue();

            databaseTime.set(NOW.plusSeconds(1));
            var waitingClaim = executor.submit(() -> {
                claimStarted.countDown();
                return transactions.execute(status -> repository.claim(
                        registration, "owner-b", Duration.ofSeconds(30)));
            });
            assertThat(claimStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            databaseTime.set(NOW.plusSeconds(10));
            releaseRow.countDown();

            assertThat(waitingClaim.get()).satisfies(claim -> {
                assertThat(claim.outcome())
                        .isEqualTo(MirrorRunRequestRepository.Outcome.ACQUIRED);
                assertThat(claim.lease().leaseEpoch()).isEqualTo(2);
                assertThat(claim.state().leaseExpiresAt()).isEqualTo(NOW.plusSeconds(40));
            });
            holder.get();
        } finally {
            releaseRow.countDown();
        }
    }

    @Test
    void productionDatabaseClockAdvancesInsideOneLongTransaction() throws Exception {
        DatabaseMirrorRunRequestRepository productionRepository =
                new DatabaseMirrorRunRequestRepository(jdbc);
        productionRepository.init();
        MirrorRunRequestRepository.Registration registration =
                registration("request-fresh-database-clock", '3', '4');

        transactions.executeWithoutResult(status -> {
            MirrorRunRequestRepository.Claim claim = productionRepository.claim(
                    registration, "owner-a", Duration.ofSeconds(30));
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("database-clock test interrupted", interrupted);
            }
            assertThat(productionRepository.complete(
                    claim.lease(), "run-fresh-clock", fingerprint('5'))).isTrue();
        });

        assertThat(productionRepository.find(SCOPE, registration.requestId())).get()
                .satisfies(state -> assertThat(state.updatedAt()).isAfter(state.createdAt()));
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt) {
        return claim(registration, owner, now, expiresAt, null);
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt,
            MirrorRunRequestRepository.TrustAttempt trustAttempt) {
        databaseTime.set(now);
        return transactions.execute(status -> repository.claim(
                registration, owner, Duration.between(now, expiresAt), trustAttempt));
    }

    private boolean complete(
            MirrorRunRequestRepository.Lease lease,
            String runId,
            String evidenceBundleFingerprint,
            Instant at) {
        databaseTime.set(at);
        return repository.complete(lease, runId, evidenceBundleFingerprint);
    }

    private boolean release(
            MirrorRunRequestRepository.Lease lease, String failureCode, Instant at) {
        databaseTime.set(at);
        return repository.release(lease, failureCode);
    }

    private static MirrorRunRequestRepository.Registration registration(
            String requestId, char requestFingerprint, char contextFingerprint) {
        return new MirrorRunRequestRepository.Registration(SCOPE, requestId,
                fingerprint(requestFingerprint), fingerprint(contextFingerprint),
                "plan-1", fingerprint('0'), NOW.plusSeconds(86_400));
    }

    private static MirrorRunRequestRepository.Registration certificationRegistration(
            String requestId,
            MirrorDeploymentIsolationRunTrust.Admission admission) {
        return new MirrorRunRequestRepository.Registration(SCOPE, requestId,
                fingerprint('1'), fingerprint('2'), "plan-1", fingerprint('0'),
                NOW.plusSeconds(86_400),
                MirrorRunRequestRepository.TrustDecision.certification(admission));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding mirror request row", interrupted);
        }
    }
}
