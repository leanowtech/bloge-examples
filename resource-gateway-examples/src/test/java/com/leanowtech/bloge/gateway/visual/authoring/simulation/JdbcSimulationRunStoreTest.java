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

class JdbcSimulationRunStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void claimCompletionReplayAndScopedReadAreDurable() {
        Fixture fixture = fixture("durable");
        JdbcSimulationRunStore store = fixture.store();
        Instant started = Instant.parse("2030-01-01T00:00:00Z");

        SimulationRunStore.Claim first = store.claim(SCOPE, "key", FINGERPRINT, () -> "sim-1", started);
        SimulationRunStore.Claim busy = store.claim(SCOPE, "key", FINGERPRINT, () -> "unused", started);
        SimulationRun run = run("sim-1", started);
        store.complete(SCOPE, "key", FINGERPRINT, run);
        SimulationRunStore.Claim replay = store.claim(SCOPE, "key", FINGERPRINT, () -> "unused", started);

        assertThat(first).isEqualTo(new SimulationRunStore.Claim.Acquired("sim-1"));
        assertThat(busy).isEqualTo(new SimulationRunStore.Claim.Busy("sim-1"));
        assertThat(replay).isEqualTo(new SimulationRunStore.Claim.Replay(run));
        assertThat(store.find(SCOPE, "sim-1")).contains(run);
        assertThat(store.find(new AuthoringScope("other", "project", "dev"), "sim-1")).isEmpty();
        JdbcSimulationRunStore reopened = store(fixture.jdbc());
        assertThat(reopened.find(SCOPE, "sim-1")).contains(run);
    }

    @Test
    void changedFingerprintConflictsAndExpiredRunIsResumedWithItsOriginalId() {
        Fixture fixture = fixture("resume");
        JdbcSimulationRunStore store = fixture.store();
        Instant started = Instant.parse("2030-01-01T00:00:00Z");
        store.claim(SCOPE, "key", FINGERPRINT, () -> "sim-1", started);

        assertThat(store.claim(SCOPE, "key", "sha256:" + "b".repeat(64),
                () -> "sim-2", started)).isInstanceOf(SimulationRunStore.Claim.Conflict.class);

        fixture.jdbc().update("""
                UPDATE rg_authoring_simulation_runs
                   SET lease_until=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND idempotency_key=?
                """, Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), SCOPE.tenantId(),
                SCOPE.projectId(), SCOPE.environmentId(), "key");
        assertThat(store.claim(SCOPE, "key", FINGERPRINT, () -> "sim-2", started))
                .isEqualTo(new SimulationRunStore.Claim.Acquired("sim-1"));
    }

    @Test
    void persistedRunIdentityDriftFailsClosed() {
        Fixture fixture = fixture("tamper");
        Instant started = Instant.parse("2030-01-01T00:00:00Z");
        fixture.store().claim(SCOPE, "key", FINGERPRINT, () -> "sim-1", started);
        fixture.store().complete(SCOPE, "key", FINGERPRINT, run("sim-1", started));
        String altered = new ObjectMapper().findAndRegisterModules().valueToTree(run("sim-other", started))
                .toString();
        fixture.jdbc().update("""
                UPDATE rg_authoring_simulation_runs SET run_json=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND run_id=?
                """, altered, SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), "sim-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.store().find(SCOPE, "sim-1"))
                .isInstanceOf(SimulationFailure.class)
                .extracting(value -> ((SimulationFailure) value).code())
                .isEqualTo(SimulationFailure.Code.INTEGRITY);
    }

    private static SimulationRun run(String id, Instant time) {
        return new SimulationRun(SimulationRun.SCHEMA_VERSION, id, SimulationRun.Status.SUCCEEDED,
                new FixtureSubjectRef.ApiResource("orders", 1, "sha256:" + "c".repeat(64)),
                new SimulationRun.FixtureCase("orders:r1", 1, "happy"), null, List.of(),
                new SimulationRun.Verdicts(SimulationRun.ExecutionVerdict.SIMULATED_ONLY,
                        SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED,
                        SimulationRun.Verdict.NOT_CHECKED), List.of(), time, time);
    }

    private static Fixture fixture(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:simulation-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new Fixture(jdbc, store(jdbc));
    }

    private static JdbcSimulationRunStore store(JdbcTemplate jdbc) {
        return new JdbcSimulationRunStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                new ObjectMapper().findAndRegisterModules(), Duration.ofSeconds(30));
    }

    private record Fixture(JdbcTemplate jdbc, JdbcSimulationRunStore store) { }
}
