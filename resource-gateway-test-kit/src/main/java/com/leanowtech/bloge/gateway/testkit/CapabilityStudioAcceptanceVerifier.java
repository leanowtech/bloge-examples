package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independently verifies the bounded, payload-free Capability Studio acceptance artifacts.
 *
 * <p>Schema validation is followed by semantic checks that JSON Schema cannot express safely:
 * exact gate and scenario coverage, truthful aggregate status, and observed zero real external
 * calls before acceptance. The verifier never includes instance values or validator paths in its
 * result, so it is suitable for CI and governance logs.</p>
 */
public final class CapabilityStudioAcceptanceVerifier {
    /** The two artifact kinds accepted by this verifier. */
    public enum ArtifactType {
        /** Acceptance baseline v1. */
        ACCEPTANCE_BASELINE_V1,
        /** Golden-path acceptance manifest v1. */
        GOLDEN_PATH_MANIFEST_V1
    }

    /**
     * Compact result of schema and semantic verification.
     *
     * @param artifactType artifact kind that was checked
     * @param schemaValid whether the artifact satisfied its packaged schema
     * @param semanticValid whether cross-field acceptance rules passed
     * @param artifactStatus status declared by the artifact, or {@code UNKNOWN}
     * @param checks names of the verification groups that passed
     * @param errorCode safe machine-readable failure code, or {@code null}
     */
    public record VerificationResult(
            ArtifactType artifactType,
            boolean schemaValid,
            boolean semanticValid,
            String artifactStatus,
            Set<String> checks,
            String errorCode) {
        /**
         * Returns true only when both schema and semantic checks passed.
         *
         * @return whether the artifact is structurally and semantically valid
         */
        public boolean verified() {
            return schemaValid && semanticValid && errorCode == null;
        }
    }

