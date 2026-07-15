package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseReplayPayloadRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSecurityEventRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.ConfiguredVisualPayloadGovernancePolicy;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.DatabaseVisualRunPayloadRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReplayPayloadServiceTest {

    private TestReplayPayloadService service;
    private DatabaseVisualGraphRunRepository sourceRuns;
    private DatabaseTestSecurityEventRepository securityEvents;
    private VisualGraphRunRecord source;

    @BeforeEach
    void setUp() {
        var sourceDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:replay-source-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DatabaseVisualEvidenceSigner signer = new DatabaseVisualEvidenceSigner(sourceJdbc);
        var policy = new ConfiguredVisualPayloadGovernancePolicy(
                "replay-source-policy", "1", "CONFIDENTIAL", Set.of("quality"),
                Map.of("CONFIDENTIAL", Duration.ofDays(7)));
        DatabaseVisualRunPayloadRepository sourcePayloads = new DatabaseVisualRunPayloadRepository(
                sourceJdbc, mapper, policy, signer);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sourcePayloads, "init");
        sourceRuns = new DatabaseVisualGraphRunRepository(sourceJdbc, mapper, signer, null, sourcePayloads);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(sourceRuns, "init");
        source = sourceRuns.create(sourceRecord("run-source-1", "SUCCESS", normalOutput()));

        var testDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:replay-test-vault-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate testJdbc = new JdbcTemplate(testDataSource);
        DatabaseReplayPayloadRepository replayPayloads = new DatabaseReplayPayloadRepository(testJdbc, mapper);
        replayPayloads.init();
        securityEvents = new DatabaseTestSecurityEventRepository(testJdbc, mapper);
        securityEvents.init();
        service = new TestReplayPayloadService(sourceRuns, replayPayloads, securityEvents,
                mapper, Duration.ofDays(30));
    }

    @Test
    void capturesExactSignedAttemptWithSecondSanitizationAndImmutableLineage() {
        ReplayPayloadCaptureRequest request = request(source, "CONFIDENTIAL", 1,
                source.payloadRetention().expiresAt().minusSeconds(1));

        StoredReplayPayload captured = service.capture("orders-approved", request, replayIdentity());

        assertThat(captured.readable()).isTrue();
        assertThat(captured.descriptor().reference().canonical())
                .startsWith("bloge-replay:orders-approved@1#sha256:");
        assertThat(captured.descriptor().source().runId()).isEqualTo(source.runId());
        assertThat(captured.descriptor().source().nodeId()).isEqualTo("fetchOrder");
        assertThat(captured.descriptor().source().attempt()).isEqualTo(1);
        assertThat(captured.descriptor().source().runEvidenceFingerprint())
                .isEqualTo(source.evidenceMaterialFingerprint());
        assertThat(captured.descriptor().source().sourcePayloadFingerprint())
                .isEqualTo(source.payloadRetention().payloadFingerprint());
        assertThat(captured.descriptor().redaction().sourceRedactedCount()).isPositive();
        assertThat(captured.descriptor().redaction().captureRedactedCount()).isPositive();
        assertThat(captured.descriptor().certificationEligible()).isFalse();
        assertThat(captured.descriptor().certificationGaps())
                .contains("SOURCE_NOT_IMMUTABLE_PUBLICATION_RUN");
        assertThat(captured.value()).isEqualTo(Map.of(
                "orderId", "O-1", "decision", "approved", "apiToken", "[REDACTED]"));
        assertThat(service.find("orders-approved", 1, replayIdentity())).isEqualTo(captured);
    }

    @Test
    void rejectsPurposeScopeAttemptFingerprintAndClassificationViolations() {
        ReplayPayloadCaptureRequest valid = request(source, "CONFIDENTIAL", 1,
                source.payloadRetention().expiresAt().minusSeconds(1));

        assertProblem(() -> service.capture("orders-approved", valid,
                identity("TEST_EXECUTION", "test", Set.of("quality"), "RESTRICTED")),
                403, "RG.TEST.REPLAY_PURPOSE_REQUIRED");
        assertProblem(() -> service.capture("orders-approved", valid,
                identity("TEST_REPLAY", "staging", Set.of("quality"), "RESTRICTED")),
                404, "RG.TEST.REPLAY_SOURCE_NOT_FOUND");
        assertProblem(() -> service.capture("orders-approved",
                        request(source, "CONFIDENTIAL", 2,
                                source.payloadRetention().expiresAt().minusSeconds(1)), replayIdentity()),
                409, "RG.TEST.REPLAY_SOURCE_ATTEMPT_INVALID");

        ReplayPayloadCaptureRequest stale = new ReplayPayloadCaptureRequest("", 1,
                new ReplayPayloadCaptureRequest.Source(source.runId(), "fetchOrder", 1,
                        fingerprint('a'), source.payloadRetention().payloadFingerprint()),
                "CONFIDENTIAL", source.payloadRetention().expiresAt().minusSeconds(1));
        assertProblem(() -> service.capture("orders-approved", stale, replayIdentity()),
                409, "RG.TEST.REPLAY_SOURCE_FINGERPRINT_CONFLICT");
        assertProblem(() -> service.capture("orders-approved",
                        request(source, "INTERNAL", 1,
                                source.payloadRetention().expiresAt().minusSeconds(1)), replayIdentity()),
                400, "RG.TEST.REPLAY_CLASSIFICATION_DOWNGRADE");
        assertProblem(() -> service.capture("orders-approved", valid,
                        identity("TEST_REPLAY", "test", Set.of(), "RESTRICTED")),
                403, "RG.TEST.REPLAY_SOURCE_GROUP_REQUIRED");
    }

    @Test
    void rejectsFailedOrTruncatedSourceInsteadOfCreatingLowFidelityReplayData() {
        VisualGraphRunRecord failed = sourceRuns.create(
                sourceRecord("run-failed-attempt", "FAILED", Map.of("error", "upstream")));
        assertProblem(() -> service.capture("failed-source",
                        request(failed, "CONFIDENTIAL", 1,
                                failed.payloadRetention().expiresAt().minusSeconds(1)), replayIdentity()),
                409, "RG.TEST.REPLAY_SOURCE_ATTEMPT_INVALID");

        Map<String, Object> oversized = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 101; i++) {
            oversized.put("field" + i, i);
        }
        VisualGraphRunRecord truncated = sourceRuns.create(
                sourceRecord("run-truncated", "SUCCESS", oversized));
        assertProblem(() -> service.capture("truncated-source",
                        request(truncated, "CONFIDENTIAL", 1,
                                truncated.payloadRetention().expiresAt().minusSeconds(1)), replayIdentity()),
                409, "RG.TEST.REPLAY_SOURCE_TRUNCATED");
    }

    private static ReplayPayloadCaptureRequest request(VisualGraphRunRecord run,
                                                       String classification,
                                                       int attempt,
                                                       Instant expiresAt) {
        return new ReplayPayloadCaptureRequest("", 1,
                new ReplayPayloadCaptureRequest.Source(run.runId(), "fetchOrder", attempt,
                        run.evidenceMaterialFingerprint(), run.payloadRetention().payloadFingerprint()),
                classification, expiresAt);
    }

    private static VisualGraphRunRecord sourceRecord(String runId, String attemptStatus, Object output) {
        GraphDraft draft = new GraphDraft("", "draft-1", 1, "orderReplay", "tenant-a", "local", "test",
                "", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("fetchOrder", ""));
        VisualNodeExecutionAttempt attempt = new VisualNodeExecutionAttempt(
                1, Map.of("orderId", "O-1"), output, attemptStatus,
                Instant.parse("2026-07-16T00:00:00Z"), 4,
                "SUCCESS".equals(attemptStatus) ? "" : "UPSTREAM_FAILURE", "");
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, "orderReplay", "fetchOrder", output,
                Map.of("fetchOrder", output), Map.of("fetchOrder", "COMPLETED"), 5,
                Map.of("fetchOrder", 4L), List.of(), List.of(), null, null,
                "graph orderReplay {}", null, "", Map.of("fetchOrder", List.of(attempt)), Map.of());
        return VisualGraphRunRecord.storedDraft(draft,
                Map.of("orderId", "O-1", "authorization", "Bearer source-secret"), response)
                .withIdentity(runId, Instant.now());
    }

    private static Map<String, Object> normalOutput() {
        return Map.of("orderId", "O-1", "decision", "approved", "apiToken", "source-secret");
    }

    private static IntegrationRequestContext replayIdentity() {
        return identity("TEST_REPLAY", "test", Set.of("quality"), "RESTRICTED");
    }

    private static IntegrationRequestContext identity(String purpose, String environment,
                                                      Set<String> groups, String clearance) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", environment,
                "local", "WORKLOAD", "replay-runner", "", purpose, "correlation-1",
                groups, clearance, "");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(status);
                    assertThat(problem.code()).isEqualTo(code);
                    assertThat(problem.details().toString()).doesNotContain("source-secret");
                });
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
