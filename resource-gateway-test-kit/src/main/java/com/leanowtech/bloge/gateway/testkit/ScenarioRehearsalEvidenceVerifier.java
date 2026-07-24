package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Dependency-light offline verifier for signed Scenario rehearsal aggregate evidence.
 *
 * <p>The verifier trusts neither producer outcome labels nor a detached signature alone. It
 * applies the packaged strict Schema, re-derives every assertion, case, aggregate, and bundle
 * content address, checks the ordered identity/outcome/summary closure, applies public-key
 * lifecycle policy, and verifies the domain-separated Ed25519 signature. It has no dependency on
 * Spring or Resource Gateway server classes.</p>
 */
public final class ScenarioRehearsalEvidenceVerifier {
    /** Maximum canonical bytes admitted for one assertion result. */
    public static final int MAXIMUM_ASSERTION_BYTES = 256 * 1024;
    /** Maximum canonical bytes admitted for one case result. */
    public static final int MAXIMUM_CASE_BYTES = 512 * 1024;
    /** Maximum canonical bytes admitted for one aggregate result. */
    public static final int MAXIMUM_RESULT_BYTES = 160 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one portable bundle. */
    public static final int MAXIMUM_BUNDLE_BYTES = 168 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES = 8 * 1024;
    private static final int MAXIMUM_RUN_ID_MATERIAL_BYTES = 16 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_EVIDENCE_V1";
    private static final String RUN_ID_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1";

