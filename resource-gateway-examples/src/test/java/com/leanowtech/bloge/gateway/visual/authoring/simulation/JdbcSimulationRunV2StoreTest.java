package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSimulationRunV2StoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void v2CompletionReplayRestartAndExpiryReuseTheV013Authority() {
        Fixture fixture = fixture("durable");
        Instant started = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = SimulationRunV2StoreTest.run("sim-v2-1", started);

        assertThat(fixture.store().claim(SCOPE, "key", FINGERPRINT, run::runId, started))
                .isEqualTo(new SimulationRunV2Store.Claim.Acquired(run.runId()));
        assertThat(fixture.store().claim(SCOPE, "key", FINGERPRINT, () -> "unused", started))
                .isEqualTo(new SimulationRunV2Store.Claim.Busy(run.runId()));
        fixture.store().complete(SCOPE, "key", FINGERPRINT, run);
        assertThat(fixture.store().claim(SCOPE, "key", FINGERPRINT, () -> "unused", started))
                .isEqualTo(new SimulationRunV2Store.Claim.Replay(run));
        assertThat(store(fixture.jdbc()).find(SCOPE, run.runId())).contains(run);

        Fixture expired = fixture("expired");
        expired.store().claim(SCOPE, "key", FINGERPRINT, run::runId, started);
        expired.jdbc().update("""
                UPDATE rg_authoring_simulation_runs SET lease_until=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND idempotency_key=?
                """, Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), SCOPE.tenantId(),
                SCOPE.projectId(), SCOPE.environmentId(), "key");
        assertThat(expired.store().claim(SCOPE, "key", FINGERPRINT, () -> "other", started))
                .isEqualTo(new SimulationRunV2Store.Claim.Acquired(run.runId()));
    }

    @Test
    void v1EvidenceIsInvisibleToV2ReadsAndStillOwnsTheSharedIdempotencyCoordinate() {
        Fixture fixture = fixture("coexist");
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        JdbcSimulationRunStore v1 = new JdbcSimulationRunStore(fixture.jdbc(),
                transactions(fixture.jdbc()), mapper(), Duration.ofSeconds(30));
        v1.claim(SCOPE, "v1-key", FINGERPRINT, () -> "sim-v1", time);
        v1.complete(SCOPE, "v1-key", FINGERPRINT, new SimulationRun(
                SimulationRun.SCHEMA_VERSION, "sim-v1", SimulationRun.Status.SUCCEEDED,
                new FixtureSubjectRef.ApiResource("orders", 1, "sha256:" + "b".repeat(64)),
                new SimulationRun.FixtureCase("orders:r1", 1, "happy"), null, List.of(),
                new SimulationRun.Verdicts(SimulationRun.ExecutionVerdict.SIMULATED_ONLY,
                        SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED,
                        SimulationRun.Verdict.NOT_CHECKED), List.of(), time, time));

        assertThat(fixture.store().find(SCOPE, "sim-v1")).isEmpty();
        assertThat(fixture.store().claim(SCOPE, "v1-key", "sha256:" + "c".repeat(64),
                () -> "sim-v2", time)).isInstanceOf(SimulationRunV2Store.Claim.Conflict.class);
    }

    @Test
    void persistedRequestFingerprintOrRunIdentityDriftFailsClosed() throws Exception {
        Fixture fixture = fixture("tamper");
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = SimulationRunV2StoreTest.run("sim-v2-1", time);
        fixture.store().claim(SCOPE, "key", FINGERPRINT, run::runId, time);
        fixture.store().complete(SCOPE, "key", FINGERPRINT, run);
        var altered = mapper().valueToTree(run);
        ((com.fasterxml.jackson.databind.node.ObjectNode) altered).put(
                "requestFingerprint", "sha256:" + "f".repeat(64));
        fixture.jdbc().update("""
                UPDATE rg_authoring_simulation_runs SET run_json=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND run_id=?
                """, mapper().writeValueAsString(altered), SCOPE.tenantId(), SCOPE.projectId(),
                SCOPE.environmentId(), run.runId());

        assertThatThrownBy(() -> fixture.store().find(SCOPE, run.runId()))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
    }

    @Test
    void completionRejectsEvidenceFromAnotherRequestWithoutWritingRunJson() {
        Fixture fixture = fixture("completion-drift");
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        SimulationRunV2 run = SimulationRunV2StoreTest.run("sim-v2-1", time);
        String other = "sha256:" + "b".repeat(64);
        fixture.store().claim(SCOPE, "key", other, run::runId, time);

        assertThatThrownBy(() -> fixture.store().complete(SCOPE, "key", other, run))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
        assertThat(fixture.store().find(SCOPE, run.runId())).isEmpty();
    }

    private static Fixture fixture(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:simulation-v2-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new Fixture(jdbc, store(jdbc));
    }

    private static JdbcSimulationRunV2Store store(JdbcTemplate jdbc) {
        return new JdbcSimulationRunV2Store(jdbc, transactions(jdbc), mapper(), Duration.ofSeconds(30));
    }

    private static TransactionTemplate transactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }

    private record Fixture(JdbcTemplate jdbc, JdbcSimulationRunV2Store store) { }
}
