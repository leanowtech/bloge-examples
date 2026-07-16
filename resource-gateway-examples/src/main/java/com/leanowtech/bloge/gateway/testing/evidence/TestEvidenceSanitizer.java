package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadSanitizer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Sanitizes every payload-bearing field before test evidence crosses a persistence boundary. */
public final class TestEvidenceSanitizer {

    private final ObjectMapper objectMapper;

    public TestEvidenceSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Produces a structurally equivalent evidence record with secrets redacted and payloads bounded.
     * The original evidence remains an in-memory execution detail and must never be stored directly.
     */
    public TestRunEvidence sanitize(TestRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        var nodes = evidence.nodeTrace().stream().map(node -> new TestRunEvidence.NodeTrace(
                node.nodeId(), node.operatorRef(), node.status(), node.fidelity(),
                sanitizeValue(node.input()), sanitizeValue(node.output()), node.errorCode(), node.durationMs(),
                node.invocationSiteId(), node.graphPath(), node.correlationKey(), node.occurrence(),
                node.graphOccurrence(),
                node.attempts().stream().map(attempt -> new TestRunEvidence.AttemptTrace(
                        attempt.attempt(), attempt.status(), attempt.fidelity(),
                        sanitizeValue(attempt.input()), sanitizeValue(attempt.output()),
                        attempt.errorCode(), attempt.durationMs())).toList()
        )).toList();
        var edges = evidence.edgeTrace().stream().map(edge -> new TestRunEvidence.EdgeTrace(
                edge.edgeId(), edge.status(), sanitizeValue(edge.value()), edge.graphPath(),
                edge.correlationKey(), edge.graphOccurrence(), edge.fromInvocationSiteId(),
                edge.toInvocationSiteId())).toList();
        var assertions = evidence.assertionResults().stream().map(assertion ->
                new TestRunEvidence.AssertionResult(assertion.scope(), assertion.path(), assertion.passed(),
                        sanitizeValue(assertion.expected()), sanitizeValue(assertion.actual()),
                        String.valueOf(sanitizeValue(assertion.diagnostic())))).toList();
        Object metadataValue = sanitizeValue(evidence.metadata());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (metadataValue instanceof Map<?, ?> map) {
            map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
        }
        metadata.put("payloadSanitized", true);
        TestRunEvidence sanitized = new TestRunEvidence(evidence.schemaVersion(), evidence.runId(),
                evidence.status(),
                evidence.evidenceClass(), evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(), evidence.startedAt(),
                evidence.completedAt(), nodes, edges, evidence.fixtureConsumptions(), assertions,
                evidence.diagnostics().stream().map(item -> String.valueOf(sanitizeValue(item))).toList(),
                metadata);
        return TestSemanticResultFingerprint.attach(objectMapper, sanitized);
    }

    private Object sanitizeValue(Object value) {
        Object normalized;
        try {
            normalized = objectMapper.convertValue(value, Object.class);
        } catch (IllegalArgumentException failure) {
            normalized = String.valueOf(value);
        }
        return VisualPayloadSanitizer.capture(Map.of(), normalized, Map.of()).output();
    }
}
