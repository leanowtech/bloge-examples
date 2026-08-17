package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline, payload-safe verifier for the Capability Studio ScenarioDataset v1 projection.
 *
 * <p>The verifier is deliberately independent from Resource Gateway server classes. It validates
 * a strict Draft 2020-12 wire document and the cross-field invariants that JSON Schema cannot
 * express. It accepts metadata references only; payload, fixture, mock and replay material are
 * outside this protocol.</p>
 */
public final class CapabilityStudioScenarioDatasetVerifier {
    /** Maximum canonical or wire representation accepted by this verifier. */
    public static final int MAXIMUM_DATASET_BYTES = 4 * 1024 * 1024;

    private static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.scenario-dataset.v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final List<String> SCOPE_FIELDS = List.of(
            "tenantId", "organizationId", "projectId", "environmentId", "region");

    /** The stable classification of a verification failure. */
    public enum FailureKind {
        /** The projection passed all structural and semantic checks. */
        NONE,
        /** The wire document, schema, or size bound is invalid. */
        SCHEMA,
        /** The document is structurally valid but violates a cross-field invariant. */
        SEMANTIC
    }

    /**
     * Payload-free verification result suitable for CI logs and governance records.
     *
     * @param failureKind stable failure classification
     * @param checks completed or failed check groups
     * @param errorCode stable machine-readable code, or {@code null} when verified
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable result and rejects non-protocol error code shapes. */
        public VerificationResult {
            if (failureKind == null) {
                throw new IllegalArgumentException("failureKind is required");
            }
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
        }

