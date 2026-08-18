package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Independent semantic verifier for Capability Studio Browser Anomaly Matrix Result v1.
 *
 * <p>Schema validation proves shape only. This verifier independently recomputes the 126
 * obligation identities, trigger semantics, the strict PASS contract, NOT_RUN purity, summary,
 * root state and evidence closure. It does not accept caller-provided authority facts beyond the
 * immutable candidate/base bindings in the wire document.</p>
 */
public final class CapabilityStudioBrowserAnomalyMatrixResultVerifier {
    /** Maximum raw or canonical result size accepted by this verifier. */
    public static final int MAXIMUM_RESULT_BYTES = 4 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String SCHEMA_VERSION =
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.SCHEMA_VERSION;
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(payload|secret|credential|password|token|request|response|body|header|fixture|mock|screenshot|html|trace).*");

    /** Stable classification of a verification result. */
    public enum FailureKind {
        /** No verification failure. */
        NONE,
        /** Wire document or schema failure. */
        SCHEMA,
        /** Schema-valid document violates protocol semantics. */
        SEMANTIC
    }

    /** Truthful root state of a schema-valid result. */
    public enum ArtifactStatus {
        /** Every obligation passed. */
        COMPLETE,
        /** At least one real obligation or binding failed. */
        FAILED,
        /** At least one obligation has not run and none has failed. */
        NOT_RUN
    }

