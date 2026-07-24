package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dependency-light offline verifier for a Scenario correctness-workbook source closure.
 *
 * <p>The verifier does not trust the producer's {@code gateReady} flag. It applies every packaged
 * strict Schema, independently verifies the aggregate and retention signatures, rechecks the
 * compiled-plan content address, binds every ordered case and assertion to its compiled and
 * executed source, re-derives blockers, and finally checks the workbook self-fingerprint. It does
 * not require Spring, Resource Gateway server classes, a database, or business payloads.</p>
 */
public final class ScenarioRehearsalWorkbookVerifier {
    /** Maximum canonical compiled-plan bytes admitted to reconstruction. */
    public static final int MAXIMUM_PLAN_BYTES = 8 * 1024 * 1024;
    /** Maximum canonical workbook bytes admitted to reconstruction. */
    public static final int MAXIMUM_WORKBOOK_BYTES = 8 * 1024 * 1024;

    /** Creates an offline verifier with fixed Scenario v1 semantics. */
    public ScenarioRehearsalWorkbookVerifier() {
    }

    /** Bounded verification outcome suitable for CI and governance ingestion. */
    public enum Outcome {
        /** Every Schema, source closure, fingerprint, key policy, and signature passed. */
        VERIFIED,
        /** At least one structure, identity, derived decision, fingerprint, or signature failed. */
        INVALID,
        /** One of the exact verification keys was not supplied. */
        KEY_UNAVAILABLE,
        /** At least one supplied key violates the fixed algorithm or lifecycle policy. */
        POLICY_REJECTED
    }

