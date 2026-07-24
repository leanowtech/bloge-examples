package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class DatabaseScenarioRehearsalRunRepositoryTest {
    private static final CapabilitySnapshot.Scope SCOPE =
            MirrorPersistenceTestFixtures.scope("org-a");
    private static final Instant NOW =
            ScenarioRehearsalEvidenceTestFixtures.STARTED;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AtomicReference<Instant> databaseTime;
    private DatabaseScenarioRehearsalRunRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(database));
        databaseTime = new AtomicReference<>(NOW);
        repository = new DatabaseScenarioRehearsalRunRepository(
                jdbc, mapper, databaseTime::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void checkpointsAContiguousPayloadFreePrefixAndRecoversAfterRestart() {
        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(30));
        ScenarioCaseRehearsalResult result = caseResult();

        checkpoint(first.lease(), result, NOW.plusSeconds(1));

        DatabaseScenarioRehearsalRunRepository restarted =
                new DatabaseScenarioRehearsalRunRepository(
                        jdbc, mapper, databaseTime::get);
        restarted.init();
        databaseTime.set(NOW.plusSeconds(30));
        ScenarioRehearsalRunRepository.Claim takeover =
                transactions.execute(status -> restarted.claim(
                        registration(), "owner-b",
                        Duration.ofSeconds(30)));

        assertThat(takeover).isNotNull();
        assertThat(takeover.outcome())
                .isEqualTo(
                        ScenarioRehearsalRunRepository.Outcome.ACQUIRED);
        assertThat(takeover.lease().leaseEpoch()).isEqualTo(2);
        assertThat(restarted.progress(takeover.lease()))
                .containsExactly(result);
        databaseTime.set(NOW.plusSeconds(31));
        assertThat(restarted.complete(
                takeover.lease(), fingerprint('b'))).isTrue();
        assertThat(restarted.find(
                SCOPE,
                ScenarioRehearsalEvidenceTestFixtures.REQUEST_ID))
                .get()
                .satisfies(state -> {
                    assertThat(state.status()).isEqualTo(
                            ScenarioRehearsalRunRepository.Status.COMPLETED);
                    assertThat(state.nextCaseIndex()).isEqualTo(1);
                    assertThat(state.evidenceBundleFingerprint())
                            .isEqualTo(fingerprint('b'));
                    assertThat(state.startedAt()).isEqualTo(NOW);
                });

        List<String> requestColumns = columns(
                "SCENARIO_REHEARSAL_RUN_REQUESTS");
        List<String> progressColumns = columns(
                "SCENARIO_REHEARSAL_CASE_PROGRESS");
        assertThat(requestColumns).noneMatch(
                DatabaseScenarioRehearsalRunRepositoryTest::businessPayloadColumn);
        assertThat(progressColumns).noneMatch(
                DatabaseScenarioRehearsalRunRepositoryTest::businessPayloadColumn);
        assertThat(progressColumns).contains("RESULT_JSON");
    }

    @Test
    void returnsBusyThenFencesTheOldEpochAfterDatabaseClockTakeover() {
        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(10));
        ScenarioRehearsalRunRepository.Claim busy =
                claim(
                        registration(),
                        "owner-b",
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(31));
        ScenarioRehearsalRunRepository.Claim takeover =
                claim(
                        registration(),
                        "owner-c",
                        NOW.plusSeconds(10),
                        NOW.plusSeconds(40));

        assertThat(busy.outcome()).isEqualTo(
                ScenarioRehearsalRunRepository.Outcome.IN_PROGRESS);
        assertThat(busy.retryAfterSeconds()).isEqualTo(9);
        assertThat(takeover.lease().leaseEpoch()).isEqualTo(2);
        assertThatThrownBy(() -> checkpoint(
                first.lease(), caseResult(), NOW.plusSeconds(11)))
                .isInstanceOf(
                        ScenarioRehearsalLeaseLostException.class);
        assertThat(release(
                first.lease(),
                "RG.MIRROR.REHEARSAL.STALE",
                NOW.plusSeconds(11))).isFalse();

        checkpoint(
                takeover.lease(),
                caseResult(),
                NOW.plusSeconds(11));
        assertThat(repository.progress(takeover.lease()))
                .containsExactly(caseResult());
    }

    @Test
    void rejectsRequestReuseAndNonContiguousOrExpiredProgress() {
        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(10));
        ScenarioRehearsalRunRepository.Registration conflicting =
                new ScenarioRehearsalRunRepository.Registration(
                        SCOPE,
                        registration().requestId(),
                        fingerprint('c'),
                        registration().compiledPlanRef(),
                        registration().runId(),
                        1,
                        registration().retainUntil());

        assertThatThrownBy(() -> claim(
                conflicting,
                "owner-b",
                NOW.plusSeconds(1),
                NOW.plusSeconds(31)))
                .isInstanceOf(
                        ScenarioRehearsalRunRequestConflictException.class);

        ScenarioCaseRehearsalResult wrongIndex =
                ScenarioRehearsalResultIntegrity.sealCase(
                        mapper,
                        new ScenarioCaseRehearsalResult(
                                "", "", 1,
                                caseResult().scenarioCaseRef(),
                                caseResult().caseType(),
                                caseResult().testSuiteRef(),
                                caseResult().testCaseId(),
                                caseResult().mirrorPlanRef(),
                                caseResult().fixtureBundleRef(),
                                null,
                                registration().requestId()
                                        + ":case:001",
                                caseResult().outcome(),
                                "", "", null, null, List.of(),
                                caseResult().diagnosticCode(),
                                NOW, NOW.plusSeconds(1)));
        assertThatThrownBy(() -> checkpoint(
                first.lease(), wrongIndex, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact next");

        assertThatThrownBy(() -> checkpoint(
                first.lease(), caseResult(), NOW.plusSeconds(10)))
                .isInstanceOf(
                        ScenarioRehearsalLeaseLostException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_case_progress",
                Integer.class)).isZero();
    }

    @Test
    void failsClosedWhenProgressIndexOrContentAddressIsTampered() {
        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(30));
        checkpoint(first.lease(), caseResult(), NOW.plusSeconds(1));

        jdbc.update("""
                UPDATE scenario_rehearsal_case_progress
                SET result_fingerprint = ?
                WHERE request_id = ?
                """, fingerprint('d'), registration().requestId());

        assertThatThrownBy(() -> repository.progress(first.lease()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity validation");
    }

    @Test
    void isolatesTheSameRequestIdentityByCompleteEnterpriseScope() {
        CapabilitySnapshot.Scope otherScope =
                MirrorPersistenceTestFixtures.scope("org-b");
        ScenarioRehearsalRunRepository.Registration other =
                registration(otherScope);

        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(30));
        ScenarioRehearsalRunRepository.Claim second =
                claim(other, "owner-b", NOW, NOW.plusSeconds(30));

        assertThat(first.outcome()).isEqualTo(
                ScenarioRehearsalRunRepository.Outcome.ACQUIRED);
        assertThat(second.outcome()).isEqualTo(
                ScenarioRehearsalRunRepository.Outcome.ACQUIRED);
        assertThat(repository.find(
                SCOPE, registration().requestId())).isPresent();
        assertThat(repository.find(
                otherScope, other.requestId())).isPresent();
        assertThat(repository.find(
                MirrorPersistenceTestFixtures.scope("org-c"),
                registration().requestId())).isEmpty();
    }

    @Test
    void releasedAttemptResumesItsCheckpointWithoutWaitingForExpiry() {
        ScenarioRehearsalRunRepository.Claim first =
                claim(registration(), "owner-a", NOW, NOW.plusSeconds(300));
        checkpoint(first.lease(), caseResult(), NOW.plusSeconds(1));
        assertThat(release(
                first.lease(),
                "RG.MIRROR.REHEARSAL.CHILD_RETRY",
                NOW.plusSeconds(2))).isTrue();

        ScenarioRehearsalRunRepository.Claim resumed =
                claim(
                        registration(),
                        "owner-b",
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(302));

        assertThat(resumed.outcome()).isEqualTo(
                ScenarioRehearsalRunRepository.Outcome.ACQUIRED);
        assertThat(resumed.lease().leaseEpoch()).isEqualTo(2);
        assertThat(repository.progress(resumed.lease()))
                .containsExactly(caseResult());
    }

    @Test
    void samplesDatabaseTimeAfterWaitingForTheAuthorityRowLock()
            throws Exception {
        ScenarioRehearsalRunRepository.Registration registration =
                registration();
        claim(registration, "owner-a", NOW, NOW.plusSeconds(10));
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRow = new CountDownLatch(1);
        CountDownLatch claimStarted = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() ->
                    transactions.executeWithoutResult(status -> {
                        jdbc.queryForObject("""
                                SELECT lease_epoch
                                FROM scenario_rehearsal_run_requests
                                WHERE tenant_id = ?
                                  AND organization_id = ?
                                  AND project_id = ?
                                  AND environment_id = ?
                                  AND region = ?
                                  AND request_id = ?
                                FOR UPDATE
                                """,
                                Long.class,
                                SCOPE.tenantId(),
                                SCOPE.organizationId(),
                                SCOPE.projectId(),
                                SCOPE.environmentId(),
                                SCOPE.region(),
                                registration.requestId());
                        rowLocked.countDown();
                        await(releaseRow);
                    }));
            assertThat(rowLocked.await(
                    5, TimeUnit.SECONDS)).isTrue();
            databaseTime.set(NOW.plusSeconds(1));
            var waiting = executor.submit(() -> {
                claimStarted.countDown();
                return transactions.execute(status ->
                        repository.claim(
                                registration,
                                "owner-b",
                                Duration.ofSeconds(30)));
            });
            assertThat(claimStarted.await(
                    5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(250);
            databaseTime.set(NOW.plusSeconds(10));
            releaseRow.countDown();

            assertThat(waiting.get(
                    5, TimeUnit.SECONDS)).satisfies(claim -> {
                        assertThat(claim.outcome()).isEqualTo(
                                ScenarioRehearsalRunRepository
                                        .Outcome.ACQUIRED);
                        assertThat(claim.lease().leaseEpoch())
                                .isEqualTo(2);
                        assertThat(claim.state().leaseExpiresAt())
                                .isEqualTo(NOW.plusSeconds(40));
                    });
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseRow.countDown();
        }
    }

    @Test
    void concurrentFirstClaimsCreateExactlyOneAggregateAuthority()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                start.await();
                return transactions.execute(status ->
                        repository.claim(
                                registration(),
                                "owner-left",
                                Duration.ofSeconds(30)));
            });
            var right = executor.submit(() -> {
                start.await();
                return transactions.execute(status ->
                        repository.claim(
                                registration(),
                                "owner-right",
                                Duration.ofSeconds(30)));
            });
            start.countDown();

            Set<ScenarioRehearsalRunRepository.Outcome> outcomes =
                    Set.of(
                            left.get(10, TimeUnit.SECONDS).outcome(),
                            right.get(10, TimeUnit.SECONDS).outcome());
            assertThat(outcomes).containsExactlyInAnyOrder(
                    ScenarioRehearsalRunRepository.Outcome.ACQUIRED,
                    ScenarioRehearsalRunRepository.Outcome.IN_PROGRESS);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_run_requests",
                Integer.class)).isEqualTo(1);
    }

    private ScenarioRehearsalRunRepository.Claim claim(
            ScenarioRehearsalRunRepository.Registration registration,
            String owner,
            Instant now,
            Instant expiresAt) {
        databaseTime.set(now);
        return transactions.execute(status -> repository.claim(
                registration,
                owner,
                Duration.between(now, expiresAt)));
    }

    private void checkpoint(
            ScenarioRehearsalRunRepository.Lease lease,
            ScenarioCaseRehearsalResult result,
            Instant at) {
        databaseTime.set(at);
        transactions.executeWithoutResult(status ->
                repository.checkpoint(lease, result));
    }

    private boolean release(
            ScenarioRehearsalRunRepository.Lease lease,
            String code,
            Instant at) {
        databaseTime.set(at);
        return transactions.execute(status ->
                repository.release(lease, code));
    }

    private ScenarioRehearsalRunRepository.Registration registration() {
        return registration(SCOPE);
    }

    private ScenarioRehearsalRunRepository.Registration registration(
            CapabilitySnapshot.Scope scope) {
        ScenarioRehearsalResult result =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, scope, '5');
        return new ScenarioRehearsalRunRepository.Registration(
                scope,
                result.requestId(),
                fingerprint('a'),
                result.compiledPlanRef(),
                ScenarioRehearsalRunIdentity.derive(
                        mapper, scope, result.requestId()),
                result.caseResults().size(),
                NOW.plus(Duration.ofDays(30)));
    }

    private ScenarioCaseRehearsalResult caseResult() {
        return ScenarioRehearsalEvidenceTestFixtures.result(
                mapper, SCOPE, '5').caseResults().getFirst();
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """, String.class, table);
    }

    private static boolean businessPayloadColumn(String column) {
        return column.contains("PAYLOAD")
                || column.contains("FIXTURE")
                || column.contains("CONTEXT")
                || column.contains("INPUT")
                || column.contains("OUTPUT")
                || column.contains("ENTITY");
    }

    private static String fingerprint(char value) {
        return ScenarioRehearsalEvidenceTestFixtures.fingerprint(
                value);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while holding Scenario authority row",
                    interrupted);
        }
    }
}