    /**
     * Payload-free verifier result.
     *
     * @param failureKind schema or semantic failure kind
     * @param checks completed verification checks
     * @param errorCode stable protocol error code
     * @param artifactStatus truthful root artifact status
     */
    public record VerificationResult(
            FailureKind failureKind,
            Set<String> checks,
            String errorCode,
            ArtifactStatus artifactStatus) {
        /** Validates and freezes the result. */
        public VerificationResult {
            failureKind = java.util.Objects.requireNonNull(failureKind, "failureKind");
            checks = checks == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(checks));
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException("errorCode is not a protocol code");
            }
        }

        /**
         * Returns whether schema and semantic verification passed.
         *
         * @return true when no failure was found
         */
        public boolean verified() {
            return failureKind == FailureKind.NONE && errorCode == null;
        }
    }

    /** Creates a stateless verifier. */
    public CapabilityStudioBrowserAnomalyMatrixResultVerifier() {
    }

    /**
     * Verifies one decoded anomaly result and its internal closure.
     *
     * @param result decoded anomaly result
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode result) {
        VerificationResult schema = verifySchema(result);
        if (!schema.verified()) {
            return schema;
        }
        VerificationResult binding = verifyBindings(result);
        if (!binding.verified()) {
            return binding;
        }
        VerificationResult obligations = verifyObligations(result.path("obligations"));
        if (!obligations.verified()) {
            return obligations;
        }
        VerificationResult summary = verifySummary(result);
        if (!summary.verified()) {
            return summary;
        }
        VerificationResult closure = verifyClosure(result);
        if (!closure.verified()) {
            return closure;
        }
        VerificationResult status = verifyRootStatus(result);
        if (!status.verified()) {
            return status;
        }
        return valid(result,
                "SCHEMA", "PAYLOAD_FREE", "FIXED_OBLIGATION_SET", "OBLIGATION_ORDER",
                "TRIGGER_SEMANTICS", "PASS_CONTRACT", "NOT_RUN_PURITY", "SUMMARY",
                "EVIDENCE_CLOSURE", "ROOT_STATUS");
    }

    /**
     * Verifies one UTF-8 anomaly result.
     *
     * @param wireBytes UTF-8 anomaly result
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] wireBytes) {
        if (wireBytes == null || wireBytes.length > MAXIMUM_RESULT_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(wireBytes));
        } catch (IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_INVALID_JSON");
        }
    }

    /**
     * Verifies an anomaly result and independently verifies/binds its exact normal browser matrix.
     * The base artifact is never trusted merely because its reference was supplied by the caller.
     *
     * @param anomaly anomaly result
     * @param baseMatrixBytes exact normal browser matrix wire artifact
     * @return payload-free verification result
     */
    public VerificationResult verify(JsonNode anomaly, byte[] baseMatrixBytes) {
        VerificationResult local = verify(anomaly);
        if (!local.verified()) {
            return local;
        }
        CapabilityStudioBrowserMatrixResultVerifier baseVerifier =
                new CapabilityStudioBrowserMatrixResultVerifier();
        CapabilityStudioBrowserMatrixResultVerifier.VerificationResult base =
                baseVerifier.verify(baseMatrixBytes);
        if (!base.verified()
                || base.artifactStatus()
                != CapabilityStudioBrowserMatrixResultVerifier.ArtifactStatus.COMPLETE) {
            return semanticFailure("BASE_MATRIX_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_INVALID");
        }
        final JsonNode baseDocument;
        try {
            baseDocument = JSON.readTree(baseMatrixBytes);
        } catch (IOException | RuntimeException invalidJson) {
            return semanticFailure("BASE_MATRIX_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_INVALID");
        }
        if (!same(anomaly.path("candidate"), baseDocument.path("candidate"))
                || !same(anomaly.path("baselineRef"), baseDocument.path("baselineRef"))
                || !same(anomaly.path("environment"), baseDocument.path("environment"))
                || !same(anomaly.path("contractRevision"), baseDocument.path("contractRevision"))) {
            return semanticFailure("BASE_MATRIX_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_BINDING_MISMATCH");
        }
        String expectedExactRef = "results/browser-matrix/"
                + baseDocument.path("resultId").asText();
        if (!expectedExactRef.equals(anomaly.path("baseMatrixRef").path("exactRef").asText())) {
            return semanticFailure("BASE_MATRIX_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_REF_MISMATCH");
        }
        if (!baseDocument.path("evidenceClosureFingerprint").asText()
                .equals(anomaly.path("baseMatrixRef").path("fingerprint").asText())
                || !"COMPLETE".equals(anomaly.path("baseMatrixRef").path("resultStatus").asText())) {
            return semanticFailure("BASE_MATRIX_BINDING",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BASE_MATRIX_FINGERPRINT_MISMATCH");
        }
        return valid(anomaly,
                "SCHEMA", "PAYLOAD_FREE", "FIXED_OBLIGATION_SET", "OBLIGATION_ORDER",
                "TRIGGER_SEMANTICS", "PASS_CONTRACT", "NOT_RUN_PURITY", "SUMMARY",
                "EVIDENCE_CLOSURE", "ROOT_STATUS", "BASE_MATRIX_INDEPENDENT_VERIFICATION",
                "BASE_MATRIX_BINDING");
    }

    /**
     * Verifies anomaly and base artifacts from UTF-8 wire documents.
     *
     * @param anomalyBytes anomaly result bytes
     * @param baseMatrixBytes base matrix result bytes
     * @return payload-free verification result
     */
    public VerificationResult verify(byte[] anomalyBytes, byte[] baseMatrixBytes) {
        if (anomalyBytes == null || anomalyBytes.length > MAXIMUM_RESULT_BYTES) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SIZE_LIMIT");
        }
        try {
            return verify(JSON.readTree(anomalyBytes), baseMatrixBytes);
        } catch (IOException | RuntimeException invalidJson) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_INVALID_JSON");
        }
    }

    private static VerificationResult verifySchema(JsonNode result) {
        if (result == null || !result.isObject()
                || !SCHEMA_VERSION.equals(result.path("schemaVersion").asText())) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SCHEMA_INVALID");
        }
        if (containsSensitiveField(result)) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SENSITIVE_FIELD");
        }
        try {
            var schemaErrors = CapabilityStudioSchemaSupport.validate(
                    result, CapabilityStudioSchemaSupport.BROWSER_ANOMALY_MATRIX_RESULT_RESOURCE);
            if (!schemaErrors.isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SCHEMA_INVALID");
            }
        } catch (RuntimeException unavailable) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SCHEMA_UNAVAILABLE");
        }
        try {
            if (EvidenceVerificationSupport.sha256Bounded(result, MAXIMUM_RESULT_BYTES).isEmpty()) {
                return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SIZE_LIMIT");
            }
        } catch (IllegalArgumentException tooLarge) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SIZE_LIMIT");
        }
        return valid("SCHEMA", "PAYLOAD_FREE");
    }

    private static VerificationResult verifyBindings(JsonNode result) {
        try {
            OffsetDateTime started = OffsetDateTime.parse(
                    result.path("executionWindow").path("startedAt").asText());
            OffsetDateTime completed = OffsetDateTime.parse(
                    result.path("executionWindow").path("completedAt").asText());
            if (completed.isBefore(started)) {
                return semanticFailure("BINDINGS",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EXECUTION_WINDOW_INVALID");
            }
        } catch (DateTimeParseException invalid) {
            return semanticFailure("BINDINGS",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EXECUTION_WINDOW_INVALID");
        }
        if (!"S0-AC-01".equals(result.path("contractId").asText())
                || !"COMPLETE".equals(result.path("baseMatrixRef").path("resultStatus").asText())) {
            return semanticFailure("BINDINGS",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_BINDING_INVALID");
        }
        return valid("BINDINGS");
    }

    private static VerificationResult verifyObligations(JsonNode obligations) {
        List<CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey> expected =
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations();
        if (!obligations.isArray() || obligations.size() != expected.size()) {
            return semanticFailure("FIXED_OBLIGATION_SET",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_OBLIGATION_COUNT_INVALID");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < expected.size(); i++) {
            JsonNode obligation = obligations.get(i);
            var key = expected.get(i);
            if (!key.obligationId().equals(obligation.path("obligationId").asText())
                    || !key.profile().name().equals(obligation.path("stateProfile").asText())
                    || !key.goldenPathId().equals(obligation.path("goldenPathId").asText())
                    || !key.locale().equals(obligation.path("locale").asText())
                    || !sameViewport(key.viewport(), obligation.path("viewport"))) {
                return semanticFailure("OBLIGATION_ORDER",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_OBLIGATION_ORDER_INVALID");
            }
            if (!seen.add(obligation.path("obligationId").asText())) {
                return semanticFailure("FIXED_OBLIGATION_SET",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_OBLIGATION_DUPLICATE");
            }
            if (!expectedUiState(key).equals(obligation.path("expectedUiState").asText())
                    || !expectedRecovery(key).equals(obligation.path("expectedRecoveryAction").asText())) {
                return semanticFailure("OBLIGATION_ORDER",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EXPECTATION_INVALID");
            }
            VerificationResult cell = verifyObligation(key, obligation);
            if (!cell.verified()) {
                return cell;
            }
        }
        return valid("FIXED_OBLIGATION_SET", "OBLIGATION_ORDER", "TRIGGER_SEMANTICS",
                "PASS_CONTRACT", "NOT_RUN_PURITY");
    }

    private static VerificationResult verifyObligation(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key,
            JsonNode obligation) {
        String status = obligation.path("status").asText();
        if (!Set.of("PASS", "FAIL", "NOT_RUN").contains(status)) {
            return semanticFailure("OBLIGATION_ORDER",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_STATUS_INVALID");
        }
        VerificationResult trigger = verifyTrigger(key, status, obligation.path("trigger"));
        if (!trigger.verified()) {
            return trigger;
        }
        VerificationResult evidence = verifyEvidenceOrder(obligation.path("evidenceRefs"));
        if (!evidence.verified()) {
            return evidence;
        }
        JsonNode browser = obligation.path("browserObservations");
        if ("NOT_RUN".equals(status)) {
            if (!isPureNotRun(obligation.path("trigger"), browser, obligation.path("evidenceRefs"))) {
                return semanticFailure("NOT_RUN_PURITY",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_NOT_RUN_HAS_EVIDENCE");
            }
            return valid("TRIGGER_SEMANTICS", "NOT_RUN_PURITY");
        }
        if ("FAIL".equals(status)
                && (browser.path("actualViewport").isNull()
                || obligation.path("evidenceRefs").isEmpty())) {
            return semanticFailure("FAILURE_OBSERVATION",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FAILURE_WITHOUT_OBSERVATION");
        }
        if ("PASS".equals(status)) {
            if (obligation.path("evidenceRefs").size() < 1
                    || !isStrictPass(key, browser)) {
                return semanticFailure("PASS_CONTRACT",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_FALSE_PASS");
            }
        }
        return valid("TRIGGER_SEMANTICS", "PASS_CONTRACT");
    }

    private static VerificationResult verifyTrigger(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key,
            String status,
            JsonNode trigger) {
        String mechanism = trigger.path("mechanism").asText();
        String targetRoute = trigger.path("targetRoute").asText();
        String failureClass = trigger.path("observedFailureClass").asText();
        JsonNode httpStatus = trigger.path("observedHttpStatus");
        boolean triggered = trigger.path("triggered").asBoolean();
        if (!expectedTargetRoute(key).equals(targetRoute)) {
            return semanticFailure("TRIGGER_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TARGET_ROUTE_INVALID");
        }
        boolean valid;
        switch (key.profile()) {
            case ERROR -> valid = "CDP_FETCH_FULFILL".equals(mechanism)
                    && Set.of("HTTP_4XX", "HTTP_5XX").contains(failureClass)
                    && (("NOT_RUN".equals(status) && httpStatus.isNull())
                    || (!"NOT_RUN".equals(status) && httpStatus.isInt()
                    && httpStatus.asInt() >= 400 && httpStatus.asInt() <= 599
                    && (("HTTP_4XX".equals(failureClass) && httpStatus.asInt() < 500)
                    || ("HTTP_5XX".equals(failureClass) && httpStatus.asInt() >= 500))));
            case OFFLINE -> valid = "CDP_FETCH_FAIL".equals(mechanism)
                    && "TRANSPORT_FAILURE".equals(failureClass) && httpStatus.isNull();
            case CONFLICT -> valid = "REAL_HTTP_STALE_REVISION".equals(mechanism)
                    && "REVISION_CONFLICT".equals(failureClass)
                    && (("NOT_RUN".equals(status) && httpStatus.isNull())
                    || (!"NOT_RUN".equals(status) && httpStatus.isInt() && httpStatus.asInt() == 409));
            default -> valid = false;
        }
        if (!valid || (!"NOT_RUN".equals(status) && !triggered)
                || ("NOT_RUN".equals(status) && triggered)) {
            return semanticFailure("TRIGGER_SEMANTICS",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_TRIGGER_INVALID");
        }
        return valid("TRIGGER_SEMANTICS");
    }

    private static String expectedTargetRoute(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return switch (key.goldenPathId()) {
            case "GP-01", "GP-02", "GP-07" -> "/api/capability-studio/demo-pack";
            case "GP-03" -> "/api/capability-studio/scenario-dataset";
            case "GP-04" ->
                    "/api/capability-studio/tutorial-branch/behaviors/compensation-history";
            case "GP-05", "GP-06" -> "/api/capability-studio/feature-rehearsal";
            case "GP-08" -> "/api/capability-studio/governed-baseline";
            case "GP-09" -> "/api/capability-studio/scenario-dataset/quality-impact";
            case "GP-10" -> "/api/capability-studio/governed-runs/runId/evidence";
            default -> "";
        };
    }

    private static boolean isStrictPass(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key,
            JsonNode browser) {
        JsonNode actual = browser.path("actualViewport");
        JsonNode axe = browser.path("axe");
        JsonNode keyboard = browser.path("keyboardPath");
        boolean conflict = key.profile()
                == CapabilityStudioBrowserAnomalyMatrixResultBuilder.StateProfile.CONFLICT;
        return sameViewport(key.viewport(), actual)
                && !browser.path("pageHorizontalOverflow").asBoolean()
                && axe.path("serious").asInt(-1) == 0
                && axe.path("critical").asInt(-1) == 0
                && browser.path("technicalIdCount").asInt(-1) == 0
                && browser.path("rawJsonCount").asInt(-1) == 0
                && keyboard.path("completed").asBoolean()
                && keyboard.path("steps").asInt(0) >= 1
                && keyboard.path("focusLosses").asInt(-1) == 0
                && browser.path("errorVisible").asBoolean()
                && browser.path("businessSafeExplanation").asBoolean()
                && browser.path("recoveryActionVisible").asBoolean()
                && browser.path("recoveryAttempted").asBoolean()
                && browser.path("recoveredToReady").asBoolean()
                && (!conflict || (browser.path("localDraftRetained").asBoolean()
                        && browser.path("serverRevisionPreserved").asBoolean()))
                && browser.path("staleGreenPreflightAbsent").asBoolean()
                && browser.path("staleErrorAbsent").asBoolean()
                && browser.path("staleEvidenceAbsent").asBoolean()
                && browser.path("staleSuccessAbsent").asBoolean()
                && browser.path("p0Count").asInt(-1) == 0
                && browser.path("p1Count").asInt(-1) == 0;
    }

    private static boolean isPureNotRun(JsonNode trigger, JsonNode browser, JsonNode evidence) {
        JsonNode actual = browser.path("actualViewport");
        JsonNode axe = browser.path("axe");
        JsonNode keyboard = browser.path("keyboardPath");
        return !trigger.path("triggered").asBoolean()
                && trigger.path("observedHttpStatus").isNull()
                && actual.isNull()
                && !browser.path("pageHorizontalOverflow").asBoolean()
                && axe.path("serious").asInt(-1) == 0
                && axe.path("critical").asInt(-1) == 0
                && browser.path("technicalIdCount").asInt(-1) == 0
                && browser.path("rawJsonCount").asInt(-1) == 0
                && !keyboard.path("completed").asBoolean()
                && keyboard.path("steps").asInt(-1) == 0
                && keyboard.path("focusLosses").asInt(-1) == 0
                && !browser.path("errorVisible").asBoolean()
                && !browser.path("businessSafeExplanation").asBoolean()
                && !browser.path("recoveryActionVisible").asBoolean()
                && !browser.path("recoveryAttempted").asBoolean()
                && !browser.path("recoveredToReady").asBoolean()
                && !browser.path("localDraftRetained").asBoolean()
                && !browser.path("serverRevisionPreserved").asBoolean()
                && !browser.path("staleGreenPreflightAbsent").asBoolean()
                && !browser.path("staleErrorAbsent").asBoolean()
                && !browser.path("staleEvidenceAbsent").asBoolean()
                && !browser.path("staleSuccessAbsent").asBoolean()
                && browser.path("p0Count").asInt(-1) == 0
                && browser.path("p1Count").asInt(-1) == 0
                && evidence.isArray() && evidence.isEmpty();
    }

    private static VerificationResult verifyEvidenceOrder(JsonNode refs) {
        if (!refs.isArray()) {
            return semanticFailure("EVIDENCE", "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EVIDENCE_INVALID");
        }
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (JsonNode ref : refs) {
            String exactRef = ref.path("exactRef").asText();
            if (!seen.add(exactRef) || previous != null && previous.compareTo(exactRef) >= 0) {
                return semanticFailure("EVIDENCE",
                        "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EVIDENCE_ORDER_INVALID");
            }
            previous = exactRef;
        }
        return valid("EVIDENCE");
    }

    private static VerificationResult verifySummary(JsonNode result) {
        int passed = 0;
        int failed = 0;
        int notRun = 0;
        for (JsonNode obligation : result.path("obligations")) {
            switch (obligation.path("status").asText()) {
                case "PASS" -> passed++;
                case "FAIL" -> failed++;
                case "NOT_RUN" -> notRun++;
                default -> { }
            }
        }
        JsonNode summary = result.path("summary");
        if (summary.path("expected").asInt(-1) != 126
                || summary.path("actual").asInt(-1) != 126
                || summary.path("passed").asInt(-1) != passed
                || summary.path("failed").asInt(-1) != failed
                || summary.path("notRun").asInt(-1) != notRun
                || summary.path("errorExpected").asInt(-1) != 60
                || summary.path("offlineExpected").asInt(-1) != 60
                || summary.path("conflictExpected").asInt(-1) != 6) {
            return semanticFailure("SUMMARY",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SUMMARY_MISMATCH");
        }
        return valid("SUMMARY");
    }

    private static VerificationResult verifyClosure(JsonNode result) {
        String expected;
        try {
            ObjectNode material = (ObjectNode) result.deepCopy();
            material.remove("evidenceClosureFingerprint");
            expected = EvidenceVerificationSupport.sha256Bounded(material, MAXIMUM_RESULT_BYTES);
        } catch (IllegalArgumentException tooLarge) {
            return schemaFailure("RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_SIZE_LIMIT");
        }
        if (!expected.equals(result.path("evidenceClosureFingerprint").asText())) {
            return semanticFailure("EVIDENCE_CLOSURE",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_EVIDENCE_FINGERPRINT_MISMATCH");
        }
        return valid("EVIDENCE_CLOSURE");
    }

    private static VerificationResult verifyRootStatus(JsonNode result) {
        int failed = result.path("summary").path("failed").asInt(-1);
        int notRun = result.path("summary").path("notRun").asInt(-1);
        String expected = !"CLEAN".equals(result.path("candidate").path("sourceTreeStatus").asText())
                || failed > 0
                ? "FAILED"
                : notRun > 0 ? "NOT_RUN" : "COMPLETE";
        if (!expected.equals(result.path("resultStatus").asText())) {
            return semanticFailure("ROOT_STATUS",
                    "RG.CAPABILITY_STUDIO.BROWSER_ANOMALY_RESULT_STATUS_MISMATCH");
        }
        return valid("ROOT_STATUS");
    }

    private static boolean sameViewport(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.Viewport expected,
            JsonNode actual) {
        return actual != null && actual.isObject()
                && expected.width() == actual.path("width").asInt(-1)
                && expected.height() == actual.path("height").asInt(-1);
    }

    private static String expectedUiState(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return key.profile().name() + "_FEEDBACK";
    }

    private static String expectedRecovery(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return key.profile()
                == CapabilityStudioBrowserAnomalyMatrixResultBuilder.StateProfile.CONFLICT
                ? "RETRY_OR_MERGE" : "RETRY";
    }

    private static boolean same(JsonNode left, JsonNode right) {
        return left != null && right != null && left.equals(right);
    }

    private static boolean containsSensitiveField(JsonNode value) {
        if (value == null) {
            return false;
        }
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (SENSITIVE_FIELD.matcher(entry.getKey()).matches()
                        || containsSensitiveField(entry.getValue())) {
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

    private static VerificationResult valid(String... checks) {
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null, null);
    }

    private static VerificationResult valid(JsonNode result, String... checks) {
        ArtifactStatus status;
        try {
            status = ArtifactStatus.valueOf(result.path("resultStatus").asText());
        } catch (IllegalArgumentException invalidStatus) {
            status = null;
        }
        return new VerificationResult(FailureKind.NONE, Set.of(checks), null, status);
    }

    private static VerificationResult semanticFailure(String check, String errorCode) {
        return new VerificationResult(FailureKind.SEMANTIC, Set.of(check), errorCode, null);
    }

    private static VerificationResult schemaFailure(String errorCode) {
        return new VerificationResult(FailureKind.SCHEMA, Set.of("SCHEMA"), errorCode, null);
    }
}
