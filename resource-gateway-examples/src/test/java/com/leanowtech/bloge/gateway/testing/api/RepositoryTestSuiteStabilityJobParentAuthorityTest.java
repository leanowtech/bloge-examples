package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryTestSuiteStabilityJobParentAuthorityTest {

    private ObjectMapper mapper;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseTestSuiteStabilityRunRepository runs;
    private DatabaseTestSuiteStabilityJobRepository jobs;
    private TestSuiteStabilityAttestationService attestations;
    private RepositoryTestSuiteStabilityJobParentAuthority authority;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-stability-parent-authority-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        runs = new DatabaseTestSuiteStabilityRunRepository(jdbc, mapper);
        runs.init();
        attestations = new TestSuiteStabilityAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        authority = new RepositoryTestSuiteStabilityJobParentAuthority(
                runs, mapper, attestations);
        jobs = new DatabaseTestSuiteStabilityJobRepository(
                jdbc, mapper, authority, new DataSourceTransactionManager(dataSource));
        jobs.init();
    }

    @Test
    void parentStopIsDurableIdempotentAndBlocksFutureSynchronousClaim() {
        TestSuiteStabilityJobRecord job = job();
        Duration retention = Duration.ofDays(30);

        TestSuiteStabilityJobParentAuthority.Resolution first = authority.stop(
                job, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_JOB_CANCELLED", retention);
        TestSuiteStabilityJobParentAuthority.Resolution replay = authority.stop(
                job, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_JOB_CANCELLED", retention);

        assertThat(first).isEqualTo(TestSuiteStabilityJobParentAuthority.Resolution.stopped());
        assertThat(replay).isEqualTo(first);
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(mapper, job);
        assertThat(runs.findStop(
                job.tenantId(), job.environmentId(), execution.stabilityRunId()))
                .get().satisfies(stop -> {
                    assertThat(stop.clientRequestId())
                            .isEqualTo(job.request().clientRequestId());
                    assertThat(stop.requestFingerprint()).isEqualTo(job.requestFingerprint());
                    assertThat(stop.reason())
                            .isEqualTo(TestSuiteStabilityExecutionStop.Reason.CANCELLED);
                    assertThat(stop.actorId()).isEqualTo("stability-job-control");
                });

        TestSuiteStabilityLeaseClaim claim = runs.claim(new TestSuiteStabilityLeaseRequest(
                execution.stabilityRunId(), execution.tenantId(), execution.environmentId(),
                execution.clientRequestId(), execution.requestFingerprint(),
                job.request().suiteRef(), execution.classification(),
                job.request().attempts(), "sync-runner", Duration.ofSeconds(30),
                Duration.ofDays(30)));
        assertThat(claim.state()).isEqualTo(TestSuiteStabilityLeaseClaim.State.STOPPED);
    }

    @Test
    void cryptographicallyVerifiedParentCompletionWinsOverALaterStop() throws Exception {
        TestSuiteStabilityJobRecord job = job();
        TestSuiteStabilityRunRecord terminal = persistTerminal(job);

        TestSuiteStabilityJobParentAuthority.Resolution proof = authority.requireCompleted(
                job, terminal.stabilityRunId(), terminal.evidenceFingerprint());
        TestSuiteStabilityJobParentAuthority.Resolution resolution = authority.stop(
                job, TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                "RG.TEST.STABILITY_JOB_CANCELLED", Duration.ofDays(30));

        assertThat(proof).isEqualTo(TestSuiteStabilityJobParentAuthority.Resolution.completed(
                terminal.stabilityRunId(), terminal.evidenceFingerprint()));
        assertThat(resolution).isEqualTo(TestSuiteStabilityJobParentAuthority.Resolution.completed(
                terminal.stabilityRunId(), terminal.evidenceFingerprint()));
        assertThat(runs.findStop(job.tenantId(), job.environmentId(),
                terminal.stabilityRunId())).isEmpty();
    }

    @Test
    void corruptedParentSignatureCannotWinQueueTerminalArbitration() throws Exception {
        TestSuiteStabilityJobRecord job = job();
        TestSuiteStabilityRunRecord terminal = persistTerminal(job);
        String storedJson = jdbc.queryForObject("""
                SELECT record_json
                FROM rg_test_suite_stability_records
                WHERE stability_run_id = ?
                """, String.class, terminal.stabilityRunId());
        ObjectNode stored = (ObjectNode) mapper.readTree(storedJson);
        ((ObjectNode) stored.get("attestation")).put("signature", "AAAA");
        jdbc.update("""
                UPDATE rg_test_suite_stability_records
                SET record_json = ?
                WHERE stability_run_id = ?
                """, mapper.writeValueAsString(stored), terminal.stabilityRunId());

        assertThatThrownBy(() -> authority.requireCompleted(
                job, terminal.stabilityRunId(), terminal.evidenceFingerprint()))
                .isInstanceOf(TestSuiteStabilityRunConflictException.class)
                .hasMessageContaining(
                        "Signed parent winner contradicts the durable stability job");
    }

    @Test
    void missingOrMismatchedParentCannotAuthorizeQueueSuccess() {
        TestSuiteStabilityJobRecord job = job();
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(mapper, job);

        assertThatThrownBy(() -> authority.requireCompleted(
                job, execution.stabilityRunId(),
                TestSuiteStabilityProtocolFixtures.fingerprint('7')))
                .isInstanceOf(TestSuiteStabilityRunConflictException.class)
                .hasMessageContaining("not durably available");
        assertThatThrownBy(() -> authority.requireCompleted(
                job, "stability-" + "2".repeat(64),
                TestSuiteStabilityProtocolFixtures.fingerprint('7')))
                .isInstanceOf(TestSuiteStabilityRunConflictException.class)
                .hasMessageContaining("deterministic parent run");
        assertThatThrownBy(() -> authority.requireCompleted(
                job, execution.stabilityRunId(), ""))
                .isInstanceOf(TestSuiteStabilityRunConflictException.class)
                .hasMessageContaining("canonical parent evidence");
    }

    @Test
    void queueCompletionRequiresAndThenConsumesARealSignedParentProof() throws Exception {
        TestSuiteStabilityJobRecord source = job();
        TestSuiteStabilityQueuePolicy policy = policy();
        jobs.submit(new TestSuiteStabilityJobSubmission(
                source.jobId(), source.request(), source.requestFingerprint(),
                source.classification(), source.principal(), source.priority(),
                Instant.now().plus(Duration.ofHours(1))), policy);
        TestSuiteStabilityJobClaim claim = jobs.claimNext("test", "worker-a", policy);
        TestSuiteStabilityJobLease committing =
                jobs.prepareCompletion(claim.lease(), policy).lease();
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(mapper, source);

        assertThatThrownBy(() -> jobs.complete(
                committing, execution.stabilityRunId(),
                TestSuiteStabilityProtocolFixtures.fingerprint('7'), policy))
                .isInstanceOfSatisfying(TestSuiteStabilityJobConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT));
        assertThat(jobs.find(source.tenantId(), source.environmentId(), source.jobId()))
                .get().extracting(TestSuiteStabilityJobRecord::status)
                .isEqualTo(TestSuiteStabilityJobRecord.Status.COMMITTING);

        TestSuiteStabilityRunRecord terminal = persistTerminal(source);
        TestSuiteStabilityJobRecord completed = jobs.complete(
                committing, terminal.stabilityRunId(), terminal.evidenceFingerprint(), policy);

        assertThat(completed.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.SUCCEEDED);
        assertThat(completed.terminalStabilityRunId()).isEqualTo(terminal.stabilityRunId());
        assertThat(completed.terminalEvidenceFingerprint())
                .isEqualTo(terminal.evidenceFingerprint());
    }

    private TestSuiteStabilityRunRecord persistTerminal(
            TestSuiteStabilityJobRecord job) throws Exception {
        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(mapper, job);
        ObjectNode evidenceJson = mapper.valueToTree(
                TestSuiteStabilityProtocolFixtures.stableEvidence());
        evidenceJson.put("stabilityRunId", execution.stabilityRunId());
        evidenceJson.put("clientRequestId", execution.clientRequestId());
        TestSuiteStabilityEvidence evidence = mapper.treeToValue(
                evidenceJson, TestSuiteStabilityEvidence.class);
        var seal = attestations.seal(evidence, execution.requestFingerprint());
        TestSuiteStabilityJobPrincipal principal = job.principal();
        Instant createdAt = Instant.now();
        TestSuiteStabilityRunRecord terminal = new TestSuiteStabilityRunRecord(
                execution.stabilityRunId(), execution.clientRequestId(),
                execution.requestFingerprint(), execution.tenantId(),
                principal.organizationId(), principal.projectId(), execution.environmentId(),
                principal.actorId(), execution.classification(),
                seal.attestation().evidenceFingerprint(), evidence, seal.attestation(),
                createdAt, createdAt.plus(Duration.ofDays(30)));
        TestSuiteStabilityLeaseClaim claim = runs.claim(new TestSuiteStabilityLeaseRequest(
                execution.stabilityRunId(), execution.tenantId(), execution.environmentId(),
                execution.clientRequestId(), execution.requestFingerprint(), evidence.suiteRef(),
                execution.classification(), evidence.requestedAttempts(), "test-worker",
                Duration.ofSeconds(30), Duration.ofDays(30)));
        TestSuiteStabilityExecutionLease lease = claim.lease();
        for (TestSuiteStabilityEvidence.AttemptResult attempt : evidence.attempts()) {
            lease = runs.checkpoint(lease,
                    new TestSuiteStabilityExecutionProgress.AttemptReference(
                            attempt.attempt(), attempt.suiteRunId(),
                            attempt.aggregateEvidenceFingerprint()),
                    Duration.ofSeconds(30), Duration.ofDays(30)).lease();
        }
        return runs.complete(terminal, lease);
    }

    private static TestSuiteStabilityJobRecord job() {
        Instant now = Instant.now();
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                "parent-authority-request", 3, Map.of("pipeline", "nightly"));
        TestSuiteStabilityJobPrincipal principal = new TestSuiteStabilityJobPrincipal(
                "tenant-a", "org-a", "project-a", "test", "sg-1", "SERVICE",
                "ci-runner", "", "TEST_EXECUTION", "correlation-1",
                Set.of("test-runners"), "INTERNAL", "");
        return new TestSuiteStabilityJobRecord(
                "stability-job-" + "1".repeat(64), request,
                TestSuiteStabilityProtocolFixtures.fingerprint('9'), "INTERNAL", principal,
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                TestSuiteStabilityJobRecord.Status.RUNNING, 0, now,
                now.plus(Duration.ofHours(1)), now, now, now.plus(Duration.ofDays(31)),
                "", "", "", "", "",
                TestSuiteStabilityProtocolFixtures.fingerprint('8'));
    }

    private static TestSuiteStabilityQueuePolicy policy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 10, 10, 2, 1, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3,
                Duration.ofDays(7), Duration.ofDays(30));
    }
}
