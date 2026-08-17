package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independently verifies the strict, payload-free Capability Studio Stage Acceptance Result v1.
 *
 * <p>The verifier enforces the acceptance contract from section 13.1 of the Capability Studio
 * evolution plan. It validates both the packaged JSON Schema and the cross-field state machine
 * for {@code NOT_RUN}, {@code BLOCKED}, {@code FAIL}, {@code PARTIAL}, and {@code PASS}. It is
 * independent of Resource Gateway server classes and returns no instance values, paths, or
 * business payloads.</p>
 */
public final class CapabilityStudioStageAcceptanceResultVerifier {
    /** Maximum UTF-8 wire document accepted before parsing. */
    public static final int MAXIMUM_RESULT_BYTES = 4 * 1024 * 1024;

    private static final String OWNER_ROLE = "OWNER";
    private static final Set<String> REQUIRED_PRECONDITIONS = Set.of(
            "AC-PRE-01", "AC-PRE-02", "AC-PRE-03", "AC-PRE-04", "AC-PRE-05");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(payload|secret|token|password|request|response|body|header).*");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** Every schema and semantic check passed. */
        NONE,
        /** The wire document violates the strict JSON Schema or payload-free boundary. */
        SCHEMA,
        /** The wire document is schema-valid but violates an acceptance state invariant. */
        SEMANTIC
    }

    /**
     * Payload-free verification result suitable for CI and acceptance ledgers.
     *
     * @param failureKind stable failure classification
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
         * Returns true only when all required structural and semantic checks passed.
         *
         * @return whether the result is verified
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioStageAcceptanceResultVerifier() {
    }

    /**
     * Verifies one decoded Stage Acceptance Result v1 document.
     *
     * @param result decoded protocol document
     * @return payload-free verification result with stable checks and error code
     */
    public VerificationResult verify(JsonNode result) {
        if (result == null) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_NULL");
        }
        if (containsSensitiveField(result)) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SENSITIVE_FIELD");
        }
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    result, CapabilityStudioSchemaSupport.STAGE_ACCEPTANCE_RESULT_RESOURCE)
                    .isEmpty()) {
                return schemaFailure(
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure(
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SCHEMA_UNAVAILABLE");
        }

        VerificationResult preconditions = verifyPreconditions(result.path("preconditions"));
        if (!preconditions.verified()) {
            return preconditions;
        }
        VerificationResult status = verifyStatus(result);
        if (!status.verified()) {
            return status;
        }
        return valid(
                "SCHEMA",
                "PAYLOAD_FREE",
                "PRECONDITION_CLOSURE",
                "STATUS_SEMANTICS",
                "STAGE_KIND_POLICY",
                "TIME_ORDER",
                "MATRIX_CLOSURE",
                "AUTOMATION_COMMANDS",
                "OBSERVATIONS",
                "EVIDENCE_AVAILABILITY",
                "OPEN_BLOCKER_GATE",
                "OWNER_SIGN_OFF");
    }

    /**
     * Verifies one UTF-8 wire document with a size limit applied before parsing.
     *
     * @param wireBytes UTF-8 protocol document
     * @return payload-free verification result with stable checks and error code
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_RESULT_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_INVALID_JSON");
        }
    }

    private static VerificationResult verifyPreconditions(JsonNode preconditions) {
        if (!preconditions.isArray() || preconditions.size() != REQUIRED_PRECONDITIONS.size()) {
            return semanticFailure(
                    "PRECONDITION_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_CARDINALITY");
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode precondition : preconditions) {
            String id = precondition.path("preconditionId").textValue();
            if (id == null || byId.put(id, precondition) != null) {
                return semanticFailure(
                        "PRECONDITION_CLOSURE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_DUPLICATE");
            }
        }
        if (!byId.keySet().equals(REQUIRED_PRECONDITIONS)) {
            return semanticFailure(
                    "PRECONDITION_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_SET");
        }
        for (JsonNode precondition : byId.values()) {
            if (!hasEvidenceShape(precondition.path("evidenceRefs"))) {
                return semanticFailure(
                        "PRECONDITION_CLOSURE",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PRECONDITION_EVIDENCE");
            }
        }
        return valid("PRECONDITION_CLOSURE");
    }

    private static VerificationResult verifyStatus(JsonNode result) {
        String status = result.path("status").textValue();
        String kind = result.path("resultKind").textValue();
        JsonNode preconditions = result.path("preconditions");
        JsonNode matrix = result.path("executedMatrix");
        JsonNode observations = result.path("observations");

        if ("PARTIAL".equals(status) && !"DEVELOPMENT_LEDGER".equals(kind)) {
            return semanticFailure(
                    "STAGE_KIND_POLICY",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PARTIAL_STAGE_EXIT");
        }
        if ("NOT_RUN".equals(status)) {
            if (hasCompletedMatrix(matrix) || !result.path("completedAt").isNull()) {
                return semanticFailure(
                        "STATUS_SEMANTICS",
                        "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_NOT_RUN_HAS_COMPLETION");
            }
            return valid("STATUS_SEMANTICS", "STAGE_KIND_POLICY");
        }
        if ("BLOCKED".equals(status) && !hasPrecondition(preconditions, "BLOCKED", "NOT_RUN")) {
            return semanticFailure(
                    "STATUS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_BLOCKED_WITHOUT_PRECONDITION");
        }
        if ("FAIL".equals(status)
                && !hasPrecondition(preconditions, "FAIL")
                && !hasObservation(observations, "FAIL")) {
            return semanticFailure(
                    "STATUS_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_FAIL_WITHOUT_FAILURE");
        }
        if ("PARTIAL".equals(status)) {
            return verifyPartial(result);
        }
        if ("PASS".equals(status)) {
            return verifyPass(result);
        }
        return valid("STATUS_SEMANTICS", "STAGE_KIND_POLICY");
    }

    private static VerificationResult verifyPartial(JsonNode result) {
        JsonNode matrix = result.path("executedMatrix");
        if (matrix.isEmpty() || !hasExecutionStatus(matrix, "EXECUTED")
                || allExecutionStatus(matrix, "EXECUTED")
                || !hasAvailableEvidence(result.path("evidenceRefs"))) {
            return semanticFailure(
                    "MATRIX_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PARTIAL_NOT_SUBSTANTIATED");
        }
        return valid("STATUS_SEMANTICS", "STAGE_KIND_POLICY", "MATRIX_CLOSURE",
                "EVIDENCE_AVAILABILITY");
    }

    private static VerificationResult verifyPass(JsonNode result) {
        if (!allPreconditionsPass(result.path("preconditions"))) {
            return semanticFailure(
                    "PRECONDITION_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_PRECONDITION");
        }
        if (!validTimeOrder(result.path("startedAt"), result.path("completedAt"))) {
            return semanticFailure(
                    "TIME_ORDER",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_TIME_INVALID");
        }
        if (!hasCompletedMatrix(result.path("executedMatrix"))
                || !allMatrixEntriesComplete(result.path("executedMatrix"))) {
            return semanticFailure(
                    "MATRIX_CLOSURE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_MATRIX_INCOMPLETE");
        }
        if (!hasNonEmptyStrings(result.path("automationCommands"))) {
            return semanticFailure(
                    "AUTOMATION_COMMANDS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_COMMANDS_EMPTY");
        }
        if (!hasObservationsPassing(result.path("observations"))) {
            return semanticFailure(
                    "OBSERVATIONS",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_OBSERVATION_INVALID");
        }
        if (!hasAvailableEvidence(result.path("evidenceRefs"))
                || !allLinkedEvidenceAvailable(result)) {
            return semanticFailure(
                    "EVIDENCE_AVAILABILITY",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_EVIDENCE_INVALID");
        }
        if (hasOpenBlocker(result.path("openIssues"))) {
            return semanticFailure(
                    "OPEN_BLOCKER_GATE",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_OPEN_BLOCKER");
        }
        if (!hasApprovedOwnerSignOff(result.path("owner"), result.path("signOffs"))) {
            return semanticFailure(
                    "OWNER_SIGN_OFF",
                    "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_RESULT_PASS_OWNER_SIGN_OFF");
        }
        return valid(
                "STATUS_SEMANTICS",
                "PRECONDITION_CLOSURE",
                "TIME_ORDER",
                "MATRIX_CLOSURE",
                "AUTOMATION_COMMANDS",
                "OBSERVATIONS",
                "EVIDENCE_AVAILABILITY",
                "OPEN_BLOCKER_GATE",
                "OWNER_SIGN_OFF");
    }

    private static boolean allPreconditionsPass(JsonNode preconditions) {
        for (JsonNode precondition : preconditions) {
            if (!"PASS".equals(precondition.path("status").textValue())
                    || !allEvidenceAvailable(precondition.path("evidenceRefs"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allLinkedEvidenceAvailable(JsonNode result) {
        for (JsonNode precondition : result.path("preconditions")) {
            if (!allEvidenceAvailable(precondition.path("evidenceRefs"))) {
                return false;
            }
        }
        for (JsonNode entry : result.path("executedMatrix")) {
            if (!allEvidenceAvailable(entry.path("evidenceRefs"))) {
                return false;
            }
        }
        for (JsonNode observation : result.path("observations")) {
            if (!allEvidenceAvailable(observation.path("evidenceRefs"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEvidenceAvailable(JsonNode evidenceRefs) {
        if (evidenceRefs == null || !evidenceRefs.isArray()) {
            return false;
        }
        for (JsonNode evidence : evidenceRefs) {
            if (!"AVAILABLE".equals(evidence.path("status").textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEvidenceShape(JsonNode evidenceRefs) {
        if (evidenceRefs == null || !evidenceRefs.isArray() || evidenceRefs.isEmpty()) {
            return false;
        }
        for (JsonNode evidence : evidenceRefs) {
            if (!evidence.path("exactRef").isTextual()
                    || !evidence.path("fingerprint").isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAvailableEvidence(JsonNode evidenceRefs) {
        return evidenceRefs.isArray() && !evidenceRefs.isEmpty()
                && allEvidenceAvailable(evidenceRefs);
    }

    private static boolean hasCompletedMatrix(JsonNode matrix) {
        if (!matrix.isArray() || matrix.isEmpty()) {
            return false;
        }
        return hasExecutionStatus(matrix, "EXECUTED") || hasExecutionStatus(matrix, "FAILED");
    }

    private static boolean allMatrixEntriesComplete(JsonNode matrix) {
        if (!matrix.isArray() || matrix.isEmpty()) {
            return false;
        }
        for (JsonNode entry : matrix) {
            if (!"EXECUTED".equals(entry.path("executionStatus").textValue())
                    || !validTimeOrder(entry.path("startedAt"), entry.path("completedAt"))
                    || !hasAvailableEvidence(entry.path("evidenceRefs"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExecutionStatus(JsonNode matrix, String expected) {
        for (JsonNode entry : matrix) {
            if (expected.equals(entry.path("executionStatus").textValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean allExecutionStatus(JsonNode matrix, String expected) {
        if (!matrix.isArray() || matrix.isEmpty()) {
            return false;
        }
        for (JsonNode entry : matrix) {
            if (!expected.equals(entry.path("executionStatus").textValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasPrecondition(JsonNode preconditions, String... statuses) {
        Set<String> expected = Set.of(statuses);
        for (JsonNode precondition : preconditions) {
            if (expected.contains(precondition.path("status").textValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObservation(JsonNode observations, String expectedStatus) {
        for (JsonNode observation : observations) {
            if (expectedStatus.equals(observation.path("status").textValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasObservationsPassing(JsonNode observations) {
        if (!observations.isArray() || observations.isEmpty()) {
            return false;
        }
        for (JsonNode observation : observations) {
            if (!"PASS".equals(observation.path("status").textValue())
                    || !hasAvailableEvidence(observation.path("evidenceRefs"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNonEmptyStrings(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOpenBlocker(JsonNode issues) {
        for (JsonNode issue : issues) {
            boolean open = "OPEN".equals(issue.path("status").textValue());
            boolean highSeverity = "P0".equals(issue.path("severity").textValue())
                    || "P1".equals(issue.path("severity").textValue());
            if (open && (highSeverity || issue.path("blocker").asBoolean(false))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasApprovedOwnerSignOff(JsonNode owner, JsonNode signOffs) {
        String ownerActor = owner.path("actor").textValue();
        boolean found = false;
        for (JsonNode signOff : signOffs) {
            if (!OWNER_ROLE.equals(signOff.path("role").textValue())) {
                continue;
            }
            if (!ownerActor.equals(signOff.path("actor").textValue())
                    || !"APPROVED".equals(signOff.path("decision").textValue())
                    || signOff.path("signature").asText().isBlank()
                    || !parseInstant(signOff.path("timestamp").textValue()).isPresent()) {
                return false;
            }
            found = true;
        }
        return found;
    }

    private static boolean validTimeOrder(JsonNode startedAt, JsonNode completedAt) {
        if (!startedAt.isTextual() || !completedAt.isTextual()) {
            return false;
        }
        Instant started = parseInstant(startedAt.textValue()).orElse(null);
        Instant completed = parseInstant(completedAt.textValue()).orElse(null);
        return started != null && completed != null && !completed.isBefore(started);
    }

    private static java.util.Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Instant.parse(value));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
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
