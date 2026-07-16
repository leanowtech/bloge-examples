package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSemanticResultFingerprintTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();

    @Test
    void equivalentRunsIgnoreRunFactsDurationsProvenanceAndCompletionOrder() {
        TestRunEvidence first = TestSemanticResultFingerprint.attach(mapper,
                evidence("run-a", Instant.parse("2026-07-16T00:00:00Z"), 3, false));
        TestRunEvidence repeated = TestSemanticResultFingerprint.attach(mapper,
                evidence("run-b", Instant.parse("2026-07-17T12:30:00Z"), 900, true));

        assertThat(repeated.semanticResultFingerprint())
                .isEqualTo(first.semanticResultFingerprint());
        assertThat(ProtocolFingerprint.of(mapper, repeated))
                .isNotEqualTo(ProtocolFingerprint.of(mapper, first));
        assertThat(TestSemanticResultFingerprint.matches(mapper, first)).isTrue();
        assertThat(TestSemanticResultFingerprint.matches(mapper, repeated)).isTrue();
    }

    @Test
    void outputOutcomeAndExecutionServiceUsageAreSemanticDifferences() {
        TestRunEvidence baseline = evidence("run-a", Instant.EPOCH, 3, false);
        TestRunEvidence changedOutput = replaceOutput(baseline, Map.of("decision", "DECLINE"));
        TestRunEvidence changedStatus = new TestRunEvidence(baseline.schemaVersion(), baseline.runId(),
                TestRunEvidence.Status.EXECUTION_FAILED, baseline.evidenceClass(),
                baseline.executionPurpose(), baseline.targetFingerprint(),
                baseline.fixtureBundleFingerprint(), baseline.planFingerprint(), baseline.startedAt(),
                baseline.completedAt(), baseline.nodeTrace(), baseline.edgeTrace(),
                baseline.fixtureConsumptions(), baseline.assertionResults(), baseline.diagnostics(),
                baseline.metadata());
        TestRunEvidence changedUsage = withMetadata(baseline, Map.of(
                "executionServiceUsages", List.of(Map.of(
                        "service", "RANDOM", "providerCalls", 2,
                        "semanticProviderCalls", 2, "functionCalls", 2)),
                "executionServiceStateFingerprint", "sha256:" + "9".repeat(64)));

        String fingerprint = TestSemanticResultFingerprint.compute(mapper, baseline);
        assertThat(TestSemanticResultFingerprint.compute(mapper, changedOutput)).isNotEqualTo(fingerprint);
        assertThat(TestSemanticResultFingerprint.compute(mapper, changedStatus)).isNotEqualTo(fingerprint);
        assertThat(TestSemanticResultFingerprint.compute(mapper, changedUsage)).isNotEqualTo(fingerprint);
    }

    @Test
    void changedEvidenceCannotReuseAnEarlierSemanticFingerprint() {
        TestRunEvidence attached = TestSemanticResultFingerprint.attach(mapper,
                evidence("run-a", Instant.EPOCH, 3, false));
        TestRunEvidence changed = replaceOutput(attached, Map.of("decision", "DECLINE"))
                .withSemanticResultFingerprint(attached.semanticResultFingerprint());

        assertThat(TestSemanticResultFingerprint.matches(mapper, changed)).isFalse();
    }

    @Test
    void ignoresInfrastructureUsageAndParallelSideEffectCompletionOrder() {
        TestRunEvidence baseline = evidence("run-a", Instant.EPOCH, 3, false);
        Map<String, Object> debit = Map.of(
                "attemptId", "attempt-a", "operation", "debit", "amount", 50);
        Map<String, Object> notify = Map.of(
                "attemptId", "attempt-b", "operation", "notify", "channel", "email");
        TestRunEvidence first = withMetadata(baseline, Map.of(
                "executionServiceUsages", List.of(Map.of(
                        "schemaVersion", "bloge.executionServiceUsage.v1",
                        "service", "UUID", "mode", "SEEDED_SHA256_UUID",
                        "providerCalls", 12, "semanticProviderCalls", 0, "functionCalls", 0,
                        "providerScopeFingerprints", List.of("sha256:" + "7".repeat(64)))),
                "sideEffectIntents", List.of(debit, notify)));
        TestRunEvidence repeated = withMetadata(baseline, Map.of(
                "executionServiceUsages", List.of(Map.of(
                        "schemaVersion", "bloge.executionServiceUsage.v1",
                        "service", "UUID", "mode", "SEEDED_SHA256_UUID",
                        "providerCalls", 99, "semanticProviderCalls", 0, "functionCalls", 0,
                        "providerScopeFingerprints", List.of("sha256:" + "6".repeat(64)))),
                "sideEffectIntents", List.of(
                        Map.of("attemptId", "different-b", "operation", "notify", "channel", "email"),
                        Map.of("attemptId", "different-a", "operation", "debit", "amount", 50))));

        assertThat(TestSemanticResultFingerprint.compute(mapper, repeated))
                .isEqualTo(TestSemanticResultFingerprint.compute(mapper, first));
    }

    private static TestRunEvidence evidence(String runId, Instant startedAt,
                                            long durationBase, boolean reverseOrder) {
        TestRunEvidence.NodeTrace first = node("fetch", "/root/fetch#PRIMARY", 1,
                Map.of("customerId", "C-1"), Map.of("score", 720), durationBase);
        TestRunEvidence.NodeTrace second = node("decide", "/root/decide#PRIMARY", 1,
                Map.of("score", 720), Map.of("decision", "APPROVE"), durationBase + 1);
        TestRunEvidence.EdgeTrace firstEdge = new TestRunEvidence.EdgeTrace(
                "input->fetch", "TRANSFERRED", Map.of("customerId", "C-1"),
                "/root", "", 1, "/root/input#PRIMARY", "/root/fetch#PRIMARY");
        TestRunEvidence.EdgeTrace secondEdge = new TestRunEvidence.EdgeTrace(
                "fetch->decide", "TRANSFERRED", Map.of("score", 720),
                "/root", "", 1, "/root/fetch#PRIMARY", "/root/decide#PRIMARY");
        List<TestRunEvidence.NodeTrace> nodes = reverseOrder
                ? List.of(second, first) : List.of(first, second);
        List<TestRunEvidence.EdgeTrace> edges = reverseOrder
                ? List.of(secondEdge, firstEdge) : List.of(firstEdge, secondEdge);
        Map<String, Object> metadata = Map.of(
                "tenantId", reverseOrder ? "tenant-b" : "tenant-a",
                "correlationId", reverseOrder ? "correlation-b" : "correlation-a",
                "nodeControlModes", Map.of(
                        "/root/fetch#PRIMARY", "REAL", "/root/decide#PRIMARY", "REAL"),
                "executionServiceUsages", List.of(Map.of(
                        "service", "RANDOM", "providerCalls", 1,
                        "semanticProviderCalls", 1, "functionCalls", 1)),
                "executionServiceStateFingerprint", "sha256:" + "8".repeat(64));
        return new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, runId,
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST", "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64), "sha256:" + "3".repeat(64),
                startedAt, startedAt.plusSeconds(durationBase + 10), nodes, edges,
                List.of(new TestRunEvidence.FixtureConsumption("fixture-a", 1, true, "SATISFIED")),
                List.of(new TestRunEvidence.AssertionResult("OUTPUT_PATH", "/decision", true,
                        "APPROVE", "APPROVE", "")), List.of(), metadata);
    }

    private static TestRunEvidence.NodeTrace node(String nodeId, String site, int occurrence,
                                                  Object input, Object output, long duration) {
        return new TestRunEvidence.NodeTrace(nodeId, "operator." + nodeId, "SUCCESS", "REAL",
                input, output, "", duration, site, "/root", "", occurrence, 1,
                List.of(new TestRunEvidence.AttemptTrace(1, "SUCCESS", "REAL", input, output,
                        "", duration)));
    }

    private static TestRunEvidence replaceOutput(TestRunEvidence evidence, Object output) {
        List<TestRunEvidence.NodeTrace> nodes = evidence.nodeTrace().stream().map(node ->
                "decide".equals(node.nodeId())
                        ? new TestRunEvidence.NodeTrace(node.nodeId(), node.operatorRef(), node.status(),
                        node.fidelity(), node.input(), output, node.errorCode(), node.durationMs(),
                        node.invocationSiteId(), node.graphPath(), node.correlationKey(), node.occurrence(),
                        node.graphOccurrence(), node.attempts()) : node).toList();
        return new TestRunEvidence(evidence.schemaVersion(), evidence.runId(), evidence.status(),
                evidence.evidenceClass(), evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(), evidence.startedAt(),
                evidence.completedAt(), nodes, evidence.edgeTrace(), evidence.fixtureConsumptions(),
                evidence.assertionResults(), evidence.diagnostics(), evidence.metadata());
    }

    private static TestRunEvidence withMetadata(TestRunEvidence evidence,
                                                Map<String, Object> metadata) {
        return new TestRunEvidence(evidence.schemaVersion(), evidence.runId(), evidence.status(),
                evidence.evidenceClass(), evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(), evidence.startedAt(),
                evidence.completedAt(), evidence.nodeTrace(), evidence.edgeTrace(),
                evidence.fixtureConsumptions(), evidence.assertionResults(), evidence.diagnostics(),
                metadata);
    }
}