    /**
     * Payload-free workbook verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param runId Scenario aggregate identity, or blank when unavailable
     * @param seedFingerprint workbook identity, or blank when unavailable
     * @param gateReady independently reconstructed release-readiness claim
     * @param blockers independently reconstructed sorted blockers
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String runId,
            String seedFingerprint,
            boolean gateReady,
            List<String> blockers
    ) {
        /** Validates one bounded, log-safe verification result. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            runId = normalized(runId);
            seedFingerprint = normalized(seedFingerprint);
            blockers = blockers == null
                    ? List.of() : List.copyOf(blockers);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || blockers.size() > 16) {
                throw new IllegalArgumentException(
                        "Scenario workbook verification result is invalid");
            }
        }

        /**
         * Reports whether the entire workbook source closure passed.
         *
         * @return true only for a fully verified closure
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies and reconstructs one Scenario correctness-workbook source closure.
     *
     * @param workbook decoded v1 workbook seed
     * @param compiledPlan decoded v1 compiled rehearsal plan
     * @param evidenceBundle decoded v1 signed aggregate evidence
     * @param evidenceKey exact aggregate-evidence public key; may be {@code null}
     * @param retentionKey exact retention-event public key; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode workbook,
            JsonNode compiledPlan,
            JsonNode evidenceBundle,
            EvidenceVerificationKey evidenceKey,
            EvidenceVerificationKey retentionKey) {
        String runId = text(workbook, "runId");
        String seedFingerprint =
                text(workbook, "seedFingerprint");
        try {
            requireSchemas(
                    workbook, compiledPlan, evidenceBundle);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_SCHEMA_INVALID",
                    runId, seedFingerprint, false, List.of());
        }

        ScenarioRehearsalEvidenceVerifier.VerificationResult
                evidenceVerification =
                new ScenarioRehearsalEvidenceVerifier().verify(
                        evidenceBundle, evidenceKey);
        if (!evidenceVerification.verified()) {
            return result(
                    map(evidenceVerification.outcome()),
                    "SCENARIO_WORKBOOK_EVIDENCE_"
                            + evidenceVerification.reasonCode(),
                    runId, seedFingerprint, false, List.of());
        }
        JsonNode retentionEvent =
                workbook.path("retentionProof");
        ObjectNode retentionState =
                retentionState(retentionEvent);
        ScenarioRehearsalRetentionVerifier.VerificationResult
                retentionVerification =
                new ScenarioRehearsalRetentionVerifier().verify(
                        retentionState, retentionKey);
        if (!retentionVerification.verified()
                || retentionVerification.verifiedDeletionProof()) {
            return result(
                    map(retentionVerification.outcome()),
                    "SCENARIO_WORKBOOK_RETENTION_"
                            + retentionVerification.reasonCode(),
                    runId, seedFingerprint, false, List.of());
        }

        try {
            verifyPlanFingerprint(compiledPlan);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_PLAN_INVALID",
                    runId, seedFingerprint, false, List.of());
        }
        try {
            verifyIdentityClosure(
                    workbook, compiledPlan,
                    evidenceBundle, retentionEvent);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_IDENTITY_INVALID",
                    runId, seedFingerprint, false, List.of());
        }
        List<String> blockers;
        try {
            blockers = deriveAndVerifyCases(
                    workbook,
                    compiledPlan,
                    evidenceBundle.path("result"));
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_CASE_CLOSURE_INVALID",
                    runId, seedFingerprint, false, List.of());
        }
        boolean gateReady = blockers.isEmpty();
        if (workbook.path("gateReady").asBoolean()
                != gateReady
                || !arrayText(
                workbook.path("blockers"))
                .equals(blockers)) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_GATE_DECISION_INVALID",
                    runId, seedFingerprint, false, List.of());
        }
        try {
            verifyWorkbookFingerprint(workbook);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_WORKBOOK_FINGERPRINT_INVALID",
                    runId, seedFingerprint, false, List.of());
        }
        return result(
                Outcome.VERIFIED,
                "VERIFIED",
                runId,
                seedFingerprint,
                gateReady,
                blockers);
    }

    private static void requireSchemas(
            JsonNode workbook,
            JsonNode compiledPlan,
            JsonNode evidenceBundle) {
        CapabilityMirrorSchemaValidator.require(
                workbook,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_WORKBOOK_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                compiledPlan,
                CapabilityMirrorProtocol
                        .COMPILED_SCENARIO_REHEARSAL_PLAN_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_PLAN_SCHEMA_INVALID");
        CapabilityMirrorSchemaValidator.require(
                evidenceBundle,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_EVIDENCE_BUNDLE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_EVIDENCE_SCHEMA_INVALID");
    }

    private static void verifyPlanFingerprint(
            JsonNode plan) {
        ObjectNode material =
                ((ObjectNode) plan).deepCopy();
        material.put("fingerprint", "");
        String actual =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_PLAN_BYTES);
        if (!actual.equals(
                plan.path("fingerprint").asText())) {
            throw new IllegalArgumentException(
                    "compiled plan fingerprint mismatch");
        }
    }

    private static void verifyWorkbookFingerprint(
            JsonNode workbook) {
        ObjectNode material =
                ((ObjectNode) workbook).deepCopy();
        material.put("seedFingerprint", "");
        String actual =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_WORKBOOK_BYTES);
        if (!actual.equals(
                workbook.path("seedFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "workbook fingerprint mismatch");
        }
    }

    private static void verifyIdentityClosure(
            JsonNode workbook,
            JsonNode plan,
            JsonNode bundle,
            JsonNode retention) {
        JsonNode aggregate = bundle.path("result");
        JsonNode attestation = bundle.path("attestation");
        ObjectNode planRef = JsonNodeFactory.instance.objectNode();
        planRef.put("kind", "COMPILED_REHEARSAL_PLAN");
        planRef.put("id", plan.path("planId").asText());
        planRef.put(
                "revision", plan.path("revision").asLong());
        planRef.put(
                "fingerprint",
                plan.path("fingerprint").asText());
        if (!workbook.path("scope").equals(plan.path("scope"))
                || !workbook.path("scope").equals(
                aggregate.path("scope"))
                || !workbook.path("runId").equals(
                attestation.path("runId"))
                || !workbook.path("requestId").equals(
                aggregate.path("requestId"))
                || !sameRef(
                workbook.path("scenarioPackRef"),
                plan.path("scenarioPackRef"))
                || !sameRef(
                workbook.path("compiledPlanRef"), planRef)
                || !sameRef(
                aggregate.path("compiledPlanRef"), planRef)
                || !sameRef(
                workbook.path("targetCapabilityRef"),
                plan.path("targetCapabilityRef"))
                || !sameRef(
                workbook.path("targetCapabilityRef"),
                aggregate.path("targetCapabilityRef"))
                || !workbook.path("evidenceBundleFingerprint")
                .equals(bundle.path("bundleFingerprint"))
                || !workbook.path("resultFingerprint").equals(
                aggregate.path("resultFingerprint"))
                || !workbook.path("evidenceKeyId").equals(
                attestation.path("keyId"))
                || !retention.path("scope").equals(
                workbook.path("scope"))
                || !retention.path("runId").equals(
                workbook.path("runId"))
                || !retention.path("requestId").equals(
                workbook.path("requestId"))
                || !retention.path("evidenceBundleFingerprint")
                .equals(
                        workbook.path(
                                "evidenceBundleFingerprint"))
                || retention.path("revision").asLong() != 1
                || !"RETENTION_REGISTERED".equals(
                retention.path("type").asText())
                || !workbook.path("outcome").equals(
                aggregate.path("outcome"))
                || !workbook.path("summary").equals(
                aggregate.path("summary"))) {
            throw new IllegalArgumentException(
                    "workbook source identity closure is invalid");
        }
    }

    private static List<String> deriveAndVerifyCases(
            JsonNode workbook,
            JsonNode plan,
            JsonNode aggregate) {
        ArrayNode planCases =
                (ArrayNode) plan.path("cases");
        ArrayNode aggregateCases =
                (ArrayNode) aggregate.path("caseResults");
        ArrayNode workbookCases =
                (ArrayNode) workbook.path("cases");
        if (planCases.size() != aggregateCases.size()
                || planCases.size() != workbookCases.size()) {
            throw new IllegalArgumentException(
                    "workbook case counts differ");
        }
        Set<String> planAssertions =
                refSet(plan.path("assertionRefs"));
        Set<String> caseAssertions = new HashSet<>();
        TreeSet<String> blockers = new TreeSet<>();
        String outcome =
                aggregate.path("outcome").asText();
        if ("FAIL".equals(outcome)) {
            blockers.add("REHEARSAL_FAILED");
        } else if ("INDETERMINATE".equals(outcome)) {
            blockers.add("REHEARSAL_INDETERMINATE");
        }
        for (int index = 0; index < planCases.size(); index++) {
            JsonNode binding = planCases.get(index);
            JsonNode executed = aggregateCases.get(index);
            JsonNode projected = workbookCases.get(index);
            verifyCase(
                    index, binding, executed,
                    projected, caseAssertions);
            if (projected.path("childRunId").asText()
                    .isBlank()) {
                blockers.add("CASE_EVIDENCE_MISSING");
            } else if (!"CERTIFIABLE".equals(
                    projected.path("evidenceClass")
                            .asText())) {
                blockers.add(
                        "CHILD_EVIDENCE_NOT_CERTIFIABLE");
            }
            for (JsonNode assertion :
                    projected.path("assertionResults")) {
                if (!"BLOCKER".equals(
                        assertion.path("severity").asText())) {
                    continue;
                }
                if ("FAIL".equals(
                        assertion.path("outcome").asText())) {
                    blockers.add("BLOCKER_ASSERTION_FAILED");
                } else if ("INDETERMINATE".equals(
                        assertion.path("outcome").asText())) {
                    blockers.add(
                            "BLOCKER_ASSERTION_INDETERMINATE");
                }
            }
        }
        if (!planAssertions.equals(caseAssertions)) {
            throw new IllegalArgumentException(
                    "workbook assertion closure differs");
        }
        return List.copyOf(blockers);
    }

    private static void verifyCase(
            int index,
            JsonNode binding,
            JsonNode executed,
            JsonNode projected,
            Set<String> assertionClosure) {
        for (String field : List.of(
                "scenarioCaseRef", "testSuiteRef",
                "mirrorPlanRef", "fixtureBundleRef")) {
            if (!sameRef(
                    binding.path(field),
                    executed.path(field))
                    || !sameRef(
                    binding.path(field),
                    projected.path(field))) {
                throw new IllegalArgumentException(
                        "workbook case binding differs");
            }
        }
        if (!binding.path("caseType").equals(
                executed.path("caseType"))
                || !binding.path("caseType").equals(
                projected.path("caseType"))
                || !binding.path("testCaseId").equals(
                executed.path("testCaseId"))
                || !binding.path("testCaseId").equals(
                projected.path("testCaseId"))
                || !sameOptionalRef(
                binding.path("sessionCheckpointRef"),
                executed.path("sessionCheckpointRef"))
                || !sameOptionalRef(
                binding.path("sessionCheckpointRef"),
                projected.path("sessionCheckpointRef"))) {
            throw new IllegalArgumentException(
                    "workbook case binding differs");
        }
        if (executed.path("caseIndex").asInt(-1) != index
                || projected.path("caseIndex")
                .asInt(-1) != index
                || !projected.path("childRunId").equals(
                executed.path("runId"))
                || !projected.path(
                "childEvidenceBundleFingerprint").equals(
                executed.path("evidenceBundleFingerprint"))
                || !projected.path("outcome").equals(
                executed.path("outcome"))
                || !projected.path("diagnosticCode").equals(
                executed.path("diagnosticCode"))
                || !projected.path("assertionResults").equals(
                executed.path("assertionResults"))) {
            throw new IllegalArgumentException(
                    "workbook executed case differs");
        }
        String expectedStatus =
                executed.path("evidenceStatus").isNull()
                        ? ""
                        : executed.path("evidenceStatus")
                        .asText();
        String expectedClass =
                executed.path("evidenceClass").isNull()
                        ? ""
                        : executed.path("evidenceClass")
                        .asText();
        if (!expectedStatus.equals(
                projected.path("evidenceStatus").asText())
                || !expectedClass.equals(
                projected.path("evidenceClass").asText())
                || !refList(binding.path("assertionRefs"))
                .equals(refList(
                        executed.path("assertionResults"),
                        "assertionRef"))) {
            throw new IllegalArgumentException(
                    "workbook case evidence differs");
        }
        assertionClosure.addAll(
                refList(
                        executed.path("assertionResults"),
                        "assertionRef"));
    }

    private static ObjectNode retentionState(
            JsonNode event) {
        ObjectNode state =
                JsonNodeFactory.instance.objectNode();
        state.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_RETENTION_STATE_V1);
        state.set("scope", event.path("scope").deepCopy());
        state.set("runId", event.path("runId").deepCopy());
        state.set(
                "requestId",
                event.path("requestId").deepCopy());
        state.set(
                "evidenceBundleFingerprint",
                event.path("evidenceBundleFingerprint")
                        .deepCopy());
        state.put("status", "RETAINED");
        state.set(
                "revision",
                event.path("revision").deepCopy());
        state.set(
                "retainUntil",
                event.path("retainUntil").deepCopy());
        state.putArray("activeHoldIds");
        state.set(
                "updatedAt",
                event.path("occurredAt").deepCopy());
        state.set("latestEvent", event.deepCopy());
        return state;
    }

    private static Set<String> refSet(
            JsonNode refs) {
        return new HashSet<>(refList(refs));
    }

    private static List<String> refList(
            JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(refCoordinate(value));
        }
        return List.copyOf(result);
    }

    private static List<String> refList(
            JsonNode values, String field) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(
                    refCoordinate(value.path(field)));
        }
        return List.copyOf(result);
    }

    private static String refCoordinate(
            JsonNode ref) {
        return ref.path("kind").asText()
                + "|" + ref.path("id").asText()
                + "|" + ref.path("revision").asLong()
                + "|" + ref.path("fingerprint").asText();
    }

    private static boolean sameRef(
            JsonNode first, JsonNode second) {
        return refCoordinate(first).equals(
                refCoordinate(second));
    }

    private static boolean sameOptionalRef(
            JsonNode first, JsonNode second) {
        if (first.isNull() || first.isMissingNode()) {
            return second.isNull() || second.isMissingNode();
        }
        return !second.isNull()
                && !second.isMissingNode()
                && sameRef(first, second);
    }

    private static List<String> arrayText(
            JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static Outcome map(
            ScenarioRehearsalEvidenceVerifier.Outcome outcome) {
        return switch (outcome) {
            case VERIFIED -> Outcome.VERIFIED;
            case INVALID -> Outcome.INVALID;
            case KEY_UNAVAILABLE -> Outcome.KEY_UNAVAILABLE;
            case POLICY_REJECTED -> Outcome.POLICY_REJECTED;
        };
    }

    private static Outcome map(
            ScenarioRehearsalRetentionVerifier.Outcome outcome) {
        return switch (outcome) {
            case VERIFIED -> Outcome.VERIFIED;
            case INVALID -> Outcome.INVALID;
            case KEY_UNAVAILABLE -> Outcome.KEY_UNAVAILABLE;
            case POLICY_REJECTED -> Outcome.POLICY_REJECTED;
        };
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            String runId,
            String seedFingerprint,
            boolean gateReady,
            List<String> blockers) {
        return new VerificationResult(
                outcome, reasonCode, runId,
                seedFingerprint, gateReady, blockers);
    }

    private static String text(
            JsonNode value, String field) {
        return value == null
                ? "" : value.path(field).asText("");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
