package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema-validated, payload-free ANEKE projection of one exact semantic test-suite revision.
 *
 * <p>This value is a governance seed, not a replacement for offline signature verification.
 * Consumers should retrieve each projected evidence endpoint and verify its portable evidence
 * bundle against an independently pinned key-set before making a release decision.</p>
 *
 * @param suiteId exact immutable suite id
 * @param suiteRevision exact immutable suite revision
 * @param suiteFingerprint canonical suite fingerprint
 * @param targetKind graph or operator target kind
 * @param targetId target identity
 * @param semanticRequirements typed semantic requirements
 * @param evidence verified terminal aggregate references and verdicts
 * @param projectionStatus bounded projection/trust state
 * @param gateReady whether the producer found complete, verified, promotion-eligible evidence
 * @param rawPayload defensive schema-validated workbook payload
 */
public record SemanticCorrectnessWorkbook(
        String suiteId,
        long suiteRevision,
        String suiteFingerprint,
        String targetKind,
        String targetId,
        List<Requirement> semanticRequirements,
        List<Evidence> evidence,
        ProjectionStatus projectionStatus,
        boolean gateReady,
        JsonNode rawPayload
) {
    /** Projection and trust states emitted by Resource Gateway. */
    public enum ProjectionStatus {
        /** At least one verified, semantically satisfied, promotion-eligible result exists. */
        READY,
        /** The exact suite has no retained terminal aggregate evidence. */
        NO_TERMINAL_EVIDENCE,
        /** At least one terminal candidate could not be verified due to authority availability. */
        VERIFICATION_UNAVAILABLE,
        /** Verified terminal evidence exists but none is eligible for a release gate. */
        NO_ELIGIBLE_EVIDENCE
    }

    /** Typed semantic coverage status copied from signed aggregate evidence. */
    public enum SemanticStatus {
        /** Semantic evaluation did not run. */
        NOT_EVALUATED,
        /** Every typed semantic requirement was observed. */
        SATISFIED,
        /** Complete trusted evidence disproved one or more requirements. */
        UNSATISFIED,
        /** Trusted evidence was insufficient to evaluate all requirements. */
        INCOMPLETE
    }

    /** Terminal semantic suite aggregate status. */
    public enum AggregateStatus {
        /** Every case completed and passed. */
        PASSED,
        /** Every scheduled case completed, with one or more failures. */
        COMPLETED_WITH_FAILURES,
        /** Only part of the expected result is available. */
        PARTIAL,
        /** Evidence integrity or completeness blocked a trusted result. */
        EVIDENCE_INCOMPLETE
    }

    /** Server-owned promotion eligibility status. */
    public enum PromotionStatus {
        /** Promotion policy was not evaluated. */
        NOT_EVALUATED,
        /** The aggregate satisfies the suite-owned promotion policy. */
        ELIGIBLE,
        /** The aggregate is blocked by one or more policy requirements. */
        BLOCKED
    }

    /**
     * One typed semantic requirement.
     *
     * @param requirementId suite-local stable requirement id
     * @param kind branch, decision, retry, fallback, timeout, or compensation kind
     */
    public record Requirement(String requirementId, String kind) {
        /** Normalizes machine identities. */
        public Requirement {
            requirementId = normalized(requirementId);
            kind = normalized(kind);
            if (requirementId.isBlank() || kind.isBlank()) {
                throw new IllegalArgumentException("Semantic requirement identity is incomplete");
            }
        }
    }

    /**
     * One verified terminal evidence reference and its signed governance verdicts.
     *
     * @param suiteRunId durable aggregate run id
     * @param evidenceFingerprint canonical signed aggregate fingerprint
     * @param aggregateStatus terminal suite result
     * @param semanticStatus typed semantic coverage status
     * @param promotionStatus server-owned promotion status
     * @param keyId detached attestation verification key id
     * @param endpoint portable evidence-bundle endpoint
     */
    public record Evidence(
            String suiteRunId,
            String evidenceFingerprint,
            AggregateStatus aggregateStatus,
            SemanticStatus semanticStatus,
            PromotionStatus promotionStatus,
            String keyId,
            String endpoint
    ) {
        /** Validates the payload-free evidence identity. */
        public Evidence {
            suiteRunId = normalized(suiteRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            keyId = normalized(keyId);
            endpoint = normalized(endpoint);
            if (suiteRunId.isBlank() || !fingerprint(evidenceFingerprint)
                    || aggregateStatus == null || semanticStatus == null || promotionStatus == null
                    || keyId.isBlank() || !endpoint.matches(
                    "^/api/testing/suite-executions/[^/?#]+/evidence-bundle$")) {
                throw new IllegalArgumentException("Semantic workbook evidence identity is incomplete");
            }
        }
    }

    /** Validates immutable identities and protects decoded JSON from caller mutation. */
    public SemanticCorrectnessWorkbook {
        suiteId = normalized(suiteId);
        suiteFingerprint = normalized(suiteFingerprint);
        targetKind = normalized(targetKind);
        targetId = normalized(targetId);
        semanticRequirements = semanticRequirements == null ? List.of()
                : List.copyOf(semanticRequirements);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        rawPayload = rawPayload == null ? null : rawPayload.deepCopy();
        if (suiteId.isBlank() || suiteRevision < 1 || !fingerprint(suiteFingerprint)
                || targetKind.isBlank() || targetId.isBlank() || projectionStatus == null
                || rawPayload == null) {
            throw new IllegalArgumentException("Semantic correctness workbook is incomplete");
        }
        if (gateReady != (projectionStatus == ProjectionStatus.READY
                && evidence.stream().anyMatch(item -> item.aggregateStatus() == AggregateStatus.PASSED
                && item.semanticStatus() == SemanticStatus.SATISFIED
                && item.promotionStatus() == PromotionStatus.ELIGIBLE))) {
            throw new IllegalArgumentException("Semantic workbook gate readiness is inconsistent");
        }
    }

    /**
     * Decodes and validates a Tool Studio integration envelope and payload.
     *
     * @param envelope decoded integration response
     * @return typed semantic workbook projection
     */
    public static SemanticCorrectnessWorkbook fromEnvelope(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()
                || !"ToolStudioResourceGatewayProtocol".equals(envelope.path("protocol").asText())
                || !"1.0".equals(envelope.path("protocolVersion").asText())
                || !"toolStudio.resourceGateway.envelope.v1".equals(
                envelope.path("schemaVersion").asText())
                || !"SEMANTIC_CORRECTNESS_WORKBOOK_BUNDLE".equals(
                envelope.path("payloadKind").asText())
                || !TestingProtocol.SEMANTIC_CORRECTNESS_WORKBOOK_V1.equals(
                envelope.path("payloadSchemaVersion").asText())
                || !fingerprint(envelope.path("payloadFingerprint").asText())) {
            throw new IllegalArgumentException("Semantic workbook integration envelope is invalid");
        }
        JsonNode payload = envelope.path("payload");
        TestingProtocolSchemaValidator.requireRoot(payload,
                TestingProtocol.SEMANTIC_WORKBOOK_SCHEMA_RESOURCE);
        JsonNode suite = payload.path("suite");
        List<Requirement> requirements = new ArrayList<>();
        suite.path("semanticCoveragePolicy").path("requirements").forEach(item ->
                requirements.add(new Requirement(item.path("requirementId").asText(),
                        item.path("kind").asText())));
        List<Evidence> evidence = new ArrayList<>();
        payload.path("evidence").forEach(item -> evidence.add(new Evidence(
                item.path("suiteRunId").asText(), item.path("evidenceFingerprint").asText(),
                enumValue(AggregateStatus.class, item.path("status").asText()),
                enumValue(SemanticStatus.class,
                        item.path("semanticCoverage").path("status").asText()),
                enumValue(PromotionStatus.class, item.path("promotion").path("status").asText()),
                item.path("attestation").path("keyId").asText(), item.path("endpoint").asText())));
        JsonNode manifest = payload.path("manifest");
        requireManifestConsistency(payload, requirements, evidence);
        return new SemanticCorrectnessWorkbook(suite.path("suiteId").asText(),
                suite.path("revision").asLong(), suite.path("suiteFingerprint").asText(),
                suite.path("target").path("kind").asText(),
                suite.path("target").path("id").asText(), requirements, evidence,
                enumValue(ProjectionStatus.class, manifest.path("projectionStatus").asText()),
                manifest.path("gateReady").asBoolean(), payload);
    }

    private static void requireManifestConsistency(
            JsonNode payload, List<Requirement> requirements, List<Evidence> evidence) {
        JsonNode manifest = payload.path("manifest");
        int cases = payload.path("suite").path("cases").size();
        int candidates = manifest.path("candidateEvidenceCount").asInt();
        int verified = manifest.path("verifiedEvidenceCount").asInt();
        int unavailable = manifest.path("unavailableEvidenceCount").asInt();
        int eligible = (int) evidence.stream().filter(item ->
                item.aggregateStatus() == AggregateStatus.PASSED
                        && item.semanticStatus() == SemanticStatus.SATISFIED
                        && item.promotionStatus() == PromotionStatus.ELIGIBLE).count();
        boolean truncated = manifest.path("evidenceTruncated").asBoolean();
        ProjectionStatus status = enumValue(ProjectionStatus.class,
                manifest.path("projectionStatus").asText());
        ProjectionStatus expectedStatus = candidates == 0
                ? ProjectionStatus.NO_TERMINAL_EVIDENCE
                : unavailable > 0
                ? ProjectionStatus.VERIFICATION_UNAVAILABLE
                : eligible > 0 ? ProjectionStatus.READY : ProjectionStatus.NO_ELIGIBLE_EVIDENCE;
        int unprojectedSentinels = candidates - verified - unavailable;
        if (manifest.path("caseCount").asInt() != cases
                || manifest.path("semanticRequirementCount").asInt() != requirements.size()
                || verified != evidence.size()
                || manifest.path("eligibleEvidenceCount").asInt() != eligible
                || unprojectedSentinels < 0 || unprojectedSentinels > (truncated ? 1 : 0)
                || truncated != (candidates > 100)
                || status != expectedStatus
                || manifest.path("gateReady").asBoolean() != (status == ProjectionStatus.READY)) {
            throw new IllegalArgumentException("Semantic workbook manifest is inconsistent");
        }
    }

    /**
     * Fails closed when the workbook is not a release-ready semantic evidence seed.
     *
     * @throws IllegalStateException with a stable code when evidence is absent, unavailable, or blocked
     */
    public void requireGateReady() {
        if (!gateReady) {
            throw new IllegalStateException("SEMANTIC_WORKBOOK_NOT_GATE_READY:" + projectionStatus.name());
        }
    }

    /**
     * Returns the complete validated payload for audit and forward-compatible inspection.
     *
     * @return defensive complete payload
     */
    @Override
    public JsonNode rawPayload() {
        return rawPayload.deepCopy();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Semantic workbook contains an unknown status");
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
