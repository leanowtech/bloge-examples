package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline verifier for the payload-free Capability Studio governed run evidence projection.
 *
 * <p>The verifier is deliberately independent from Resource Gateway server classes. It validates
 * the packaged Draft 2020-12 schema, then checks canonical reference closure, run/Data Lens
 * identity, structure-only redaction, bounded trace closure, and both producer fingerprints.</p>
 */
public final class CapabilityStudioGovernedRunEvidenceVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_GOVERNED_RUN_EVIDENCE_BYTES = 16 * 1024 * 1024;
    /** Canonical baseline bound to every governed child run. */
    public static final String CANONICAL_BASELINE_ID = "capability-studio-governed-9x3-v1";

    private static final int MAX_FINGERPRINT_MATERIAL_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** The projection passed all schema and semantic checks. */
        NONE,
        /** The wire document violates the strict schema or size limit. */
        SCHEMA,
        /** The document is schema-valid but violates a cross-field invariant. */
        SEMANTIC
    }

    /**
     * Payload-free result suitable for CI and governance logs.
     *
     * @param failureKind schema/semantic classification
     * @param checks protocol checks completed by the verifier
     * @param errorCode stable protocol error code, or {@code null} on success
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable protocol-shaped result. */
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
         * Returns true only when the complete projection contract passed.
         *
         * @return whether all schema and semantic checks passed
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioGovernedRunEvidenceVerifier() {
    }

    /**
     * Verifies a decoded governed run evidence projection.
     *
     * @param projection decoded JSON projection
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode projection) {
        VerificationResult schema = verifySchema(projection);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult status = verifyStatusAndBaseline(projection);
        if (!status.verified()) {
            return status;
        }
        VerificationResult references = verifyReferenceClosure(projection);
        if (!references.verified()) {
            return references;
        }
        VerificationResult bindingReferences = verifyBindingReferences(projection);
        if (!bindingReferences.verified()) {
            return bindingReferences;
        }
        VerificationResult identity = verifyRunLensIdentity(projection);
        if (!identity.verified()) {
            return identity;
        }
        VerificationResult counts = verifyAssertionAndFixtureCounts(projection);
        if (!counts.verified()) {
            return counts;
        }
        VerificationResult closure = verifyTraceClosure(projection);
        if (!closure.verified()) {
            return closure;
        }
        VerificationResult permission = verifyPermissionBoundary(projection);
        if (!permission.verified()) {
            return permission;
        }
        VerificationResult lens = verifyDataLensFingerprint(projection);
        if (!lens.verified()) {
            return lens;
        }
        VerificationResult binding = verifyBindingPlanFingerprint(projection);
        if (!binding.verified()) {
            return binding;
        }
        VerificationResult projectionFingerprint = verifyProjectionFingerprint(projection);
        if (!projectionFingerprint.verified()) {
            return projectionFingerprint;
        }
        return valid(
                "SCHEMA",
                "EXACT_VERIFICATION_STATUS",
                "CANONICAL_REFERENCE_CLOSURE",
                "BINDING_REFERENCE_CLOSURE",
                "RUN_DATA_LENS_IDENTITY",
                "ASSERTION_FIXTURE_CARDINALITY",
                "INVOCATION_SITE_CLOSURE",
                "FOCUS_NODE_CLOSURE",
                "STRUCTURE_ONLY_PAYLOAD_BOUNDARY",
                "DATA_LENS_FINGERPRINT",
                "BINDING_PLAN_FINGERPRINT",
                "PROJECTION_FINGERPRINT");
    }

    /**
     * Verifies one UTF-8 JSON wire document. The raw byte limit is applied before parsing and
     * Jackson is configured to reject trailing JSON tokens.
     *
     * @param wireBytes UTF-8 JSON response bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_GOVERNED_RUN_EVIDENCE_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (JsonProcessingException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_INVALID_JSON");
        } catch (java.io.IOException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode projection) {
        try {
            if (projection == null || !CapabilityStudioSchemaSupport.validate(
                    projection, CapabilityStudioSchemaSupport.GOVERNED_RUN_EVIDENCE_RESOURCE)
                    .isEmpty()) {
                return schemaFailure(
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_SCHEMA_UNAVAILABLE");
        }
        return valid("SCHEMA");
    }

    private static VerificationResult verifyStatusAndBaseline(JsonNode projection) {
        if (!"EXACT_VERIFIED".equals(projection.path("verificationStatus").textValue())) {
            return semanticFailure(
                    "EXACT_VERIFICATION_STATUS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_STATUS_INVALID");
        }
        if (!CANONICAL_BASELINE_ID.equals(projection.path("baselineId").textValue())) {
            return semanticFailure(
                    "EXACT_VERIFICATION_STATUS",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BASELINE_ID_INVALID");
        }
        return valid("EXACT_VERIFICATION_STATUS");
    }

    private static VerificationResult verifyReferenceClosure(JsonNode projection) {
        if (!hasKind(projection, "graphRef", "FEATURE")
                || !hasKind(projection, "capabilityRef", "TOOL")
                || !hasKind(projection, "contractRef", "CONTRACT")
                || !hasKind(projection, "datasetRef", "DATASET")
                || !hasKind(projection, "caseRef", "DATA_CASE")
                || !"OPERATOR".equals(projection.path("runtimeTarget").path("kind").textValue())) {
            return semanticFailure(
                    "CANONICAL_REFERENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_REFERENCE_KIND_INVALID");
        }
        JsonNode scenario = projection.path("scenario");
        JsonNode scenarioCaseRef = scenario.path("caseRef");
        JsonNode topCaseRef = projection.path("caseRef");
        JsonNode topContractRef = projection.path("contractRef");
        String caseId = scenario.path("caseId").textValue();
        if (!caseId.equals(topCaseRef.path("id").textValue())
                || !caseId.equals(scenarioCaseRef.path("id").textValue())
                || !scenarioCaseRef.equals(topCaseRef)
                || !caseId.equals(scenario.path("scenarioRef").path("id").textValue())) {
            return semanticFailure(
                    "CANONICAL_REFERENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_CASE_DRIFT");
        }
        for (JsonNode contract : scenario.path("applicableContractRefs")) {
            if (!"CONTRACT".equals(contract.path("kind").textValue())) {
                return semanticFailure(
                        "CANONICAL_REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_CONTRACT_REF_INVALID");
            }
        }
        boolean topContractApplicable = false;
        for (JsonNode contract : scenario.path("applicableContractRefs")) {
            if (contract.equals(topContractRef)) {
                topContractApplicable = true;
                break;
            }
        }
        if (!topContractApplicable) {
            return semanticFailure(
                    "CANONICAL_REFERENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TOP_CONTRACT_NOT_APPLICABLE");
        }
        JsonNode capability = projection.path("capabilityRef");
        JsonNode runtime = projection.path("runtimeTarget");
        if (!sameText(capability, runtime, "id", "id")) {
            return semanticFailure(
                    "CANONICAL_REFERENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_RUNTIME_TARGET_DRIFT");
        }
        return valid("CANONICAL_REFERENCE_CLOSURE");
    }

    private static VerificationResult verifyBindingReferences(JsonNode projection) {
        JsonNode binding = projection.path("bindingPlan");
        Set<String> behaviorRefs = new HashSet<>();
        Set<String> behaviorCoordinates = new HashSet<>();
        for (JsonNode behavior : binding.path("behaviorRefs")) {
            if (!behaviorRefs.add(referenceKey(behavior))
                    || !behaviorCoordinates.add(referenceCoordinate(behavior))) {
                return semanticFailure(
                        "BINDING_REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DUPLICATE_BEHAVIOR_REF");
            }
        }
        Set<String> dependencyRefs = new HashSet<>();
        for (JsonNode dependency : binding.path("dependencyRefs")) {
            String key = referenceKey(dependency);
            if (!dependencyRefs.add(key)) {
                return semanticFailure(
                        "BINDING_REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DUPLICATE_DEPENDENCY_REF");
            }
            if (behaviorCoordinates.contains(referenceCoordinate(dependency))) {
                return semanticFailure(
                        "BINDING_REFERENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BEHAVIOR_DEPENDENCY_OVERLAP");
            }
        }
        return valid("BINDING_REFERENCE_CLOSURE");
    }

    private static VerificationResult verifyRunLensIdentity(JsonNode projection) {
        JsonNode run = projection.path("run");
        JsonNode lens = projection.path("dataLens");
        if (!sameText(run, lens, "runId", "runId")
                || !sameText(run, lens, "status", "runStatus")) {
            return semanticFailure(
                    "RUN_DATA_LENS_IDENTITY",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_RUN_LENS_DRIFT");
        }
        return valid("RUN_DATA_LENS_IDENTITY");
    }

    private static VerificationResult verifyAssertionAndFixtureCounts(JsonNode projection) {
        JsonNode run = projection.path("run");
        int assertionsEvaluated = run.path("assertionsEvaluated").intValue();
        int assertionsPassed = run.path("assertionsPassed").intValue();
        int fixturesEvaluated = run.path("fixtureControlsEvaluated").intValue();
        int fixturesSatisfied = run.path("fixtureControlsSatisfied").intValue();
        if (assertionsPassed > assertionsEvaluated || fixturesSatisfied > fixturesEvaluated) {
            return semanticFailure(
                    "ASSERTION_FIXTURE_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PASS_COUNT_EXCEEDS_EVALUATED");
        }
        if ("PASSED".equals(run.path("status").textValue())
                && (assertionsEvaluated < 1 || assertionsPassed != assertionsEvaluated
                || fixturesEvaluated < 1 || fixturesSatisfied != fixturesEvaluated)) {
            return semanticFailure(
                    "ASSERTION_FIXTURE_CARDINALITY",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PASSED_COUNTS_INCOMPLETE");
        }
        return valid("ASSERTION_FIXTURE_CARDINALITY");
    }

    private static VerificationResult verifyTraceClosure(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> invocationSites = new LinkedHashSet<>();
        Map<String, String> invocationPaths = new HashMap<>();
        for (JsonNode node : lens.path("nodes")) {
            if (!nodeIds.add(node.path("nodeId").textValue())
                    || !invocationSites.add(node.path("invocationSite").textValue())
                    || !node.path("invocationSite").textValue()
                    .startsWith(node.path("graphPath").textValue() + "/")) {
                return semanticFailure(
                        "INVOCATION_SITE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DUPLICATE_NODE_OR_SITE");
            }
            invocationPaths.put(node.path("invocationSite").textValue(),
                    node.path("graphPath").textValue());
            Set<Integer> attempts = new HashSet<>();
            for (JsonNode attempt : node.path("attempts")) {
                if (!attempts.add(attempt.path("attempt").intValue())) {
                    return semanticFailure(
                            "INVOCATION_SITE_CLOSURE",
                            "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DUPLICATE_ATTEMPT");
                }
            }
        }
        if (!nodeIds.contains(projection.path("focusNodeId").textValue())) {
            return semanticFailure(
                    "FOCUS_NODE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_FOCUS_NODE_MISSING");
        }
        Set<String> edgeIds = new LinkedHashSet<>();
        for (JsonNode edge : lens.path("edges")) {
            if (!edgeIds.add(edge.path("edgeId").textValue())
                    || !invocationPaths.containsKey(edge.path("fromInvocationSite").textValue())
                    || !invocationPaths.containsKey(edge.path("toInvocationSite").textValue())
                    || !edge.path("graphPath").textValue().equals(
                    invocationPaths.get(edge.path("fromInvocationSite").textValue()))
                    || !edge.path("graphPath").textValue().equals(
                    invocationPaths.get(edge.path("toInvocationSite").textValue()))) {
                return semanticFailure(
                        "INVOCATION_SITE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DANGLING_EDGE");
            }
        }
        JsonNode truncation = lens.path("truncation");
        if (truncation.path("nodesTruncated").booleanValue()
                != truncation.path("omittedNodes").intValue() > 0
                || truncation.path("edgesTruncated").booleanValue()
                != truncation.path("omittedEdges").intValue() > 0
                || truncation.path("attemptsTruncated").booleanValue()
                != truncation.path("omittedAttempts").intValue() > 0) {
            return semanticFailure(
                    "INVOCATION_SITE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TRUNCATION_INVALID");
        }
        if (truncation.path("nodesTruncated").booleanValue()
                && lens.path("nodes").size() != 256) {
            return semanticFailure(
                    "INVOCATION_SITE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TRUNCATION_INVALID");
        }
        if (truncation.path("edgesTruncated").booleanValue()
                && lens.path("edges").size() != 512) {
            return semanticFailure(
                    "INVOCATION_SITE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TRUNCATION_INVALID");
        }
        if (truncation.path("attemptsTruncated").booleanValue()) {
            boolean hasBoundedNode = false;
            for (JsonNode node : lens.path("nodes")) {
                if (node.path("attempts").size() == 16) {
                    hasBoundedNode = true;
                    break;
                }
            }
            if (!hasBoundedNode) {
                return semanticFailure(
                        "INVOCATION_SITE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_TRUNCATION_INVALID");
            }
        }
        return valid("INVOCATION_SITE_CLOSURE", "FOCUS_NODE_CLOSURE");
    }

    private static VerificationResult verifyPermissionBoundary(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        for (JsonNode node : lens.path("nodes")) {
            if (!node.path("input").isNull() || !node.path("output").isNull()) {
                return payloadLeak();
            }
            for (JsonNode attempt : node.path("attempts")) {
                if (!attempt.path("input").isNull() || !attempt.path("output").isNull()) {
                    return payloadLeak();
                }
            }
        }
        for (JsonNode edge : lens.path("edges")) {
            if (!edge.path("value").isNull()) {
                return payloadLeak();
            }
        }
        JsonNode difference = lens.path("firstDifference");
        if (!difference.isNull()
                && (!difference.path("expected").isNull() || !difference.path("actual").isNull())) {
            return payloadLeak();
        }
        return valid("STRUCTURE_ONLY_PAYLOAD_BOUNDARY");
    }

    private static VerificationResult verifyDataLensFingerprint(JsonNode projection) {
        JsonNode lens = projection.path("dataLens");
        ObjectNode material = JSON.createObjectNode();
        material.set("runId", lens.get("runId"));
        material.set("runStatus", lens.get("runStatus"));
        material.set("permissionMode", lens.get("permissionMode"));
        material.set("nodes", lens.get("nodes"));
        material.set("edges", lens.get("edges"));
        material.set("firstDifference", lens.get("firstDifference"));
        material.set("truncation", lens.get("truncation"));
        try {
            String expected = EvidenceVerificationSupport.sha256Bounded(
                    material, MAX_FINGERPRINT_MATERIAL_BYTES);
            if (!expected.equals(lens.path("fingerprint").textValue())) {
                return semanticFailure(
                        "DATA_LENS_FINGERPRINT",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DATA_LENS_FINGERPRINT_TAMPERED");
            }
        } catch (RuntimeException invalidMaterial) {
            return semanticFailure(
                    "DATA_LENS_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_DATA_LENS_FINGERPRINT_UNAVAILABLE");
        }
        return valid("DATA_LENS_FINGERPRINT");
    }

    private static VerificationResult verifyBindingPlanFingerprint(JsonNode projection) {
        JsonNode binding = projection.path("bindingPlan");
        JsonNode ref = binding.path("ref");
        ObjectNode material = JSON.createObjectNode();
        material.put("refKind", ref.path("kind").textValue());
        material.put("refId", ref.path("id").textValue());
        material.put("refRevision", ref.path("revision").longValue());
        material.set("fixtureBundleRef", binding.get("fixtureBundleRef"));
        material.set("effectiveExecutionPlanFingerprint",
                binding.get("effectiveExecutionPlanFingerprint"));
        material.set("behaviorRefs", binding.get("behaviorRefs"));
        material.set("dependencyRefs", binding.get("dependencyRefs"));
        material.set("fallbackToReal", binding.get("fallbackToReal"));
        material.set("sourceMapFingerprint", binding.get("sourceMapFingerprint"));
        material.set("provenanceFingerprint", binding.get("provenanceFingerprint"));
        try {
            String expected = EvidenceVerificationSupport.sha256Bounded(
                    material, MAX_FINGERPRINT_MATERIAL_BYTES);
            if (!expected.equals(ref.path("fingerprint").textValue())) {
                return semanticFailure(
                        "BINDING_PLAN_FINGERPRINT",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BINDING_FINGERPRINT_TAMPERED");
            }
        } catch (RuntimeException invalidMaterial) {
            return semanticFailure(
                    "BINDING_PLAN_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_BINDING_FINGERPRINT_UNAVAILABLE");
        }
        return valid("BINDING_PLAN_FINGERPRINT");
    }

    private static VerificationResult verifyProjectionFingerprint(JsonNode projection) {
        if (!(projection instanceof ObjectNode object)) {
            return semanticFailure(
                    "PROJECTION_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PROJECTION_FINGERPRINT_UNAVAILABLE");
        }
        ObjectNode material = object.deepCopy();
        material.remove("projectionFingerprint");
        try {
            String expected = EvidenceVerificationSupport.sha256Bounded(
                    material, MAX_FINGERPRINT_MATERIAL_BYTES);
            if (!expected.equals(object.path("projectionFingerprint").textValue())) {
                return semanticFailure(
                        "PROJECTION_FINGERPRINT",
                        "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PROJECTION_FINGERPRINT_TAMPERED");
            }
        } catch (RuntimeException invalidMaterial) {
            return semanticFailure(
                    "PROJECTION_FINGERPRINT",
                    "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PROJECTION_FINGERPRINT_UNAVAILABLE");
        }
        return valid("PROJECTION_FINGERPRINT");
    }

    private static boolean hasKind(JsonNode object, String field, String expected) {
        return expected.equals(object.path(field).path("kind").textValue());
    }

    private static boolean sameText(JsonNode left, JsonNode right, String leftField,
                                    String rightField) {
        return left.path(leftField).isTextual()
                && left.path(leftField).textValue().equals(right.path(rightField).textValue());
    }

    private static String referenceKey(JsonNode reference) {
        return reference.path("kind").textValue() + "\u0000"
                + reference.path("id").textValue() + "\u0000"
                + reference.path("revision").longValue() + "\u0000"
                + reference.path("fingerprint").textValue();
    }

    private static String referenceCoordinate(JsonNode reference) {
        return reference.path("id").textValue() + "\u0000"
                + reference.path("revision").longValue() + "\u0000"
                + reference.path("fingerprint").textValue();
    }

    private static VerificationResult payloadLeak() {
        return semanticFailure(
                "STRUCTURE_ONLY_PAYLOAD_BOUNDARY",
                "RG.CAPABILITY_STUDIO.GOVERNED_RUN_EVIDENCE_PAYLOAD_LEAK");
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(
                FailureKind.NONE, new LinkedHashSet<>(List.of(checks)), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }
}
