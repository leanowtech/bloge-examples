package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independently verifies the strict, payload-free Capability Studio Stage Acceptance Result v2.
 *
 * <p>Version 2 is a stage-exit contract. It keeps v1 independent, binds the candidate execution
 * to a contract identity, and recomputes one evidence closure from the immutable coordinates
 * that were available before signoff. Non-PASS results may honestly omit proof projections and
 * explain that absence with stable diagnostics.</p>
 *
 * <p>This is Schema and semantic verification only. It does not resolve external evidence, verify
 * public-key signatures, or establish issuer and authority permissions. A verified result alone
 * is not formal Stage PASS.</p>
 *
 * <p>{@code BLOCKED} supports either a pre-execution block with null execution times or an
 * in-execution block with a complete valid time window; the two forms cannot be mixed.</p>
 *
 * <p>AC-STD projection states are checked against the corresponding candidate, environment,
 * egress, and signoff facts before the general result state machine is accepted. In particular,
 * AC-STD-01 cannot pass without a clean candidate and environment attestation, AC-STD-06 cannot
 * pass without a zero-call, zero-denial PASS egress observation, and AC-STD-09 cannot pass without
 * all required approved roles.</p>
 */
public final class CapabilityStudioStageAcceptanceResultV2Verifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_RESULT_BYTES = 4 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> AC_STD_IDS = exactIds(
            "AC-STD-01", "AC-STD-02", "AC-STD-03", "AC-STD-04", "AC-STD-05",
            "AC-STD-06", "AC-STD-07", "AC-STD-08", "AC-STD-09");
    private static final Set<String> REQUIRED_SIGNOFF_ROLES = exactIds(
            "CORRECTNESS_OWNER", "RUNTIME_OWNER", "QA_OWNER");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(payload|secret|credential|password|token|request|response|body|header).*",
            Pattern.UNICODE_CASE);

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** Every schema and semantic check passed. */
        NONE,
        /** The wire document violates the strict JSON Schema or payload-free boundary. */
        SCHEMA,
        /** The wire document is schema-valid but violates an evidence closure invariant. */
        SEMANTIC
    }

    /**
     * Payload-free verification result suitable for CI and acceptance ledgers.
     *
     * @param failureKind stable schema or semantic failure classification
     * @param checks immutable names of checks completed before returning
     * @param errorCode stable protocol error code, or {@code null} when verified
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode) {
        /** Creates an immutable result and rejects non-protocol error codes. */
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
         * Determines whether schema and every semantic gate passed.
         *
         * @return true only when the result is fully verified
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioStageAcceptanceResultV2Verifier() {
    }

    /**
     * Verifies a decoded v2 result against the current clock.
     *
     * @param result decoded protocol document
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode result) {
        return verify(result, Instant.now());
    }

    /**
     * Verifies a decoded v2 result against an explicit clock for deterministic callers.
     *
     * @param result decoded protocol document
     * @param now verification instant used for attestation expiry
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode result, Instant now) {
        if (result == null) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NULL");
        }
        if (now == null) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOCK_INVALID");
        }
        if (containsSensitiveField(result)) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SENSITIVE_FIELD");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    result, CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_RESULT_V2_RESOURCE)
                    .isEmpty()) {
                return schemaFailure(
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SCHEMA_UNAVAILABLE");
        }

        VerificationResult checks = verifyAcceptanceChecks(result.path("acceptanceChecks"));
        if (!checks.verified()) {
            return checks;
        }
        VerificationResult binding = verifyCandidateExecutionBinding(result, now);
        if (!binding.verified()) {
            return binding;
        }
        VerificationResult environment = verifyEnvironmentAttestation(result, now);
        if (!environment.verified()) {
            return environment;
        }
        VerificationResult egress = verifyDeploymentEgress(result);
        if (!egress.verified()) {
            return egress;
        }
        VerificationResult evidence = verifyEvidenceClosure(result);
        if (!evidence.verified()) {
            return evidence;
        }
        VerificationResult signoffs = verifySignoffClosure(result);
        if (!signoffs.verified()) {
            return signoffs;
        }
        VerificationResult state = verifyStateMachine(result);
        if (!state.verified()) {
            return state;
        }
        VerificationResult diagnostics = verifyDiagnostics(result);
        if (!diagnostics.verified()) {
            return diagnostics;
        }
        VerificationResult gate = verifyPassGate(result, now);
        if (!gate.verified()) {
            return gate;
        }
        return valid(
                "SCHEMA",
                "PAYLOAD_FREE",
                "AC_STD_EXACT_SET",
                "CANDIDATE_EXECUTION_BINDING",
                "ENVIRONMENT_ATTESTATION",
                "DEPLOYMENT_EGRESS",
                "EVIDENCE_CLOSURE",
                "SIGNOFF_CLOSURE",
                "STATUS_STATE_MACHINE",
                "STAGE_EXIT_GATE");
    }

    /**
     * Verifies one UTF-8 wire document using the current clock.
     *
     * @param wireBytes UTF-8 protocol document
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        return verify(wireBytes, Instant.now());
    }

    /**
     * Verifies one UTF-8 wire document against an explicit clock.
     *
     * @param wireBytes UTF-8 protocol document
     * @param now verification instant used for attestation expiry
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes, Instant now) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_RESULT_BYTES) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes), now);
        } catch (IOException | RuntimeException invalidJson) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_INVALID_JSON");
        }
    }

    private static VerificationResult verifyAcceptanceChecks(JsonNode checks) {
        if (!checks.isArray() || checks.size() != AC_STD_IDS.size()) {
            return semanticFailure(
                    "AC_STD_EXACT_SET",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_SET_INVALID");
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode check : checks) {
            String id = check.path("checkId").textValue();
            if (id == null || byId.put(id, check) != null) {
                return semanticFailure(
                        "AC_STD_EXACT_SET",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_DUPLICATE");
            }
        }
        if (!byId.keySet().equals(AC_STD_IDS)) {
            return semanticFailure(
                    "AC_STD_EXACT_SET",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_SET_INVALID");
        }
        return valid("AC_STD_EXACT_SET");
    }

    private static VerificationResult verifyCandidateExecutionBinding(
            JsonNode result, Instant now) {
        JsonNode binding = result.path("candidateExecutionBinding");
        JsonNode build = binding.path("candidateBuild");
        Instant started = instant(binding.path("executionStartedAt"));
        Instant completed = instant(binding.path("evidenceCompletedAt"));
        Instant decided = instant(result.path("decidedAt"));
        String status = result.path("status").textValue();
        String sourceTreeStatus = build.path("sourceTreeStatus").textValue();
        if (decided == null || decided.isAfter(now)) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_DECISION_IN_FUTURE");
        }
        boolean noExecutionWindow = started == null && completed == null;
        boolean partialExecutionWindow = (started == null) != (completed == null);
        if ("NOT_RUN".equals(status)) {
            if (started != null || completed != null) {
                return semanticFailure(
                        "CANDIDATE_EXECUTION_BINDING",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NOT_RUN_TIMES_MUST_BE_NULL");
            }
        } else if ("BLOCKED".equals(status) && partialExecutionWindow) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BLOCKED_TIMES_MUST_BOTH_BE_NULL_OR_SET");
        } else if (!noExecutionWindow && (started == null || completed == null
                || completed.isBefore(started) || decided.isBefore(completed))) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BINDING_TIME_INVALID");
        } else if (!"BLOCKED".equals(status) && noExecutionWindow) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BINDING_TIME_INVALID");
        }
        if ("PASS".equals(status) && !"CLEAN".equals(sourceTreeStatus)) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_CANDIDATE_NOT_CLEAN");
        }
        JsonNode attestation = result.path("environmentAttestation");
        if (attestation.isObject()
                && !fingerprintEquals(
                build.path("artifactFingerprint"),
                attestation.path("candidateArtifactFingerprint"))) {
            return semanticFailure(
                    "CANDIDATE_EXECUTION_BINDING",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CANDIDATE_ARTIFACT_MISMATCH");
        }
        return valid("CANDIDATE_EXECUTION_BINDING");
    }

    private static VerificationResult verifyEnvironmentAttestation(JsonNode result, Instant now) {
        JsonNode attestation = result.path("environmentAttestation");
        if (attestation.isNull()) {
            return "PASS".equals(result.path("status").textValue())
                    ? semanticFailure(
                    "ENVIRONMENT_ATTESTATION",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_REQUIRED_FOR_PASS")
                    : valid("ENVIRONMENT_ATTESTATION");
        }
        JsonNode binding = result.path("candidateExecutionBinding");
        Instant executionStarted = instant(binding.path("executionStartedAt"));
        Instant evidenceCompleted = instant(binding.path("evidenceCompletedAt"));
        Instant issued = instant(attestation.path("issuedAt"));
        Instant expires = instant(attestation.path("expiresAt"));
        if (executionStarted == null || evidenceCompleted == null
                || issued == null || expires == null || !expires.isAfter(issued)
                || issued.isAfter(executionStarted) || expires.isBefore(evidenceCompleted)) {
            return semanticFailure(
                    "ENVIRONMENT_ATTESTATION",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_WINDOW_INVALID");
        }
        if (!sameText(attestation, "environmentFingerprint", binding, "environmentFingerprint")
                || !fingerprintEquals(attestation.path("candidateArtifactFingerprint"),
                binding.path("candidateBuild").path("artifactFingerprint"))) {
            return semanticFailure(
                    "ENVIRONMENT_ATTESTATION",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_BINDING_MISMATCH");
        }
        if ("PASS".equals(result.path("status").textValue()) && !expires.isAfter(now)) {
            return semanticFailure(
                    "ENVIRONMENT_ATTESTATION",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_EXPIRED");
        }
        return valid("ENVIRONMENT_ATTESTATION");
    }

    private static VerificationResult verifyDeploymentEgress(JsonNode result) {
        JsonNode egress = result.path("deploymentEgressObservation");
        if (egress.isNull()) {
            return "PASS".equals(result.path("status").textValue())
                    ? semanticFailure(
                    "DEPLOYMENT_EGRESS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_REQUIRED_FOR_PASS")
                    : valid("DEPLOYMENT_EGRESS");
        }
        JsonNode binding = result.path("candidateExecutionBinding");
        Instant started = instant(egress.path("observationStartedAt"));
        Instant completed = instant(egress.path("observationCompletedAt"));
        Instant executionStarted = instant(binding.path("executionStartedAt"));
        Instant evidenceCompleted = instant(binding.path("evidenceCompletedAt"));
        Instant decided = instant(result.path("decidedAt"));
        if (started == null || completed == null || completed.isBefore(started)
                || executionStarted == null || evidenceCompleted == null || decided == null
                || started.isAfter(executionStarted) || completed.isBefore(evidenceCompleted)
                || completed.isAfter(decided)) {
            return semanticFailure(
                    "DEPLOYMENT_EGRESS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_WINDOW_INVALID");
        }
        if (!fingerprintEquals(egress.path("candidateIntentFingerprint"),
                binding.path("candidateIntentFingerprint"))) {
            return semanticFailure(
                    "DEPLOYMENT_EGRESS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_BINDING_MISMATCH");
        }
        if ("PASS".equals(egress.path("status").textValue())
                && egress.path("observedExternalCallCount").intValue() != 0) {
            return semanticFailure(
                    "DEPLOYMENT_EGRESS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_EXTERNAL_CALLS_NONZERO");
        }
        if ("PASS".equals(egress.path("status").textValue())
                && egress.path("deniedAttemptCount").intValue() != 0) {
            return semanticFailure(
                    "DEPLOYMENT_EGRESS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_DENIED_ATTEMPTS_NONZERO");
        }
        return valid("DEPLOYMENT_EGRESS");
    }

    private static VerificationResult verifyEvidenceClosure(JsonNode result) {
        Map<String, JsonNode> catalog = new LinkedHashMap<>();
        for (JsonNode evidence : result.path("evidenceRefs")) {
            String evidenceId = evidence.path("evidenceId").textValue();
            if (evidenceId == null || catalog.put(evidenceId, evidence) != null) {
                return semanticFailure(
                        "EVIDENCE_CLOSURE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EVIDENCE_CATALOG_DUPLICATE");
            }
        }
        JsonNode attestation = result.path("environmentAttestation");
        if (attestation.isObject() && !catalogContains(catalog, attestation)) {
            return semanticFailure(
                    "EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_EVIDENCE_UNRESOLVED");
        }
        JsonNode egress = result.path("deploymentEgressObservation");
        if (egress.isObject() && !catalogContains(catalog, egress)) {
            return semanticFailure(
                    "EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EGRESS_EVIDENCE_UNRESOLVED");
        }
        for (JsonNode check : result.path("acceptanceChecks")) {
            for (JsonNode evidenceId : check.path("evidenceIds")) {
                if (!catalog.containsKey(evidenceId.textValue())) {
                    return semanticFailure(
                            "EVIDENCE_CLOSURE",
                            "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EVIDENCE_ID_UNRESOLVED");
                }
            }
        }
        String expected;
        try {
            expected = closureFingerprint(result);
        } catch (RuntimeException canonicalizationFailure) {
            return semanticFailure(
                    "EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_CANONICALIZATION_FAILED");
        }
        if (!expected.equals(result.path("evidenceClosureFingerprint").textValue())) {
            return semanticFailure(
                    "EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CLOSURE_FINGERPRINT_MISMATCH");
        }
        return valid("EVIDENCE_CLOSURE");
    }

    private static VerificationResult verifySignoffClosure(JsonNode result) {
        String closure = result.path("evidenceClosureFingerprint").textValue();
        Map<String, JsonNode> byRole = new LinkedHashMap<>();
        for (JsonNode signoff : result.path("signoffs")) {
            String role = signoff.path("role").textValue();
            if (role == null || byRole.put(role, signoff) != null) {
                return semanticFailure(
                        "SIGNOFF_CLOSURE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_SET_INVALID");
            }
            if (!fingerprintEquals(signoff.path("evidenceClosureFingerprint"), closure)) {
                return semanticFailure(
                        "SIGNOFF_CLOSURE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_CLOSURE_MISMATCH");
            }
        }
        if ("PASS".equals(result.path("status").textValue())
                && !byRole.keySet().containsAll(REQUIRED_SIGNOFF_ROLES)) {
            return semanticFailure(
                    "SIGNOFF_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_SET_INVALID");
        }
        return valid("SIGNOFF_CLOSURE");
    }

    private static VerificationResult verifyStateMachine(JsonNode result) {
        String status = result.path("status").textValue();
        boolean allPass = true;
        boolean hasExpectedFailure = false;
        Map<String, String> checkStatuses = new LinkedHashMap<>();
        for (JsonNode check : result.path("acceptanceChecks")) {
            String checkId = check.path("checkId").textValue();
            String checkStatus = check.path("status").textValue();
            checkStatuses.put(checkId, checkStatus);
            allPass &= "PASS".equals(checkStatus);
            hasExpectedFailure |= status.equals(checkStatus);
        }
        VerificationResult projection = verifyProjectionConsistency(result, checkStatuses);
        if (!projection.verified()) {
            return projection;
        }
        if ("PASS".equals(status) && !allPass) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_REQUIRES_ALL_AC_STD_PASS");
        }
        if (!"PASS".equals(status) && allPass) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_NON_PASS_REQUIRES_FAILED_CLOSED_CHECK");
        }
        if (!"PASS".equals(status) && !hasExpectedFailure) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_NOT_CLOSED");
        }
        return valid("STATUS_STATE_MACHINE");
    }

    private static VerificationResult verifyProjectionConsistency(
            JsonNode result, Map<String, String> checkStatuses) {
        String ac01 = checkStatuses.get("AC-STD-01");
        String ac06 = checkStatuses.get("AC-STD-06");
        String ac09 = checkStatuses.get("AC-STD-09");
        String sourceTreeStatus = result.path("candidateExecutionBinding")
                .path("candidateBuild").path("sourceTreeStatus").textValue();
        if ((sourceTreeStatus == null || !"CLEAN".equals(sourceTreeStatus)
                || result.path("environmentAttestation").isNull())
                && "PASS".equals(ac01)) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_01_PROJECTION_CONTRADICTION");
        }
        JsonNode egress = result.path("deploymentEgressObservation");
        boolean egressCannotPass = egress.isNull()
                || !"PASS".equals(egress.path("status").textValue())
                || egress.path("observedExternalCallCount").intValue() != 0
                || egress.path("deniedAttemptCount").intValue() != 0;
        if (egressCannotPass && "PASS".equals(ac06)) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_06_PROJECTION_CONTRADICTION");
        }
        Map<String, JsonNode> signoffsByRole = new LinkedHashMap<>();
        for (JsonNode signoff : result.path("signoffs")) {
            signoffsByRole.put(signoff.path("role").textValue(), signoff);
        }
        boolean signoffsCannotPass = !signoffsByRole.keySet().containsAll(REQUIRED_SIGNOFF_ROLES)
                || signoffsByRole.values().stream()
                .anyMatch(signoff -> !"APPROVED".equals(signoff.path("decision").textValue()));
        if (signoffsCannotPass && "PASS".equals(ac09)) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_AC_STD_09_PROJECTION_CONTRADICTION");
        }
        return valid("STATUS_STATE_MACHINE");
    }

    private static VerificationResult verifyDiagnostics(JsonNode result) {
        if ("PASS".equals(result.path("status").textValue())) {
            return valid("STATUS_STATE_MACHINE");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode diagnostic : result.path("diagnostics")) {
            codes.add(diagnostic.path("code").textValue());
        }
        String status = result.path("status").textValue();
        if ("FAIL".equals(status) && !codes.contains("ACCEPTANCE_CHECK_FAILED")
                || "BLOCKED".equals(status) && !codes.contains("ACCEPTANCE_CHECK_BLOCKED")
                || "NOT_RUN".equals(status)
                && (!codes.contains("RUN_NOT_STARTED")
                || !codes.contains("ACCEPTANCE_CHECK_NOT_RUN"))) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_STATUS_DIAGNOSTIC_MISSING");
        }
        Instant executionStarted = instant(result.path("candidateExecutionBinding")
                .path("executionStartedAt"));
        Instant evidenceCompleted = instant(result.path("candidateExecutionBinding")
                .path("evidenceCompletedAt"));
        boolean noExecutionWindow = executionStarted == null && evidenceCompleted == null;
        if (noExecutionWindow && !codes.contains("RUN_NOT_STARTED")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EXECUTION_DIAGNOSTIC_MISSING");
        }
        if (!noExecutionWindow && codes.contains("RUN_NOT_STARTED")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_EXECUTION_DIAGNOSTIC_CONTRADICTION");
        }
        if ("BLOCKED".equals(status) && noExecutionWindow
                && (!result.path("environmentAttestation").isNull()
                || !result.path("deploymentEgressObservation").isNull())) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_BLOCKED_PRE_EXECUTION_PROJECTION_INVALID");
        }
        String sourceTreeStatus = result.path("candidateExecutionBinding")
                .path("candidateBuild").path("sourceTreeStatus").textValue();
        if (!"CLEAN".equals(sourceTreeStatus)
                && !codes.contains("CANDIDATE_NOT_CLEAN")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_CANDIDATE_DIAGNOSTIC_MISSING");
        }
        if (result.path("environmentAttestation").isNull()
                && !codes.contains("ENVIRONMENT_ATTESTATION_UNAVAILABLE")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_UNAVAILABLE_DIAGNOSTIC_MISSING");
        }
        if (result.path("deploymentEgressObservation").isNull()
                && !codes.contains("DEPLOYMENT_EGRESS_UNAVAILABLE")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_UNAVAILABLE_DIAGNOSTIC_MISSING");
        }
        Set<String> signoffRoles = new LinkedHashSet<>();
        for (JsonNode signoff : result.path("signoffs")) {
            signoffRoles.add(signoff.path("role").textValue());
        }
        if (!signoffRoles.containsAll(REQUIRED_SIGNOFF_ROLES)
                && !codes.contains("SIGNOFFS_UNAVAILABLE")) {
            return semanticFailure(
                    "STATUS_STATE_MACHINE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_UNAVAILABLE_DIAGNOSTIC_MISSING");
        }
        return valid("STATUS_STATE_MACHINE");
    }

    private static VerificationResult verifyPassGate(JsonNode result, Instant now) {
        if (!"PASS".equals(result.path("status").textValue())) {
            return valid("STAGE_EXIT_GATE");
        }
        JsonNode egress = result.path("deploymentEgressObservation");
        if (!"PASS".equals(egress.path("status").textValue())
                || egress.path("observedExternalCallCount").intValue() != 0
                || egress.path("deniedAttemptCount").intValue() != 0) {
            return semanticFailure(
                    "STAGE_EXIT_GATE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_EGRESS_GATE");
        }
        Instant evidenceCompleted = instant(result.path("candidateExecutionBinding")
                .path("evidenceCompletedAt"));
        Instant decided = instant(result.path("decidedAt"));
        for (JsonNode signoff : result.path("signoffs")) {
            if (!"APPROVED".equals(signoff.path("decision").textValue())) {
                return semanticFailure(
                        "STAGE_EXIT_GATE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_PASS_SIGNOFF_GATE");
            }
            Instant signedAt = instant(signoff.path("signedAt"));
            if (signedAt == null || !signedAt.isAfter(evidenceCompleted)
                    || signedAt.isAfter(decided)) {
                return semanticFailure(
                        "STAGE_EXIT_GATE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_SIGNOFF_TIME_INVALID");
            }
        }
        Instant expires = instant(result.path("environmentAttestation").path("expiresAt"));
        if (expires == null || !expires.isAfter(now)) {
            return semanticFailure(
                    "STAGE_EXIT_GATE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_V2_ENVIRONMENT_EXPIRED");
        }
        return valid("STAGE_EXIT_GATE");
    }

    private static boolean catalogContains(Map<String, JsonNode> catalog, JsonNode coordinate) {
        if (!coordinate.isObject()) {
            return false;
        }
        return catalog.values().stream().anyMatch(evidence ->
                Objects.equals(evidence.path("exactRef").textValue(),
                        coordinate.path("exactRef").textValue())
                        && Objects.equals(evidence.path("fingerprint").textValue(),
                        coordinate.path("fingerprint").textValue())
                        && "AVAILABLE".equals(evidence.path("status").textValue()));
    }

    /**
     * Recomputes the v2 closure using the existing deterministic Test Kit canonicalizer.
     *
     * <p>The material is schema identity, result identity and status, contract identity, the
     * complete candidate execution binding, the complete environment and egress projections (or
     * null), checks sorted by AC id with sorted evidence IDs, and the complete top-level evidence
     * catalog sorted by evidence ID. It excludes {@code decidedAt}, all signoffs and signature
     * coordinates, diagnostics, and the claimed closure fingerprint.</p>
     *
     * @param result schema-valid v2 result
     * @return canonical SHA-256 closure fingerprint
     */
    static String closureFingerprint(JsonNode result) {
        return EvidenceVerificationSupport.sha256Bounded(closureMaterial(result), MAXIMUM_RESULT_BYTES);
    }

    static ObjectNode closureMaterial(JsonNode result) {
        ObjectNode material = JSON.createObjectNode();
        material.put("schemaVersion", result.path("schemaVersion").textValue());
        material.put("resultId", result.path("resultId").textValue());
        material.put("revision", result.path("revision").intValue());
        material.put("resultKind", result.path("resultKind").textValue());
        material.put("status", result.path("status").textValue());
        material.set("candidateExecutionBinding",
                result.path("candidateExecutionBinding").deepCopy());
        material.put("contractId", result.path("contractId").textValue());
        material.put("contractRevision", result.path("contractRevision").textValue());
        material.set("environmentAttestation", objectOrNull(
                result.path("environmentAttestation")));
        material.set("deploymentEgressObservation", objectOrNull(
                result.path("deploymentEgressObservation")));

        List<JsonNode> checks = new ArrayList<>();
        result.path("acceptanceChecks").forEach(checks::add);
        checks.sort(Comparator.comparing(check -> check.path("checkId").textValue()));
        ArrayNode checkMaterial = material.putArray("acceptanceChecks");
        for (JsonNode check : checks) {
            ObjectNode normalized = checkMaterial.addObject();
            normalized.put("checkId", check.path("checkId").textValue());
            normalized.put("status", check.path("status").textValue());
            List<String> evidenceIds = new ArrayList<>();
            check.path("evidenceIds").forEach(id -> evidenceIds.add(id.textValue()));
            evidenceIds.sort(Comparator.naturalOrder());
            ArrayNode ids = normalized.putArray("evidenceIds");
            evidenceIds.forEach(ids::add);
        }

        List<JsonNode> evidence = new ArrayList<>();
        result.path("evidenceRefs").forEach(evidence::add);
        evidence.sort(Comparator.comparing(item -> item.path("evidenceId").textValue()));
        ArrayNode catalog = material.putArray("evidenceRefs");
        for (JsonNode item : evidence) {
            ObjectNode normalized = catalog.addObject();
            normalized.put("evidenceId", item.path("evidenceId").textValue());
            normalized.put("exactRef", item.path("exactRef").textValue());
            normalized.put("fingerprint", item.path("fingerprint").textValue());
            normalized.put("status", item.path("status").textValue());
        }
        return material;
    }

    private static JsonNode objectOrNull(JsonNode value) {
        if (!value.isObject()) {
            return JSON.nullNode();
        }
        return value.deepCopy();
    }

    private static boolean sameText(
            JsonNode left, String leftField, JsonNode right, String rightField) {
        return Objects.equals(left.path(leftField).textValue(), right.path(rightField).textValue());
    }

    private static boolean fingerprintEquals(JsonNode left, JsonNode right) {
        return left.isTextual() && right.isTextual() && left.textValue().equals(right.textValue());
    }

    private static boolean fingerprintEquals(JsonNode left, String right) {
        return left.isTextual() && right != null && left.textValue().equals(right);
    }

    private static Instant instant(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(value.textValue());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean containsSensitiveField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (SENSITIVE_FIELD.matcher(field.getKey()).matches()
                        || containsSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> exactIds(String... values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Collections.addAll(ids, values);
        return Collections.unmodifiableSet(ids);
    }

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, orderedSet(checks), null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode);
    }

    private static Set<String> orderedSet(String... values) {
        LinkedHashSet<String> checks = new LinkedHashSet<>();
        Collections.addAll(checks, values);
        return checks;
    }
}
