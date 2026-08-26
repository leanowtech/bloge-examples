package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned codec for TestRunEvidence and its optional state/function evidence projection. */
public final class TestRunEvidenceProtocolCodec {
    /** Metadata key carrying the versioned payload-free control projection. */
    public static final String CONTROL_PROJECTION_METADATA_KEY = "controlEvidenceProjection";

    private final ObjectMapper objectMapper;
    private final ObjectMapper strictMapper;

    public TestRunEvidenceProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Serializes evidence using the application protocol mapper. */
    public String write(TestRunEvidence evidence) {
        requireEvidence(evidence);
        controlProjection(evidence);
        if (!TestSemanticResultFingerprint.matches(objectMapper, evidence)) throw invalid();
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException failure) {
            throw invalid();
        }
    }

    /** Reads and independently verifies evidence and, when present, its control projection. */
    public TestRunEvidence read(String json) {
        try {
            if (json == null || json.isBlank() || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > 2 * 1024 * 1024) throw invalid();
            JsonNode tree = strictMapper.readTree(json);
            if (tree == null || !tree.isObject()) throw invalid();
            TestRunEvidence evidence = strictMapper.treeToValue(tree, TestRunEvidence.class);
            requireEvidence(evidence);
            JsonNode projectionNode = tree.path("metadata")
                    .path(CONTROL_PROJECTION_METADATA_KEY);
            TestRunControlEvidenceProjection metadataProjection = controlProjection(evidence);
            if (!projectionNode.isMissingNode() && !projectionNode.isNull()) {
                TestRunControlEvidenceProjection projection = strictMapper.treeToValue(
                        projectionNode, TestRunControlEvidenceProjection.class);
                if (!projection.equals(metadataProjection)
                        || !evidence.runId().equals(projection.runId())) throw invalid();
            } else if (metadataProjection != null) {
                throw invalid();
            }
            if (!TestSemanticResultFingerprint.matches(objectMapper, evidence)) throw invalid();
            return evidence;
        } catch (JsonProcessingException | RuntimeException failure) {
            throw invalid();
        }
    }

    /** Returns the verified control projection, or {@code null} for historical ordinary evidence. */
    public TestRunControlEvidenceProjection controlProjection(TestRunEvidence evidence) {
        requireEvidence(evidence);
        Object value = evidence.metadata().get(CONTROL_PROJECTION_METADATA_KEY);
        if (value == null) return null;
        try {
            TestRunControlEvidenceProjection projection = strictMapper.convertValue(
                    value, TestRunControlEvidenceProjection.class);
            if (!evidence.runId().equals(projection.runId())
                    || !evidence.targetFingerprint().equals(projection.targetFingerprint())
                    || !evidence.planFingerprint().equals(projection.executionPlanFingerprint())) {
                throw invalid();
            }
            return projection;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /** Attaches a verified projection while preserving all existing evidence fields. */
    public TestRunEvidence withControlProjection(
            TestRunEvidence evidence, TestRunControlEvidenceProjection projection) {
        requireEvidence(evidence);
        Objects.requireNonNull(projection, "projection");
        if (!evidence.runId().equals(projection.runId())
                || !evidence.targetFingerprint().equals(projection.targetFingerprint())
                || !evidence.planFingerprint().equals(projection.executionPlanFingerprint())
                || evidence.metadata().containsKey(CONTROL_PROJECTION_METADATA_KEY)) throw invalid();
        Map<String, Object> metadata = new LinkedHashMap<>(evidence.metadata());
        metadata.put(CONTROL_PROJECTION_METADATA_KEY, projection);
        return TestSemanticResultFingerprint.attach(objectMapper, new TestRunEvidence(
                evidence.schemaVersion(), evidence.runId(), evidence.status(),
                evidence.evidenceClass(), evidence.executionPurpose(), evidence.targetFingerprint(),
                evidence.fixtureBundleFingerprint(), evidence.planFingerprint(),
                evidence.startedAt(), evidence.completedAt(), evidence.nodeTrace(), evidence.edgeTrace(),
                evidence.fixtureConsumptions(), evidence.assertionResults(), evidence.diagnostics(),
                metadata));
    }

    private static void requireEvidence(TestRunEvidence evidence) {
        if (evidence == null || evidence.runId().isBlank()) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RG.TEST.EVIDENCE_PROTOCOL_INVALID");
    }

    /** Converts a control projection to the stable, run-independent semantic material. */
    public Object semanticProjection(Object value) {
        return controlProjectionValue(value).stableSemanticMaterial();
    }

    private TestRunControlEvidenceProjection controlProjectionValue(Object value) {
        if (value instanceof TestRunControlEvidenceProjection projection) return projection;
        try {
            return strictMapper.convertValue(value, TestRunControlEvidenceProjection.class);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }
}