    private static final Set<String> GOLDEN_PATH_IDS = exactIds("GP-01", "GP-02", "GP-03",
            "GP-04", "GP-05", "GP-06", "GP-07", "GP-08", "GP-09", "GP-10");
    private static final Set<String> SPIKE_IDS = exactIds("SPIKE-A", "SPIKE-B", "SPIKE-C");
    private static final Set<String> SCENARIO_IDS = exactIds("CASE-01", "CASE-02", "CASE-03",
            "CASE-04", "CASE-05", "CASE-06", "CASE-07", "CASE-08", "CASE-09");
    private static final Set<String> CASE_TYPES = exactIds(
            "GOLDEN", "NEGATIVE", "BOUNDARY", "REGRESSION", "TIMEOUT", "MUST_NOT_CALL");
    private static final Map<String, String> CANONICAL_CASE_TYPES = Map.ofEntries(
            Map.entry("CASE-01", "GOLDEN"),
            Map.entry("CASE-02", "NEGATIVE"),
            Map.entry("CASE-03", "BOUNDARY"),
            Map.entry("CASE-04", "NEGATIVE"),
            Map.entry("CASE-05", "BOUNDARY"),
            Map.entry("CASE-06", "TIMEOUT"),
            Map.entry("CASE-07", "REGRESSION"),
            Map.entry("CASE-08", "MUST_NOT_CALL"),
            Map.entry("CASE-09", "REGRESSION"));
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(payload|secret|credential|password|token|request|response|body|header).*",
            Pattern.UNICODE_CASE);

    /** Creates a stateless verifier. */
    public CapabilityStudioAcceptanceVerifier() {
    }

    /**
     * Verifies an acceptance baseline v1 artifact.
     *
     * @param value decoded baseline JSON
     * @return a safe typed result; invalid input is represented by an error code
     */
    public VerificationResult verifyAcceptanceBaseline(JsonNode value) {
        ArtifactType type = ArtifactType.ACCEPTANCE_BASELINE_V1;
        boolean schemaValid = schemaValid(value, CapabilityStudioSchemaSupport.BASELINE_RESOURCE);
        if (!schemaValid) {
            return invalid(type, status(value), false, false,
                    containsSensitiveField(value)
                            ? "RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD"
                            : "RG.CAPABILITY_STUDIO.BASELINE_SCHEMA_INVALID");
        }
        if (containsSensitiveField(value)) {
            return invalid(type, status(value), true, false,
                    "RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD");
        }
        String semanticError = baselineSemanticError(value);
        if (semanticError != null) {
            return invalid(type, status(value), true, false, semanticError);
        }
        return valid(type, status(value), "BASELINE_SCHEMA", "BASELINE_GATE_COVERAGE",
                "BASELINE_STATUS_POLICY");
    }

    /**
     * Verifies a golden-path acceptance manifest v1 artifact.
     *
     * @param value decoded manifest JSON
     * @return a safe typed result; invalid input is represented by an error code
     */
    public VerificationResult verifyGoldenPathAcceptanceManifest(JsonNode value) {
        ArtifactType type = ArtifactType.GOLDEN_PATH_MANIFEST_V1;
        boolean schemaValid = schemaValid(value, CapabilityStudioSchemaSupport.MANIFEST_RESOURCE);
        if (!schemaValid) {
            return invalid(type, status(value), false, false,
                    containsSensitiveField(value)
                            ? "RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD"
                            : "RG.CAPABILITY_STUDIO.MANIFEST_SCHEMA_INVALID");
        }
        if (containsSensitiveField(value)) {
            return invalid(type, status(value), true, false,
                    "RG.CAPABILITY_STUDIO.RAW_PAYLOAD_OR_SECRET_FIELD");
        }
        String semanticError = manifestSemanticError(value);
        if (semanticError != null) {
            return invalid(type, status(value), true, false, semanticError);
        }
        return valid(type, status(value), "MANIFEST_SCHEMA", "MANIFEST_GATE_COVERAGE",
                "MANIFEST_SCENARIO_COVERAGE", "MANIFEST_STATUS_POLICY");
    }

    private static String baselineSemanticError(JsonNode value) {
        if (!hasExactIds(value.path("goldenPaths"), "gpId", GOLDEN_PATH_IDS)) {
            return "RG.CAPABILITY_STUDIO.BASELINE_GOLDEN_PATH_COVERAGE_INVALID";
        }
        if (!hasExactIds(value.path("spikes"), "spikeId", SPIKE_IDS)) {
            return "RG.CAPABILITY_STUDIO.BASELINE_SPIKE_COVERAGE_INVALID";
        }
        if (!hasUniqueIds(value.path("securityGates"), "gateId")
                || !hasUniqueIds(value.path("nfrGates"), "gateId")) {
            return "RG.CAPABILITY_STUDIO.BASELINE_GATE_IDS_DUPLICATED";
        }
        if ("APPROVED".equals(status(value))) {
            if (!allBaselineEvidencePresent(value)) {
                return "RG.CAPABILITY_STUDIO.BASELINE_APPROVED_REQUIRES_EVIDENCE";
            }
            if (!baselineAllPass(value)) {
                return "RG.CAPABILITY_STUDIO.BASELINE_APPROVED_REQUIRES_ALL_GATES_PASS";
            }
        }
        return null;
    }

    private static String manifestSemanticError(JsonNode value) {
        if (!hasExactIds(value.path("gpResults"), "gpId", GOLDEN_PATH_IDS)) {
            return "RG.CAPABILITY_STUDIO.MANIFEST_GOLDEN_PATH_COVERAGE_INVALID";
        }
        if (!hasExactIds(value.path("scenarioResults"), "scenarioId", SCENARIO_IDS)) {
            return "RG.CAPABILITY_STUDIO.MANIFEST_SCENARIO_COVERAGE_INVALID";
        }
        if (!hasExactCaseTypes(value.path("scenarioResults"))) {
            return "RG.CAPABILITY_STUDIO.MANIFEST_CASE_TYPE_COVERAGE_INVALID";
        }
        if (!hasCanonicalCaseTypes(value.path("scenarioResults"))) {
            return "RG.CAPABILITY_STUDIO.MANIFEST_CASE_TYPE_MAPPING_INVALID";
        }
        if (hasPassedScenarioWithUnknownOrNonzeroCall(value.path("scenarioResults"))) {
            return "RG.CAPABILITY_STUDIO.PASS_REQUIRES_OBSERVED_ZERO_EXTERNAL_CALLS";
        }
        if ("ACCEPTED".equals(status(value))) {
            if (!manifestEvidencePresent(value)) {
                return "RG.CAPABILITY_STUDIO.MANIFEST_ACCEPTED_REQUIRES_EVIDENCE";
            }
            if (!manifestAllPass(value)) {
                return "RG.CAPABILITY_STUDIO.MANIFEST_ACCEPTED_REQUIRES_ALL_GATES_PASS";
            }
        }
        return null;
    }

    private static boolean baselineAllPass(JsonNode value) {
        return allHaveStatus(value.path("goldenPaths"), "currentStatus", "PASSED")
                && allHaveStatus(value.path("spikes"), "currentStatus", "PASSED")
                && "PASSED".equals(value.path("usabilityGate").path("currentStatus").asText())
                && allHaveStatus(value.path("securityGates"), "currentStatus", "PASSED")
                && allHaveStatus(value.path("nfrGates"), "currentStatus", "PASSED")
                && "VERIFIED".equals(value.path("canonicalPack").path("verificationStatus").asText())
                && "VERIFIED".equals(value.path("branches").path("canonicalBaseline")
                .path("verificationStatus").asText())
                && "VERIFIED".equals(value.path("branches").path("tutorialBranch")
                .path("verificationStatus").asText())
                && cardinalityMatches(value.path("canonicalPack"))
                && allBaselineEvidencePresent(value)
                && allSignoffsApprovedAndBound(value.path("signoffs"));
    }

    private static boolean manifestAllPass(JsonNode value) {
        return allHaveStatus(value.path("gpResults"), "status", "PASSED")
                && allHaveStatus(value.path("scenarioResults"), "status", "PASSED")
                && allHaveStatus(value.path("scenarioResults"), "assertionStatus", "PASSED")
                && allScenarioCallsAreZero(value.path("scenarioResults"))
                && value.path("realExternalCallCount").isInt()
                && value.path("realExternalCallCount").intValue() == 0
                && "PASSED".equals(value.path("egressObservation").path("status").asText())
                && value.path("egressObservation").path("count").isInt()
                && value.path("egressObservation").path("count").intValue() == 0
                && value.path("egressObservation").path("observedAt").isTextual()
                && !value.path("egressObservation").path("evidenceRefs").isEmpty()
                && allEvidenceRefsNonEmpty(value.path("gpResults"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("scenarioResults"), "evidenceRefs")
                && allHaveStatus(value.path("browserAndViewportResults"), "status", "PASSED")
                && allEvidenceRefsNonEmpty(value.path("browserAndViewportResults"), "domEvidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("browserAndViewportResults"), "visualEvidenceRefs")
                && allHaveStatus(value.path("accessibilityResults"), "status", "PASSED")
                && allEvidenceRefsNonEmpty(value.path("accessibilityResults"), "evidenceRefs")
                && allHaveStatus(value.path("protocolAndSecurityResults"), "status", "PASSED")
                && allEvidenceRefsNonEmpty(value.path("protocolAndSecurityResults"), "evidenceRefs")
                && noBlockingLimitations(value.path("knownLimitations"))
                && allSignoffsApprovedAndBound(value.path("signOffs"));
    }

    private static boolean manifestEvidencePresent(JsonNode value) {
        return value.path("egressObservation").path("observedAt").isTextual()
                && !value.path("egressObservation").path("evidenceRefs").isEmpty()
                && allEvidenceRefsNonEmpty(value.path("gpResults"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("scenarioResults"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("browserAndViewportResults"), "domEvidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("browserAndViewportResults"), "visualEvidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("accessibilityResults"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("protocolAndSecurityResults"), "evidenceRefs");
    }

    private static boolean allBaselineEvidencePresent(JsonNode value) {
        return !value.path("canonicalPack").path("evidenceRefs").isEmpty()
                && !value.path("branches").path("canonicalBaseline")
                .path("evidenceRefs").isEmpty()
                && !value.path("branches").path("tutorialBranch").path("evidenceRefs").isEmpty()
                && !value.path("usabilityGate").path("evidenceRefs").isEmpty()
                && allEvidenceRefsNonEmpty(value.path("goldenPaths"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("spikes"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("securityGates"), "evidenceRefs")
                && allEvidenceRefsNonEmpty(value.path("nfrGates"), "evidenceRefs");
    }

    private static boolean cardinalityMatches(JsonNode canonicalPack) {
        JsonNode expected = canonicalPack.path("expectedCardinality");
        JsonNode actual = canonicalPack.path("actualCardinality");
        return expected.equals(actual) && actual.path("api").isInt()
                && actual.path("feature").isInt() && actual.path("tool").isInt()
                && actual.path("case").isInt();
    }

    private static boolean hasPassedScenarioWithUnknownOrNonzeroCall(JsonNode scenarios) {
        for (JsonNode scenario : scenarios) {
            if ("PASSED".equals(scenario.path("status").asText())) {
                JsonNode calls = scenario.path("realCallCount");
                if (!calls.isInt() || calls.intValue() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean allScenarioCallsAreZero(JsonNode scenarios) {
        for (JsonNode scenario : scenarios) {
            JsonNode calls = scenario.path("realCallCount");
            if (!calls.isInt() || calls.intValue() != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean noBlockingLimitations(JsonNode limitations) {
        for (JsonNode limitation : limitations) {
            if (limitation.path("blocksAcceptance").asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allSignoffsApprovedAndBound(JsonNode signoffs) {
        if (!signoffs.isArray() || signoffs.isEmpty()) {
            return false;
        }
        for (JsonNode signoff : signoffs) {
            if (!"APPROVED".equals(signoff.path("status").asText())
                    || !nonEmptyText(signoff.path("actorRef"))
                    || !nonEmptyText(signoff.path("signedAt"))
                    || !nonEmptyText(signoff.path("signatureRef"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEvidenceRefsNonEmpty(JsonNode values, String field) {
        if (!values.isArray() || values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!value.path(field).isArray() || value.path(field).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean nonEmptyText(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank();
    }

    private static boolean allHaveStatus(JsonNode values, String field, String expected) {
        if (!values.isArray() || values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!expected.equals(value.path(field).asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExactIds(JsonNode values, String field, Set<String> expected) {
        if (!values.isArray() || values.size() != expected.size()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.path(field).isTextual() || !actual.add(value.path(field).textValue())) {
                return false;
            }
        }
        return actual.equals(expected);
    }

    private static boolean hasExactCaseTypes(JsonNode scenarios) {
        Set<String> actual = new HashSet<>();
        for (JsonNode scenario : scenarios) {
            if (!scenario.path("caseType").isTextual()) {
                return false;
            }
            actual.add(scenario.path("caseType").textValue());
        }
        return actual.containsAll(CASE_TYPES);
    }

    private static boolean hasCanonicalCaseTypes(JsonNode scenarios) {
        for (JsonNode scenario : scenarios) {
            String scenarioId = scenario.path("scenarioId").textValue();
            if (!CANONICAL_CASE_TYPES.getOrDefault(scenarioId, "")
                    .equals(scenario.path("caseType").asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUniqueIds(JsonNode values, String field) {
        if (!values.isArray()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.path(field).isTextual() || !actual.add(value.path(field).textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean schemaValid(JsonNode value, String resource) {
        try {
            return value != null && CapabilityStudioSchemaSupport.validate(value, resource).isEmpty();
        } catch (IllegalStateException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean containsSensitiveField(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (SENSITIVE_FIELD.matcher(field.getKey()).matches()
                        || containsSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                if (containsSensitiveField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static VerificationResult valid(ArtifactType type, String status, String... checks) {
        return new VerificationResult(type, true, true, status,
                orderedChecks(checks), null);
    }

    private static VerificationResult invalid(ArtifactType type, String status,
            boolean schemaValid, boolean semanticValid, String code) {
        return new VerificationResult(type, schemaValid, semanticValid, status,
                Collections.emptySet(), code);
    }

    private static Set<String> orderedChecks(String... checks) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(checks)));
    }

    private static String status(JsonNode value) {
        return value == null || !value.path("status").isTextual()
                ? "UNKNOWN" : value.path("status").textValue();
    }

    private static Set<String> exactIds(String... ids) {
        return Set.of(ids);
    }
}
