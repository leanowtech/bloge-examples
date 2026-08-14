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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseRuntimeCertificationExecutionJournalTest {
    private static final Duration LEASE = Duration.ofMinutes(30);

    private final RuntimeCertificationTestFixtures fixtures =
            new RuntimeCertificationTestFixtures();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now = new AtomicReference<>(fixtures.now);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseRuntimeCertificationExecutionJournal journal;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        journal = repository();
        journal.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsEveryScenarioAndExactReplaysCompletedReportAfterRestart() {
        var acquired = journal.claimOrResume(identity(), "worker-a", LEASE,
                Instant.MAX.minusSeconds(1));
        var lease = acquired.lease();
        List<RuntimeCertificationReport.ScenarioResult> results = fixtures.results(null);
        for (RuntimeCertificationReport.ScenarioResult result : results) {
            journal.appendScenario(lease, result);
        }
        RuntimeCertificationReport report = fixtures.report(
                results, acquired.authorizationConsumptionRef());
        journal.complete(lease, report);

        DatabaseRuntimeCertificationExecutionJournal restarted = repository();
        restarted.init();
        var replay = restarted.claimOrResume(identity(), "worker-b", LEASE, Instant.EPOCH);

        assertThat(acquired.status()).isEqualTo(
                RuntimeCertificationExecutionJournal.ClaimStatus.ACQUIRED);
        assertThat(acquired.lease().expiresAt()).isEqualTo(fixtures.now.plus(LEASE));
        assertThat(replay.status()).isEqualTo(
                RuntimeCertificationExecutionJournal.ClaimStatus.COMPLETED);
        assertThat(replay.completedReport()).isEqualTo(report);
        assertThat(replay.savedResults()).containsExactlyElementsOf(results);
    }

    @Test
    void resumesOnlyAfterDatabaseLeaseExpiryAndFencesTheOldEpoch() {
        var first = journal.claimOrResume(identity(), "worker-a", LEASE, Instant.EPOCH);
        RuntimeCertificationReport.ScenarioResult persisted = fixtures.results(null).getFirst();
        journal.appendScenario(first.lease(), persisted);

        assertThat(journal.claimOrResume(identity(), "worker-b", LEASE, Instant.MAX)
                .status()).isEqualTo(RuntimeCertificationExecutionJournal.ClaimStatus.CONFLICT);
        now.set(first.lease().expiresAt());
        DatabaseRuntimeCertificationExecutionJournal restarted = repository();
        restarted.init();
        var resumed = restarted.claimOrResume(identity(), "worker-b", LEASE, Instant.EPOCH);

        assertThat(resumed.status()).isEqualTo(
                RuntimeCertificationExecutionJournal.ClaimStatus.RESUMED);
        assertThat(resumed.lease().epoch()).isEqualTo(first.lease().epoch() + 1);
        assertThat(resumed.savedResults()).containsExactly(persisted);
        assertThatThrownBy(() -> journal.heartbeat(first.lease(), LEASE, Instant.MAX))
                .isInstanceOf(RuntimeCertificationExecutionJournal.LeaseLostException.class);
    }

    @Test
    void oneAuthorizationCannotForkToAnotherRun() {
        journal.claimOrResume(identity(), "worker-a", LEASE, Instant.EPOCH);
        var fork = new RuntimeCertificationExecutionJournal.RunIdentity(
                "runtime-report:fork", fixtures.manifest.artifactRef(),
                fixtures.authorization.artifactRef(),
                fixtures.authorization.nonceFingerprint(),
                fixtures.manifest.environmentFingerprint());

        var result = journal.claimOrResume(fork, "worker-b", LEASE, Instant.MAX);

        assertThat(result.status()).isEqualTo(
                RuntimeCertificationExecutionJournal.ClaimStatus.CONFLICT);
        assertThat(result.reasonCode()).isEqualTo("IDENTITY_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_runtime_certification_runs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void nonceLockAllowsOnlyOneReplicaToAcquire() throws Exception {
        DatabaseRuntimeCertificationExecutionJournal replica = repository();
        replica.init();
        CyclicBarrier start = new CyclicBarrier(2);

        List<RuntimeCertificationExecutionJournal.Claim> claims;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RuntimeCertificationExecutionJournal.Claim> first = executor.submit(() -> {
                start.await();
                return journal.claimOrResume(identity(), "worker-a", LEASE, Instant.EPOCH);
            });
            Future<RuntimeCertificationExecutionJournal.Claim> second = executor.submit(() -> {
                start.await();
                return replica.claimOrResume(identity(), "worker-b", LEASE, Instant.MAX);
            });
            claims = List.of(first.get(), second.get());
        }

        assertThat(claims).filteredOn(value -> value.status()
                        == RuntimeCertificationExecutionJournal.ClaimStatus.ACQUIRED)
                .singleElement();
        assertThat(claims).filteredOn(value -> value.status()
                        == RuntimeCertificationExecutionJournal.ClaimStatus.CONFLICT)
                .singleElement();
    }

    @Test
    void directStorageMutationFailsClosedOnReadAndRestart() {
        var claim = journal.claimOrResume(identity(), "worker-a", LEASE, Instant.EPOCH);
        jdbc.update("""
                UPDATE mirror_runtime_certification_runs
                SET scenario_count = 7
                WHERE run_id = ?
                """, claim.lease().runId());

        assertThatThrownBy(() -> journal.heartbeat(claim.lease(), LEASE, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage is invalid");
        assertThatThrownBy(repository()::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage is invalid");
    }

    private DatabaseRuntimeCertificationExecutionJournal repository() {
        return new DatabaseRuntimeCertificationExecutionJournal(
                jdbc, mapper, transactions, now::get);
    }

    private RuntimeCertificationExecutionJournal.RunIdentity identity() {
        return new RuntimeCertificationExecutionJournal.RunIdentity(
                fixtures.report.reportId(), fixtures.manifest.artifactRef(),
                fixtures.authorization.artifactRef(),
                fixtures.authorization.nonceFingerprint(),
                fixtures.manifest.environmentFingerprint());
    }
}
