package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimePersistenceTest {

    private ObjectMapper mapper;
    private DatabaseFixtureBundleRepository fixtures;
    private DatabaseTestRunRepository runs;
    private DatabaseTestSecurityEventRepository securityEvents;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-runtime-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        fixtures = new DatabaseFixtureBundleRepository(jdbc, mapper);
        runs = new DatabaseTestRunRepository(jdbc, mapper);
        securityEvents = new DatabaseTestSecurityEventRepository(jdbc, mapper);
        fixtures.init();
        runs.init();
        securityEvents.init();
    }

    @Test
    void immutableFixtureRevisionSurvivesRepositoryReconstructionAndRejectsConflict() {
        FixtureBundle bundle = new FixtureBundle("", "fixture-a", 2, "sha256:target",
                "INTERNAL", null, null, List.of(), List.of(), Map.of("owner", "quality"));
        StoredFixtureBundle stored = new StoredFixtureBundle("", "tenant-a", "test", "fixture-a", 2,
                "sha256:fixture-a", bundle, Instant.now(), "runner");

        fixtures.create(stored);

        assertThat(fixtures.find("tenant-a", "test", "fixture-a", 2)).contains(stored);
        assertThat(fixtures.find("tenant-b", "test", "fixture-a", 2)).isEmpty();
        StoredFixtureBundle conflict = new StoredFixtureBundle("", "tenant-a", "test", "fixture-a", 2,
                "sha256:different", bundle, stored.createdAt(), "runner");
        assertThatThrownBy(() -> fixtures.create(conflict))
                .isInstanceOf(FixtureBundleConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void terminalEvidenceRoundTripsAndLookupAlwaysAppliesScope() {
        Instant now = Instant.now();
        TestRunEvidence evidence = new TestRunEvidence("", "run-1", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", "sha256:target",
                "sha256:fixture", "sha256:plan", now, now, List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of("payloadSanitized", true));
        TestRunRecord record = new TestRunRecord("run-1", "tenant-a", "org-a", "project-a", "test",
                "runner", new TestExecutionApiRequest.Target("GRAPH", "graph-a", "sha256:target"),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", "fixture-a", 2,
                        "sha256:fixture"), TestExecutionApiRequest.Verbosity.FULL, null, evidence,
                now, now.plusSeconds(3600));

        runs.create(record);

        assertThat(runs.find("tenant-a", "test", "run-1")).contains(record);
        assertThat(runs.find("tenant-b", "test", "run-1")).isEmpty();
        assertThat(runs.find("tenant-a", "prod", "run-1")).isEmpty();
    }

    @Test
    void securityEventsAreAppendOnlyAndCredentialFree() {
        TestSecurityEvent event = new TestSecurityEvent(0, Instant.now(), "correlation-1", "tenant-a",
                "prod", "runner", "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of("endpoint", "/api/testing/executions"));

        TestSecurityEvent stored = securityEvents.append(event);

        assertThat(stored.sequence()).isPositive();
        assertThat(securityEvents.recent(10)).containsExactly(stored);
        assertThat(mapper.valueToTree(stored).toString())
                .doesNotContain("credential", "authorization", "token");
    }
}
