package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
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
    private DatabaseTestSuiteRepository suites;
    private DatabaseTestSuiteRunRepository suiteRuns;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-runtime-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        fixtures = new DatabaseFixtureBundleRepository(jdbc, mapper);
        runs = new DatabaseTestRunRepository(jdbc, mapper);
        securityEvents = new DatabaseTestSecurityEventRepository(jdbc, mapper);
        suites = new DatabaseTestSuiteRepository(jdbc, mapper);
        suiteRuns = new DatabaseTestSuiteRunRepository(jdbc, mapper);
        fixtures.init();
        runs.init();
        securityEvents.init();
        suites.init();
        suiteRuns.init();
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
    void immutableSuiteRevisionRoundTripsWithPolicyAndRejectsCrossScopeOrOverwrite() {
        String target = "sha256:" + "a".repeat(64);
        String fixture = "sha256:" + "b".repeat(64);
        TestSuite suite = new TestSuite("", "suite-a", 3,
                new TestSuite.Target("GRAPH", "graph-a", target), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                        Map.of("orderId", "O-1"), new TestSuite.FixtureBundleRef(
                        "fixture-a", 2, fixture), List.of("ci"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of("/root/fetch#PRIMARY"), List.of(new TestSuite.EdgeTransferRef(
                        "/root/fetch#PRIMARY", "/root/output#PRIMARY")), 1, true),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of("owner", "quality"));
        StoredTestSuite stored = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                "sha256:" + "c".repeat(64), suite, Instant.now(), "runner");

        suites.create(stored);

        assertThat(suites.find("tenant-a", "test", "suite-a", 3)).contains(stored);
        assertThat(suites.find("tenant-b", "test", "suite-a", 3)).isEmpty();
        assertThat(suites.find("tenant-a", "staging", "suite-a", 3)).isEmpty();
        StoredTestSuite conflict = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                "sha256:" + "d".repeat(64), suite, stored.createdAt(), "runner");
        assertThatThrownBy(() -> suites.create(conflict))
                .isInstanceOf(TestSuiteConflictException.class)
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
    void suiteRunCheckpointsAreScopedRecoverableAndDatabaseIdempotent() {
        Instant now = Instant.now();
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "b".repeat(64));
        TestSuiteRunEvidence running = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.RUNNING, "TEST_SUITE_EXECUTION", suiteRef, target,
                now, null, List.of(), TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of());
        TestSuiteRunRecord initial = new TestSuiteRunRecord("suite-run-1", "request-1",
                "sha256:" + "c".repeat(64), "tenant-a", "org-a", "project-a", "test",
                "runner", "INTERNAL", "", running, now, now.plusSeconds(3600));

        suiteRuns.create(initial);

        assertThat(suiteRuns.find("tenant-a", "test", "suite-run-1")).contains(initial);
        assertThat(suiteRuns.findByClientRequestId("tenant-a", "test", "request-1"))
                .contains(initial);
        assertThat(suiteRuns.find("tenant-b", "test", "suite-run-1")).isEmpty();
        TestSuiteRunEvidence terminal = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.PASSED, "TEST_SUITE_EXECUTION", suiteRef, target,
                now, now.plusSeconds(1), List.of(), new TestSuiteRunEvidence.CoverageVerdict(
                TestSuiteRunEvidence.CoverageStatus.SATISFIED, 0, 0, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0,
                List.of(), List.of(), true), new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true, 0, 0,
                true, true, true), List.of(), Map.of());
        TestSuiteRunRecord completed = new TestSuiteRunRecord("suite-run-1", "request-1",
                initial.requestFingerprint(), "tenant-a", "org-a", "project-a", "test", "runner",
                "INTERNAL", "sha256:" + "d".repeat(64), terminal, now, now.plusSeconds(3600));

        suiteRuns.update(completed);

        assertThat(suiteRuns.find("tenant-a", "test", "suite-run-1")).contains(completed);
        TestSuiteRunRecord duplicate = new TestSuiteRunRecord("suite-run-2", "request-1",
                "sha256:" + "e".repeat(64), "tenant-a", "org-a", "project-a", "test", "runner",
                "INTERNAL", "", running, now, now.plusSeconds(3600));
        assertThatThrownBy(() -> suiteRuns.create(duplicate))
                .isInstanceOf(TestSuiteRunConflictException.class);
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
