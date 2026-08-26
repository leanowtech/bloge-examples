package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestRunControlEvidenceProjection;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlEvidenceBinding;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlMode;
import com.leanowtech.bloge.gateway.testing.function.FunctionControlRunEvidence;
import com.leanowtech.bloge.gateway.testing.function.FunctionEvidenceCeiling;
import com.leanowtech.bloge.gateway.testing.function.FunctionInvocationSite;
import com.leanowtech.bloge.gateway.testing.evidence.TestRunEvidenceProtocolCodec;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRunRecordIntegrityTest {

    private static final String TARGET_FINGERPRINT = "sha256:" + "1".repeat(64);
    private static final String FIXTURE_FINGERPRINT = "sha256:" + "2".repeat(64);
    private static final String PLAN_FINGERPRINT = "sha256:" + "3".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestEvidenceIntegrityService integrity = new TestEvidenceIntegrityService(
            mapper, new InMemoryVisualEvidenceSigner());

    @Test
    void canonicalCreateSnapshotBindsScopeDependenciesAndSignedEvidence() {
        MutableValue input = new MutableValue("approved");
        TestRunRecord submitted = record("tenant-a", evidence("run-a", input, "tenant-a"), null);

        TestRunRecord snapshot = TestRunRecordIntegrity.verifiedCreateSnapshot(
                mapper, integrity, submitted);
        input.status = "denied";

        assertThat(snapshot).isNotSameAs(submitted);
        assertThat(snapshot.evidence().nodeTrace().getFirst().input())
                .isEqualTo(Map.of("status", "approved"));
        assertThat(snapshot.integrity().evidenceFingerprint())
                .isEqualTo(integrity.seal(snapshot.evidence()).integrity().evidenceFingerprint());
    }

    @Test
    void rejectsForgedVerifiedManifestAndCrossTenantEnvelopeWithoutPayloadEcho() {
        TestRunEvidence signed = evidence("run-a", Map.of("credential", "must-never-escape-83"),
                "tenant-a");
        TestEvidenceIntegrity manifest = integrity.seal(signed).integrity();
        TestRunEvidence changed = evidence("run-a", Map.of("credential", "tampered-value-41"),
                "tenant-a");

        assertPayloadFreeFailure(record("tenant-a", changed, manifest));
        assertPayloadFreeFailure(record("tenant-b", signed, manifest));
    }

    @Test
    void rejectsRepositorySubstitutionOutsideCompleteLookupKey() {
        TestRunRecord stored = record("tenant-a", evidence("run-a", Map.of(), "tenant-a"), null);

        assertThatThrownBy(() -> TestRunRecordIntegrity.verifiedSnapshot(
                mapper, integrity, stored, "tenant-b", "test", "run-a"))
                .isInstanceOf(TestRunIntegrityException.class)
                .hasMessage("Stored test-run integrity verification failed")
                .hasMessageNotContaining("tenant-b")
                .hasMessageNotContaining("run-a");
    }

    @Test
    void rejectsSubstitutedCreateReceipt() {
        TestRunRecord expected = record("tenant-a", evidence("run-a", Map.of(), "tenant-a"), null);
        TestRunRecord substituted = new TestRunRecord(expected.runId(), "tenant-b",
                expected.organizationId(), expected.projectId(), expected.environmentId(),
                expected.actorId(), expected.target(), expected.fixtureBundleRef(),
                expected.requestedVerbosity(), expected.plan(), expected.evidence(),
                expected.integrity(), expected.createdAt(), expected.expiresAt());

        assertThatThrownBy(() -> TestRunRecordIntegrity.verifiedCreateReceipt(
                mapper, integrity, substituted, expected))
                .isInstanceOf(TestRunIntegrityException.class)
                .hasMessage("Stored test-run integrity verification failed")
                .hasMessageNotContaining("tenant-a")
                .hasMessageNotContaining("tenant-b");
    }

    @Test
    void rejectsCrossTargetControlProjectionAtIntegrityBoundary() {
        TestRunEvidence base = evidence("run-a", Map.of(), "tenant-a");
        FunctionInvocationSite site = new FunctionInvocationSite("/root", "node", "f", 1, 1);
        FunctionControlRunEvidence functionEvidence = new FunctionControlRunEvidence(
                PLAN_FINGERPRINT, FunctionEvidenceCeiling.CERTIFIABLE,
                List.of(new FunctionControlEvidenceBinding(site, PLAN_FINGERPRINT,
                        PLAN_FINGERPRINT, FunctionControlMode.CONTROLLED,
                        FunctionEvidenceCeiling.CERTIFIABLE, "")), List.of(), List.of(),
                PLAN_FINGERPRINT);
        TestRunControlEvidenceProjection foreign = TestRunControlEvidenceProjection.from(
                "run-a", "", "", "sha256:" + "9".repeat(64), PLAN_FINGERPRINT,
                PLAN_FINGERPRINT, null, functionEvidence);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.metadata());
        metadata.put(TestRunEvidenceProtocolCodec.CONTROL_PROJECTION_METADATA_KEY, foreign);
        TestRunEvidence forged = TestSemanticResultFingerprint.attach(mapper,
                new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, base.runId(), base.status(),
                        base.evidenceClass(), base.executionPurpose(), base.targetFingerprint(),
                        base.fixtureBundleFingerprint(), base.planFingerprint(), base.startedAt(),
                        base.completedAt(), base.nodeTrace(), base.edgeTrace(),
                        base.fixtureConsumptions(), base.assertionResults(), base.diagnostics(), metadata));

        assertThatThrownBy(() -> TestRunRecordIntegrity.verifiedSnapshot(
                mapper, integrity, record("tenant-a", forged, null), "tenant-a", "test", "run-a"))
                .isInstanceOf(TestRunIntegrityException.class)
                .hasMessage("Stored test-run integrity verification failed");
    }

    private void assertPayloadFreeFailure(TestRunRecord record) {
        assertThatThrownBy(() -> TestRunRecordIntegrity.verifiedCreateSnapshot(
                mapper, integrity, record))
                .isInstanceOf(TestRunIntegrityException.class)
                .hasMessage("Stored test-run integrity verification failed")
                .hasMessageNotContaining("must-never-escape-83")
                .hasMessageNotContaining("tampered-value-41")
                .hasMessageNotContaining("tenant-a")
                .hasMessageNotContaining("run-a");
    }

    private TestRunRecord record(String tenantId, TestRunEvidence evidence,
                                 TestEvidenceIntegrity suppliedIntegrity) {
        TestEvidenceIntegrity manifest = suppliedIntegrity == null
                ? integrity.seal(evidence).integrity() : suppliedIntegrity;
        Instant completedAt = evidence.completedAt();
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan("", "plan-a", PLAN_FINGERPRINT,
                "GRAPH_CONTRACT_TEST", TARGET_FINGERPRINT, FIXTURE_FINGERPRINT,
                List.of(), List.of(), Map.of(), List.of());
        return new TestRunRecord(evidence.runId(), tenantId, "org-a", "project-a", "test",
                "runner", new TestExecutionApiRequest.Target("GRAPH", "graph-a", TARGET_FINGERPRINT),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", "fixture-a", 1, FIXTURE_FINGERPRINT),
                TestExecutionApiRequest.Verbosity.FULL, plan, evidence, manifest,
                completedAt, completedAt.plusSeconds(3600));
    }

    private TestRunEvidence evidence(String runId, Object input, String tenantId) {
        Instant startedAt = Instant.parse("2026-07-20T08:00:00Z");
        return TestSemanticResultFingerprint.attach(mapper,
                new TestRunEvidence("", runId, TestRunEvidence.Status.PASSED,
                        TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST",
                        TARGET_FINGERPRINT, FIXTURE_FINGERPRINT, PLAN_FINGERPRINT,
                        startedAt, startedAt.plusSeconds(1),
                        List.of(new TestRunEvidence.NodeTrace("node-a", "operator-a", "SUCCESS",
                                "REAL", input, Map.of("ok", true), "", 3)),
                        List.of(), List.of(), List.of(), List.of(), Map.ofEntries(
                        Map.entry("tenantId", tenantId),
                        Map.entry("organizationId", "org-a"),
                        Map.entry("projectId", "project-a"),
                        Map.entry("environmentId", "test"),
                        Map.entry("actorId", "runner"),
                        Map.entry("payloadSanitized", true))));
    }

    private static final class MutableValue {
        public String status;

        private MutableValue(String status) {
            this.status = status;
        }
    }
}