        /**
         * Indicates whether the projection passed every required check.
         *
         * @return true only for a successful result
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioScenarioDatasetVerifier() {
    }

    /**
     * Verifies a decoded ScenarioDataset projection.
     *
     * <p>Because a {@link JsonNode} has no original wire bytes, the verifier applies the 4 MiB
     * limit to the canonical projection used for fingerprinting.</p>
     *
     * @param projection decoded ScenarioDataset projection
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        if (!canonicalFingerprintAvailable(projection)) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SIZE_LIMIT");
        }
        VerificationResult fingerprint = verifyContentFingerprint(projection);
        if (!fingerprint.verified()) {
            return fingerprint;
        }
        VerificationResult scope = verifyScopeClosure(projection);
        if (!scope.verified()) {
            return scope;
        }
        VerificationResult references = verifyReferenceClosure(projection);
        if (!references.verified()) {
            return references;
        }
        VerificationResult quality = verifyQuality(projection);
        if (!quality.verified()) {
            return quality;
        }
        return valid(
                "SCHEMA",
                "CONTENT_FINGERPRINT",
                "SCOPE_CLOSURE",
                "REFERENCE_CLOSURE",
                "QUALITY_COUNTS",
                "ACTIVE_READINESS");
    }

    /**
     * Verifies a raw JSON wire document and enforces its raw byte limit before parsing.
     *
     * @param wireBytes UTF-8 JSON document bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_DATASET_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (java.io.IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        if (projection == null
                || !projection.isObject()
                || !SCHEMA_VERSION.equals(projection.path("schemaVersion").asText())) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    projection,
                    CapabilityStudioSchemaSupport.SCENARIO_DATASET_PROJECTION_RESOURCE).isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static boolean canonicalFingerprintAvailable(JsonNode projection) {
        try {
            canonicalMaterial(projection);
            return true;
        } catch (IllegalArgumentException tooLarge) {
            return false;
        }
    }

    private static VerificationResult verifyContentFingerprint(JsonNode projection) {
        String expected;
        try {
            expected = EvidenceVerificationSupport.sha256Bounded(
                    canonicalMaterial(projection), MAXIMUM_DATASET_BYTES);
        } catch (IllegalArgumentException invalid) {
            return schemaFailure("RG.CAPABILITY_STUDIO.SCENARIO_DATASET_SIZE_LIMIT");
        }
        if (!expected.equals(projection.path("datasetRef").path("fingerprint").asText())) {
            return semanticFailure(
                    "CONTENT_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CONTENT_FINGERPRINT_MISMATCH");
        }
        return valid("CONTENT_FINGERPRINT");
    }

    private static ObjectNode canonicalMaterial(JsonNode projection) {
        ObjectNode material = projection.deepCopy();
        material.withObject("/datasetRef").putNull("fingerprint");
        // sha256Bounded performs recursive key sorting and enforces the canonical byte bound.
        EvidenceVerificationSupport.sha256Bounded(material, MAXIMUM_DATASET_BYTES);
        return material;
    }

    private static VerificationResult verifyScopeClosure(JsonNode projection) {
        JsonNode expectedScope = projection.path("datasetRef").path("scope");
        for (JsonNode ref : allReferences(projection)) {
            if (!sameScope(expectedScope, ref.path("scope"))) {
                return semanticFailure(
                        "SCOPE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CROSS_SCOPE_REFERENCE");
            }
        }
        return valid("SCOPE_CLOSURE");
    }

    private static VerificationResult verifyReferenceClosure(JsonNode projection) {
        Set<String> contractIdentities = new HashSet<>();
        for (JsonNode contractRef : projection.path("contractRefs")) {
            String identity = refIdentity(contractRef);
            if (!contractIdentities.add(identity)) {
                return semanticFailure(
                        "REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_CONTRACT_REF");
            }
        }

        Set<String> caseIds = new HashSet<>();
        Set<String> behaviorIdentities = new HashSet<>();
        for (JsonNode dataCase : projection.path("cases")) {
            String caseId = dataCase.path("caseRef").path("id").asText();
            if (!caseIds.add(caseId)) {
                return semanticFailure(
                        "REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_CASE_REF");
            }
            for (JsonNode applicable : dataCase.path("applicableContractRefs")) {
                if (!contractIdentities.contains(refIdentity(applicable))) {
                    return semanticFailure(
                            "REFERENCE_CLOSURE",
                            "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_CONTRACT_CLOSURE_BROKEN");
                }
            }
            for (JsonNode behavior : dataCase.path("behaviorProfiles")) {
                if (!behaviorIdentities.add(refIdentity(behavior.path("behaviorRef")))) {
                    return semanticFailure(
                            "REFERENCE_CLOSURE",
                            "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_DUPLICATE_BEHAVIOR_REF");
                }
            }
        }
        return valid("REFERENCE_CLOSURE");
    }

    private static VerificationResult verifyQuality(JsonNode projection) {
        JsonNode cases = projection.path("cases");
        int total = cases.size();
        int active = 0;
        int stale = 0;
        int owners = 0;
        int sources = 0;
        int oracles = 0;
        int contracts = 0;
        int behaviors = 0;
        for (JsonNode dataCase : cases) {
            if ("ACTIVE".equals(dataCase.path("lifecycle").asText())) {
                active++;
                if (dataCase.path("owner").isNull()
                        || dataCase.path("sourceRef").isNull()
                        || dataCase.path("oracleRef").isNull()
                        || dataCase.path("applicableContractRefs").isEmpty()
                        || !"READY".equals(dataCase.path("qualityState").asText())) {
                    return semanticFailure(
                            "ACTIVE_READINESS",
                            "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_ACTIVE_CASE_INCOMPLETE");
                }
            }
            if ("STALE".equals(dataCase.path("lifecycle").asText())) {
                stale++;
            }
            if (!dataCase.path("owner").isNull()) {
                owners++;
            }
            if (!dataCase.path("sourceRef").isNull()) {
                sources++;
            }
            if (!dataCase.path("oracleRef").isNull()) {
                oracles++;
            }
            if (!dataCase.path("applicableContractRefs").isEmpty()) {
                contracts++;
            }
            if (dataCase.path("behaviorProfiles").size() > 0) {
                behaviors++;
            }
        }

        JsonNode quality = projection.path("quality");
        if (quality.path("totalCaseCount").asInt() != total
                || quality.path("activeCaseCount").asInt() != active
                || quality.path("staleCaseCount").asInt() != stale) {
            return semanticFailure(
                    "QUALITY_COUNTS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_QUALITY_COUNTS_MISMATCH");
        }
        if (!coverageMatches(quality, "ownerCoveragePercent", owners, total)
                || !coverageMatches(quality, "sourceCoveragePercent", sources, total)
                || !coverageMatches(quality, "oracleCoveragePercent", oracles, total)
                || !coverageMatches(quality, "contractCoveragePercent", contracts, total)
                || !coverageMatches(quality, "behaviorClosurePercent", behaviors, total)) {
            return semanticFailure(
                    "QUALITY_COUNTS",
                    "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_QUALITY_COVERAGE_MISMATCH");
        }
        if ("ACTIVE".equals(projection.path("lifecycle").asText())) {
            if (!"READY".equals(quality.path("status").asText())
                    || stale != 0
                    || owners != total
                    || sources != total
                    || oracles != total
                    || contracts != total) {
                return semanticFailure(
                        "ACTIVE_READINESS",
                        "RG.CAPABILITY_STUDIO.SCENARIO_DATASET_ACTIVE_QUALITY_NOT_READY");
            }
        }
        return valid("QUALITY_COUNTS", "ACTIVE_READINESS");
    }

    private static boolean coverageMatches(
            JsonNode quality,
            String field,
            int covered,
            int total) {
        int expected = total == 0 ? 0 : (int) Math.round(covered * 100.0 / total);
        return quality.path(field).asInt(Integer.MIN_VALUE) == expected;
    }

    private static List<JsonNode> allReferences(JsonNode projection) {
        java.util.ArrayList<JsonNode> refs = new java.util.ArrayList<>();
        refs.add(projection.path("datasetRef"));
        refs.add(projection.path("targetRef"));
        projection.path("contractRefs").forEach(refs::add);
        for (JsonNode dataCase : projection.path("cases")) {
            refs.add(dataCase.path("caseRef"));
            addIfReference(refs, dataCase.path("sourceRef"));
            addIfReference(refs, dataCase.path("oracleRef"));
            dataCase.path("applicableContractRefs").forEach(refs::add);
            for (JsonNode behavior : dataCase.path("behaviorProfiles")) {
                refs.add(behavior.path("behaviorRef"));
                refs.add(behavior.path("dependencyRef"));
            }
        }
        return refs;
    }

    private static void addIfReference(List<JsonNode> refs, JsonNode candidate) {
        if (candidate != null && !candidate.isNull()) {
            refs.add(candidate);
        }
    }

    private static boolean sameScope(JsonNode left, JsonNode right) {
        return SCOPE_FIELDS.stream().allMatch(
                field -> left.path(field).asText().equals(right.path(field).asText()));
    }

    private static String refIdentity(JsonNode ref) {
        return ref.path("kind").asText()
                + "|" + ref.path("id").asText()
                + "|" + ref.path("revision").asLong()
                + "|" + ref.path("fingerprint").asText()
                + "|" + ref.path("authority").asText();
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }
}