    /** Creates an offline verifier with the fixed Scenario v1 policy. */
    public ScenarioRehearsalEvidenceVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Structure, closures, fingerprints, key policy, and signature all passed. */
        VERIFIED,
        /** Structure, closure, fingerprint, or signature is invalid. */
        INVALID,
        /** The attestation public key was not supplied. */
        KEY_UNAVAILABLE,
        /** Public-key lifecycle or signature algorithm policy rejects the evidence. */
        POLICY_REJECTED
    }

    /**
     * Payload-free result suitable for CI, workbooks, and publish-gate ingestion.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param runId Scenario aggregate identity, or blank when unavailable
     * @param requestId aggregate idempotency identity, or blank when unavailable
     * @param compiledPlanFingerprint exact compiled plan, or blank when unavailable
     * @param resultFingerprint complete aggregate identity, or blank when unavailable
     * @param bundleFingerprint portable bundle identity, or blank when unavailable
     * @param keyId attestation key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String runId,
            String requestId,
            String compiledPlanFingerprint,
            String resultFingerprint,
            String bundleFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe verification output. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            runId = normalized(runId);
            requestId = normalized(requestId);
            compiledPlanFingerprint =
                    normalized(compiledPlanFingerprint);
            resultFingerprint = normalized(resultFingerprint);
            bundleFingerprint = normalized(bundleFingerprint);
            keyId = normalized(keyId);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Scenario evidence verification result is invalid");
            }
        }

        /**
         * Reports whether every independent verification step passed.
         *
         * @return true only for a fully verified bundle
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded Scenario rehearsal evidence bundle.
     *
     * @param bundle decoded v1 evidence bundle
     * @param key public key resolved from the attestation key id; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode bundle, EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(bundle);
        try {
            CapabilityMirrorSchemaValidator.require(
                    bundle,
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_EVIDENCE_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_EVIDENCE_SCHEMA_INVALID",
                    coordinates);
        }

        JsonNode attestation = bundle.path("attestation");
        JsonNode aggregate = bundle.path("result");
        try {
            verifyResultClosure(aggregate);
            verifyAttestationClosure(attestation, aggregate);
            String actualBundleFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            bundleMaterial(bundle),
                            MAXIMUM_BUNDLE_BYTES);
            if (!actualBundleFingerprint.equals(
                    bundle.path("bundleFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_EVIDENCE_BUNDLE_FINGERPRINT_INVALID",
                        coordinates);
            }
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_EVIDENCE_MATERIAL_INVALID",
                    coordinates);
        }

        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SCENARIO_EVIDENCE_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(attestation.path("keyId").asText())) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_EVIDENCE_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                attestation.path("algorithm").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SCENARIO_EVIDENCE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(
                    attestation.path("signedAt").asText());
        } catch (DateTimeParseException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_EVIDENCE_SIGNING_TIME_INVALID",
                    coordinates);
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(key.createdAt().minus(
                EvidenceVerificationSupport.KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SCENARIO_EVIDENCE_KEY_POLICY_REJECTED",
                    coordinates);
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport.sha256Bounded(
                            signatureMaterial(attestation),
                            MAXIMUM_SIGNATURE_MATERIAL_BYTES);
            if (!EvidenceVerificationSupport.verifyEd25519(
                    materialFingerprint,
                    attestation.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_EVIDENCE_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_EVIDENCE_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static void verifyResultClosure(JsonNode aggregate) {
        verifyFingerprint(
                aggregate, "resultFingerprint",
                MAXIMUM_RESULT_BYTES,
                "SCENARIO_RESULT_FINGERPRINT_INVALID");
        Instant aggregateStarted = instant(
                aggregate.path("startedAt"),
                "SCENARIO_RESULT_TIME_INVALID");
        Instant aggregateCompleted = instant(
                aggregate.path("completedAt"),
                "SCENARIO_RESULT_TIME_INVALID");
        if (aggregateCompleted.isBefore(aggregateStarted)) {
            fail("SCENARIO_RESULT_TIME_INVALID");
        }

        ArrayNode cases = (ArrayNode) aggregate.path("caseResults");
        Set<String> caseRefs = new HashSet<>();
        int passed = 0;
        int failed = 0;
        int indeterminate = 0;
        int assertionCount = 0;
        int blockerFailures = 0;
        int blockerIndeterminate = 0;
        int warningFailures = 0;
        int warningIndeterminate = 0;
        for (int index = 0; index < cases.size(); index++) {
            JsonNode caseResult = cases.get(index);
            verifyCaseClosure(
                    caseResult, index,
                    aggregateStarted, aggregateCompleted);
            if (!caseRefs.add(refCoordinate(
                    caseResult.path("scenarioCaseRef")))) {
                fail("SCENARIO_CASE_IDENTITY_DUPLICATE");
            }
            switch (caseResult.path("outcome").asText()) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "INDETERMINATE" -> indeterminate++;
                default -> fail("SCENARIO_CASE_OUTCOME_INVALID");
            }
            for (JsonNode assertion :
                    caseResult.path("assertionResults")) {
                assertionCount++;
                boolean blocker = "BLOCKER".equals(
                        assertion.path("severity").asText());
                String outcome = assertion.path("outcome").asText();
                if (blocker && "FAIL".equals(outcome)) {
                    blockerFailures++;
                } else if (blocker
                        && "INDETERMINATE".equals(outcome)) {
                    blockerIndeterminate++;
                } else if (!blocker && "FAIL".equals(outcome)) {
                    warningFailures++;
                } else if (!blocker
                        && "INDETERMINATE".equals(outcome)) {
                    warningIndeterminate++;
                }
            }
        }
        JsonNode summary = aggregate.path("summary");
        if (summary.path("totalCases").asInt() != cases.size()
                || summary.path("passedCases").asInt() != passed
                || summary.path("failedCases").asInt() != failed
                || summary.path("indeterminateCases").asInt()
                != indeterminate
                || summary.path("assertionResults").asInt()
                != assertionCount
                || summary.path("blockerFailures").asInt()
                != blockerFailures
                || summary.path("blockerIndeterminate").asInt()
                != blockerIndeterminate
                || summary.path("warningFailures").asInt()
                != warningFailures
                || summary.path("warningIndeterminate").asInt()
                != warningIndeterminate) {
            fail("SCENARIO_RESULT_SUMMARY_INVALID");
        }
        String derivedOutcome = failed > 0
                ? "FAIL"
                : indeterminate > 0 ? "INDETERMINATE" : "PASS";
        if (!derivedOutcome.equals(
                aggregate.path("outcome").asText())) {
            fail("SCENARIO_RESULT_OUTCOME_INVALID");
        }
    }

    private static void verifyCaseClosure(
            JsonNode caseResult,
            int expectedIndex,
            Instant aggregateStarted,
            Instant aggregateCompleted) {
        verifyFingerprint(
                caseResult, "resultFingerprint", MAXIMUM_CASE_BYTES,
                "SCENARIO_CASE_FINGERPRINT_INVALID");
        Instant started = instant(
                caseResult.path("startedAt"),
                "SCENARIO_CASE_TIME_INVALID");
        Instant completed = instant(
                caseResult.path("completedAt"),
                "SCENARIO_CASE_TIME_INVALID");
        if (caseResult.path("caseIndex").asInt(-1) != expectedIndex
                || started.isBefore(aggregateStarted)
                || completed.isBefore(started)
                || completed.isAfter(aggregateCompleted)) {
            fail("SCENARIO_CASE_TIME_OR_ORDER_INVALID");
        }

        String runId = caseResult.path("runId").asText();
        String evidenceFingerprint =
                caseResult.path("evidenceBundleFingerprint").asText();
        JsonNode assertions = caseResult.path("assertionResults");
        boolean hasEvidence = !runId.isBlank();
        if (!hasEvidence) {
            if (!evidenceFingerprint.isBlank()
                    || !caseResult.path("evidenceStatus").isNull()
                    || !caseResult.path("evidenceClass").isNull()
                    || !assertions.isEmpty()
                    || "PASS".equals(
                    caseResult.path("outcome").asText())
                    || caseResult.path("diagnosticCode")
                    .asText().isBlank()) {
                fail("SCENARIO_CASE_PRE_EVIDENCE_CLOSURE_INVALID");
            }
            return;
        }
        if (evidenceFingerprint.isBlank()
                || caseResult.path("evidenceStatus").isNull()
                || caseResult.path("evidenceClass").isNull()
                || assertions.isEmpty()) {
            fail("SCENARIO_CASE_EVIDENCE_CLOSURE_INVALID");
        }
        Set<String> assertionRefs = new HashSet<>();
        for (JsonNode assertion : assertions) {
            verifyFingerprint(
                    assertion, "resultFingerprint",
                    MAXIMUM_ASSERTION_BYTES,
                    "SCENARIO_ASSERTION_FINGERPRINT_INVALID");
            if (!runId.equals(assertion.path("runId").asText())
                    || !evidenceFingerprint.equals(
                    assertion.path(
                            "evidenceBundleFingerprint").asText())
                    || !caseResult.path("mirrorPlanRef")
                    .path("fingerprint").asText()
                    .equals(assertion.path(
                            "planFingerprint").asText())
                    || !assertionRefs.add(refCoordinate(
                    assertion.path("assertionRef")))) {
                fail("SCENARIO_ASSERTION_IDENTITY_CLOSURE_INVALID");
            }
            verifyAssertionOutcome(assertion);
        }
        String derived = deriveCaseOutcome(caseResult);
        String diagnostic =
                caseResult.path("diagnosticCode").asText();
        if (!derived.equals(caseResult.path("outcome").asText())
                || ("PASS".equals(derived)
                ? !diagnostic.isBlank() : diagnostic.isBlank())) {
            fail("SCENARIO_CASE_OUTCOME_INVALID");
        }
    }

    private static void verifyAssertionOutcome(JsonNode assertion) {
        String outcome = assertion.path("outcome").asText();
        String reason = assertion.path("reasonCode").asText();
        boolean valid = switch (outcome) {
            case "PASS" -> "ASSERTION_MATCHED".equals(reason);
            case "FAIL" -> "ASSERTION_MISMATCH".equals(reason)
                    || "ASSERTION_OBSERVATION_ABSENT".equals(reason);
            case "INDETERMINATE" ->
                    "ASSERTION_EVIDENCE_INCOMPLETE".equals(reason)
                            || "ASSERTION_EVIDENCE_FACT_UNAVAILABLE"
                            .equals(reason);
            default -> false;
        };
        if (!valid
                || !orderedUnique(
                assertion.path("observed").path("statuses"))
                || !orderedUnique(
                assertion.path("observed").path("errorCodes"))
                || !orderedUnique(
                assertion.path("observed").path("fingerprints"))
                || !orderedUnique(
                assertion.path("observed").path("sources"))
                || !orderedUnique(
                assertion.path("observed").path("limitations"))) {
            fail("SCENARIO_ASSERTION_SEMANTICS_INVALID");
        }
    }

    private static String deriveCaseOutcome(JsonNode caseResult) {
        String status = caseResult.path("evidenceStatus").asText();
        boolean executionFailed = Set.of(
                "ASSERTION_FAILED", "EXECUTION_FAILED",
                "CONTROL_PLAN_REJECTED", "FIXTURE_UNMATCHED",
                "FIXTURE_UNUSED", "TIMED_OUT").contains(status);
        boolean blockerFailed = false;
        boolean blockerIndeterminate = false;
        for (JsonNode assertion :
                caseResult.path("assertionResults")) {
            if (!"BLOCKER".equals(
                    assertion.path("severity").asText())) {
                continue;
            }
            blockerFailed |= "FAIL".equals(
                    assertion.path("outcome").asText());
            blockerIndeterminate |= "INDETERMINATE".equals(
                    assertion.path("outcome").asText());
        }
        if (executionFailed || blockerFailed) {
            return "FAIL";
        }
        if (Set.of(
                "CONTROL_PLAN_UNAVAILABLE",
                "EVIDENCE_INCOMPLETE",
                "CANCELLED").contains(status)
                || blockerIndeterminate) {
            return "INDETERMINATE";
        }
        return "PASS";
    }

    private static void verifyAttestationClosure(
            JsonNode attestation, JsonNode aggregate) {
        if (!attestation.path("requestId").asText().equals(
                aggregate.path("requestId").asText())
                || !attestation.path("compiledPlanFingerprint")
                .asText().equals(
                        aggregate.path("compiledPlanRef")
                                .path("fingerprint").asText())
                || !attestation.path("resultFingerprint")
                .asText().equals(
                        aggregate.path("resultFingerprint").asText())
                || !attestation.path("runId").asText().equals(
                        expectedRunId(aggregate))) {
            fail("SCENARIO_EVIDENCE_ATTESTATION_IDENTITY_INVALID");
        }
        Instant completed = instant(
                aggregate.path("completedAt"),
                "SCENARIO_RESULT_TIME_INVALID");
        Instant signedAt = instant(
                attestation.path("signedAt"),
                "SCENARIO_EVIDENCE_SIGNING_TIME_INVALID");
        if (signedAt.isBefore(completed)) {
            fail("SCENARIO_EVIDENCE_SIGNING_TIME_INVALID");
        }
    }

    private static void verifyFingerprint(
            JsonNode value,
            String field,
            int maximumBytes,
            String reason) {
        ObjectNode material = (ObjectNode) value.deepCopy();
        String expected = material.path(field).asText();
        material.put(field, "");
        String actual = EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes);
        if (!actual.equals(expected)) {
            fail(reason);
        }
    }

    private static ObjectNode bundleMaterial(JsonNode bundle) {
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        material.set("schemaVersion", bundle.path("schemaVersion"));
        material.set("payloadPolicy", bundle.path("payloadPolicy"));
        material.set("attestation", bundle.path("attestation"));
        material.set("result", bundle.path("result"));
        return material;
    }

    private static ObjectNode signatureMaterial(JsonNode attestation) {
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        material.put("domain", SIGNATURE_DOMAIN);
        material.set(
                "schemaVersion",
                attestation.path("schemaVersion"));
        material.set("runId", attestation.path("runId"));
        material.set("requestId", attestation.path("requestId"));
        material.set(
                "compiledPlanFingerprint",
                attestation.path("compiledPlanFingerprint"));
        material.set(
                "resultFingerprint",
                attestation.path("resultFingerprint"));
        material.set("signedAt", attestation.path("signedAt"));
        return material;
    }

    private static String expectedRunId(JsonNode aggregate) {
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        material.put("domain", RUN_ID_DOMAIN);
        material.set("scope", aggregate.path("scope"));
        material.set("requestId", aggregate.path("requestId"));
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_RUN_ID_MATERIAL_BYTES);
        return "scenario-"
                + fingerprint.substring("sha256:".length());
    }

    private static boolean orderedUnique(JsonNode values) {
        if (!values.isArray()) {
            return false;
        }
        String previous = null;
        Iterator<JsonNode> iterator = values.elements();
        while (iterator.hasNext()) {
            String current = iterator.next().asText();
            if (previous != null
                    && previous.compareTo(current) >= 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static String refCoordinate(JsonNode ref) {
        return ref.path("kind").asText()
                + ":" + ref.path("id").asText()
                + ":" + ref.path("revision").asLong()
                + ":" + ref.path("fingerprint").asText();
    }

    private static Instant instant(JsonNode value, String reason) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            fail(reason);
            return Instant.EPOCH;
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.runId,
                coordinates.requestId,
                coordinates.compiledPlanFingerprint,
                coordinates.resultFingerprint,
                coordinates.bundleFingerprint,
                coordinates.keyId);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record Coordinates(
            String runId,
            String requestId,
            String compiledPlanFingerprint,
            String resultFingerprint,
            String bundleFingerprint,
            String keyId) {
        private static Coordinates from(JsonNode bundle) {
            JsonNode safe = bundle == null
                    ? com.fasterxml.jackson.databind.node
                    .MissingNode.getInstance()
                    : bundle;
            JsonNode attestation = safe.path("attestation");
            return new Coordinates(
                    attestation.path("runId").asText(""),
                    attestation.path("requestId").asText(""),
                    attestation.path(
                            "compiledPlanFingerprint").asText(""),
                    attestation.path(
                            "resultFingerprint").asText(""),
                    safe.path("bundleFingerprint").asText(""),
                    attestation.path("keyId").asText(""));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
