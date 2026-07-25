package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dependency-light offline verifier for one Scenario batch correctness-workbook source closure.
 *
 * <p>The verifier does not trust the producer's batch gate decision. It applies the packaged
 * strict schemas, verifies the signed batch evidence and signed retention registration, checks
 * every ordered entry against the signed request, manifest, and terminal item, verifies the
 * root-authenticated child commitments and bounded projections, derives the batch blockers, and
 * finally checks the root self-fingerprint. {@code verifyWithChildren(...)} can open the full child
 * content addresses, while case-level evidence can be verified separately with
 * {@link ScenarioRehearsalWorkbookVerifier}; the normal gate path remains bounded and
 * payload-free.</p>
 */
public final class ScenarioRehearsalBatchWorkbookVerifier {
    /** Maximum canonical root or child workbook bytes admitted to reconstruction. */
    public static final int MAXIMUM_WORKBOOK_BYTES = 8 * 1024 * 1024;

    /** Creates an offline verifier with fixed Scenario batch workbook v1 semantics. */
    public ScenarioRehearsalBatchWorkbookVerifier() {
    }

    /** Bounded verification outcome suitable for CI and governance ingestion. */
    public enum Outcome {
        /** Every schema, signed source, identity, decision, and content address passed. */
        VERIFIED,
        /** At least one structure, closure, decision, fingerprint, or signature failed. */
        INVALID,
        /** One of the exact verification keys was not supplied. */
        KEY_UNAVAILABLE,
        /** At least one supplied key violates the fixed algorithm or lifecycle policy. */
        POLICY_REJECTED
    }

    /**
     * Payload-free batch-workbook verification result.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param jobId Scenario batch identity, or blank when unavailable
     * @param seedFingerprint batch workbook identity, or blank when unavailable
     * @param gateReady independently reconstructed batch publication decision
     * @param blockers independently reconstructed sorted batch blockers
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String seedFingerprint,
            boolean gateReady,
            List<String> blockers
    ) {
        /** Validates one bounded, log-safe verification result. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            jobId = normalized(jobId);
            seedFingerprint = normalized(seedFingerprint);
            blockers = blockers == null
                    ? List.of() : List.copyOf(blockers);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || blockers.size() > 16) {
                throw new IllegalArgumentException(
                        "Scenario batch workbook verification result is invalid");
            }
        }

        /**
         * Reports whether the complete bounded source closure passed verification.
         *
         * @return true only for a fully verified batch workbook
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies and reconstructs one Scenario batch correctness-workbook source closure.
     *
     * @param workbook decoded v1 batch workbook seed
     * @param evidenceBundle decoded signed terminal batch evidence
     * @param evidenceKey exact batch-evidence public key; may be {@code null}
     * @param retentionKey exact batch-retention public key; may be {@code null}
     * @param workbookKey exact batch-workbook seal public key; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode workbook,
            JsonNode evidenceBundle,
            EvidenceVerificationKey evidenceKey,
            EvidenceVerificationKey retentionKey,
            EvidenceVerificationKey workbookKey) {
        String jobId = text(workbook, "jobId");
        String seedFingerprint =
                text(workbook, "seedFingerprint");
        try {
            requireSchemas(workbook, evidenceBundle);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_SCHEMA_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }

        ScenarioRehearsalBatchEvidenceVerifier.VerificationResult
                evidenceVerification =
                new ScenarioRehearsalBatchEvidenceVerifier()
                        .verify(evidenceBundle, evidenceKey);
        if (!evidenceVerification.verified()) {
            return result(
                    map(evidenceVerification.outcome()),
                    "SCENARIO_BATCH_WORKBOOK_EVIDENCE_"
                            + evidenceVerification.reasonCode(),
                    jobId, seedFingerprint, false, List.of());
        }
        JsonNode retentionEvent =
                workbook.path("retentionProof");
        ScenarioRehearsalBatchRetentionVerifier.VerificationResult
                retentionVerification =
                new ScenarioRehearsalBatchRetentionVerifier()
                        .verify(
                                retentionState(retentionEvent),
                                retentionKey);
        if (!retentionVerification.verified()
                || retentionVerification
                .verifiedDeletionProof()) {
            return result(
                    map(retentionVerification.outcome()),
                    "SCENARIO_BATCH_WORKBOOK_RETENTION_"
                            + retentionVerification.reasonCode(),
                    jobId, seedFingerprint, false, List.of());
        }

        try {
            verifyWorkbookSeal(workbook, workbookKey);
        } catch (KeyUnavailable unavailable) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SCENARIO_BATCH_WORKBOOK_ATTESTATION_KEY_UNAVAILABLE",
                    jobId, seedFingerprint, false, List.of());
        } catch (PolicyRejected rejected) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SCENARIO_BATCH_WORKBOOK_ATTESTATION_POLICY_REJECTED",
                    jobId, seedFingerprint, false, List.of());
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_ATTESTATION_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }
        try {
            verifyIdentityClosure(
                    workbook, evidenceBundle, retentionEvent);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_IDENTITY_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }

        List<String> blockers;
        try {
            blockers = deriveAndVerifyEntries(
                    workbook,
                    evidenceBundle.path("index"));
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_ENTRY_CLOSURE_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }
        boolean gateReady = blockers.isEmpty();
        if (workbook.path("gateReady").asBoolean()
                != gateReady
                || !arrayText(workbook.path("blockers"))
                .equals(blockers)) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_GATE_DECISION_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }
        try {
            verifyWorkbookFingerprint(workbook);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_FINGERPRINT_INVALID",
                    jobId, seedFingerprint, false, List.of());
        }
        return result(
                Outcome.VERIFIED,
                "VERIFIED",
                jobId,
                seedFingerprint,
                gateReady,
                blockers);
    }

    /**
     * Performs the bounded batch verification and additionally opens every child commitment.
     *
     * <p>Normal gate ingestion needs no child fan-out because the root seal authenticates the
     * complete bounded child projections. Audit workflows can use this method to prove that each
     * projection is also byte-for-byte derivable from its separately addressable full child seed.</p>
     *
     * @param workbook decoded v1 batch workbook seed
     * @param evidenceBundle decoded signed terminal batch evidence
     * @param evidenceKey exact batch-evidence public key
     * @param retentionKey exact batch-retention public key
     * @param workbookKey exact batch-workbook seal public key
     * @param childWorkbooks exact full child seeds keyed by run id
     * @return bounded verification result
     */
    public VerificationResult verifyWithChildren(
            JsonNode workbook,
            JsonNode evidenceBundle,
            EvidenceVerificationKey evidenceKey,
            EvidenceVerificationKey retentionKey,
            EvidenceVerificationKey workbookKey,
            Map<String, JsonNode> childWorkbooks) {
        VerificationResult bounded = verify(
                workbook, evidenceBundle,
                evidenceKey, retentionKey, workbookKey);
        if (!bounded.verified()) {
            return bounded;
        }
        try {
            verifyChildClosure(
                    workbook,
                    verifiedChildren(childWorkbooks));
            return bounded;
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_WORKBOOK_CHILD_CLOSURE_INVALID",
                    bounded.jobId(),
                    bounded.seedFingerprint(),
                    false,
                    List.of());
        }
    }

    private static void verifyWorkbookSeal(
            JsonNode workbook,
            EvidenceVerificationKey key) {
        if (key == null) {
            throw new KeyUnavailable();
        }
        JsonNode seal = workbook.path("workbookSeal");
        if (!key.keyId().equals(
                seal.path("keyId").asText())
                || !"Ed25519".equals(key.algorithm())
                || !"Ed25519".equals(
                seal.path("algorithm").asText())) {
            throw new PolicyRejected();
        }
        if (!key.verificationAllowed()) {
            throw new PolicyRejected();
        }
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1");
        for (String field : List.of(
                "schemaVersion", "jobId",
                "seedFingerprint",
                "evidenceBundleFingerprint",
                "evidenceIndexFingerprint")) {
            material.set(
                    field,
                    workbook.path(field).deepCopy());
        }
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, 16 * 1024);
        if (!fingerprint.equals(
                seal.path("materialFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "batch workbook attestation material differs");
        }
        try {
            if (!EvidenceVerificationSupport.verifyEd25519(
                    fingerprint,
                    seal.path("signature").asText(),
                    key.encodedPublicKey())) {
                throw new IllegalArgumentException(
                        "batch workbook signature is invalid");
            }
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException(
                    "batch workbook signature cannot be verified",
                    invalid);
        }
    }

    private static void requireSchemas(
            JsonNode workbook,
            JsonNode evidenceBundle) {
        CapabilityMirrorSchemaValidator.require(
                workbook,
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_WORKBOOK_SCHEMA_INVALID");
        String bundleVersion = text(
                evidenceBundle, "schemaVersion");
        String bundleSchema = CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V1
                .equals(bundleVersion)
                ? CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V1_SCHEMA_RESOURCE
                : CapabilityMirrorProtocol
                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_SCHEMA_RESOURCE;
        CapabilityMirrorSchemaValidator.require(
                evidenceBundle,
                bundleSchema,
                "RG.MIRROR.CLIENT.SCENARIO_BATCH_EVIDENCE_SCHEMA_INVALID");
    }

    private static Map<String, JsonNode> verifiedChildren(
            Map<String, JsonNode> values) {
        Map<String, JsonNode> result =
                new LinkedHashMap<>();
        if (values == null) {
            return Map.of();
        }
        for (Map.Entry<String, JsonNode> candidate :
                values.entrySet()) {
            String runId = normalized(candidate.getKey());
            JsonNode child = candidate.getValue();
            CapabilityMirrorSchemaValidator.require(
                    child,
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_WORKBOOK_SEED_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_WORKBOOK_SCHEMA_INVALID");
            if (!runId.equals(child.path("runId").asText())
                    || result.putIfAbsent(runId, child) != null) {
                throw new IllegalArgumentException(
                        "child workbook identity is invalid");
            }
            verifyWorkbookFingerprint(child);
        }
        return Map.copyOf(result);
    }

    private static void verifyChildClosure(
            JsonNode workbook,
            Map<String, JsonNode> children) {
        Set<String> used = new HashSet<>();
        for (JsonNode entry : workbook.path("entries")) {
            String runId = entry.path("runId").asText();
            if (runId.isBlank()) {
                continue;
            }
            JsonNode child = children.get(runId);
            if (child == null
                    || !used.add(runId)
                    || !entry.path("childWorkbook").equals(
                    childProjection(child))) {
                throw new IllegalArgumentException(
                        "child workbook projection differs");
            }
        }
        if (used.size() != children.size()) {
            throw new IllegalArgumentException(
                    "unreferenced child workbook supplied");
        }
    }

    private static void verifyIdentityClosure(
            JsonNode workbook,
            JsonNode bundle,
            JsonNode retention) {
        JsonNode index = bundle.path("index");
        JsonNode job = index.path("job");
        JsonNode manifest = index.path("manifest");
        JsonNode attestation = bundle.path("attestation");
        if (!workbook.path("scope").equals(
                job.path("scope"))
                || !workbook.path("jobId").equals(
                job.path("jobId"))
                || !workbook.path("requestId").equals(
                job.path("requestId"))
                || !workbook.path("requestFingerprint").equals(
                job.path("requestFingerprint"))
                || !workbook.path("manifestFingerprint").equals(
                manifest.path("manifestFingerprint"))
                || !workbook.path("terminalJobFingerprint").equals(
                job.path("recordFingerprint"))
                || !workbook.path("evidenceBundleFingerprint").equals(
                bundle.path("bundleFingerprint"))
                || !workbook.path("evidenceIndexFingerprint").equals(
                index.path("indexFingerprint"))
                || !workbook.path("evidenceKeyId").equals(
                attestation.path("keyId"))
                || !workbook.path("status").equals(
                job.path("status"))
                || !workbook.path("summary").equals(
                job.path("summary"))
                || !retention.path("scope").equals(
                workbook.path("scope"))
                || !retention.path("jobId").equals(
                workbook.path("jobId"))
                || !retention.path("requestId").equals(
                workbook.path("requestId"))
                || !retention.path("manifestFingerprint").equals(
                workbook.path("manifestFingerprint"))
                || !retention.path("evidenceBundleFingerprint").equals(
                workbook.path("evidenceBundleFingerprint"))
                || retention.path("revision").asLong() != 1
                || !"RETENTION_REGISTERED".equals(
                retention.path("type").asText())) {
            throw new IllegalArgumentException(
                    "batch workbook source identity closure is invalid");
        }
    }

    private static List<String> deriveAndVerifyEntries(
            JsonNode workbook,
            JsonNode index) {
        ArrayNode requested =
                (ArrayNode) index.path("request").path("entries");
        ArrayNode planned =
                (ArrayNode) index.path("manifest").path("entries");
        ArrayNode items =
                (ArrayNode) index.path("items");
        ArrayNode projected =
                (ArrayNode) workbook.path("entries");
        if (requested.size() != planned.size()
                || planned.size() != items.size()
                || items.size() != projected.size()) {
            throw new IllegalArgumentException(
                    "batch workbook entry counts differ");
        }
        TreeSet<String> blockers = new TreeSet<>();
        String status = workbook.path("status").asText();
        if (!"SUCCEEDED".equals(status)) {
            blockers.add("BATCH_STATUS_" + status);
        }
        for (int indexPosition = 0;
             indexPosition < items.size();
             indexPosition++) {
            JsonNode request = requested.get(indexPosition);
            JsonNode manifest = planned.get(indexPosition);
            JsonNode item = items.get(indexPosition);
            JsonNode entry = projected.get(indexPosition);
            verifyEntry(
                    indexPosition, request, manifest,
                    item, entry);
            switch (entry.path("status").asText()) {
                case "FAILED" ->
                        blockers.add("BATCH_ITEM_FAILED");
                case "INDETERMINATE" ->
                        blockers.add("BATCH_ITEM_INDETERMINATE");
                case "CANCELLED" ->
                        blockers.add("BATCH_ITEM_CANCELLED");
                case "PASSED" -> {
                }
                default -> throw new IllegalArgumentException(
                        "batch workbook item is not terminal");
            }
            JsonNode child = entry.path("childWorkbook");
            if (child.isNull()
                    && !"CANCELLED".equals(
                    entry.path("status").asText())) {
                blockers.add("CHILD_EVIDENCE_MISSING");
            } else if (!child.isNull()
                    && !child.path("gateReady").asBoolean()) {
                blockers.add("CHILD_WORKBOOK_BLOCKED");
            }
        }
        return List.copyOf(blockers);
    }

    private static void verifyEntry(
            int index,
            JsonNode request,
            JsonNode manifest,
            JsonNode item,
            JsonNode entry) {
        if (entry.path("entryIndex").asInt(-1) != index
                || !entry.path("entryId").equals(
                request.path("entryId"))
                || !entry.path("entryId").equals(
                manifest.path("entryId"))
                || !entry.path("compiledPlanRef").equals(
                request.path("compiledPlanRef"))
                || !entry.path("compiledPlanRef").equals(
                manifest.path("compiledPlanRef"))
                || !entry.path("compiledPlanRef").equals(
                item.path("compiledPlanRef"))
                || !entry.path("childRequestId").equals(
                manifest.path("aggregateRequestId"))
                || !entry.path("childRequestId").equals(
                item.path("childRequestId"))
                || !entry.path("expectedRunId").equals(
                manifest.path("aggregateRunId"))
                || !entry.path("status").equals(
                item.path("status"))
                || !entry.path("attemptCount").equals(
                item.path("attemptCount"))
                || !entry.path("runId").equals(
                item.path("runId"))
                || !entry.path(
                "childEvidenceBundleFingerprint").equals(
                item.path("evidenceBundleFingerprint"))
                || !entry.path(
                "childWorkbookSeedFingerprint").equals(
                item.path("workbookSeedFingerprint"))
                || !entry.path("failureCode").equals(
                item.path("failureCode"))) {
            throw new IllegalArgumentException(
                    "batch workbook entry differs from signed evidence");
        }
        String runId = item.path("runId").asText();
        if (runId.isBlank()) {
            if (!entry.path("childWorkbook").isNull()) {
                throw new IllegalArgumentException(
                        "child workbook unexpectedly present");
            }
            return;
        }
        JsonNode child =
                entry.path("childWorkbook");
        if (child.isNull()
                || !item.path("workbookSeedFingerprint").equals(
                child.path("seedFingerprint"))
                || !item.path("evidenceBundleFingerprint").equals(
                child.path("evidenceBundleFingerprint"))
                || !item.path("runId").equals(
                child.path("runId"))
                || !manifest.path("aggregateRequestId").equals(
                child.path("requestId"))
                || !manifest.path("compiledPlanRef").equals(
                child.path("compiledPlanRef"))) {
            throw new IllegalArgumentException(
                    "batch workbook child closure is invalid");
        }
        String expectedOutcome = switch (
                item.path("status").asText()) {
            case "PASSED" -> "PASS";
            case "FAILED" -> "FAIL";
            case "INDETERMINATE" -> "INDETERMINATE";
            default -> "";
        };
        if (!expectedOutcome.equals(
                child.path("outcome").asText())) {
            throw new IllegalArgumentException(
                    "batch item and child outcomes differ");
        }
    }

    private static ObjectNode childProjection(
            JsonNode child) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        for (String field : List.of(
                "schemaVersion", "seedFingerprint",
                "runId", "requestId", "compiledPlanRef",
                "scenarioPackRef", "targetCapabilityRef",
                "evidenceBundleFingerprint",
                "resultFingerprint", "evidenceKeyId")) {
            value.set(field, child.path(field).deepCopy());
        }
        value.put(
                "retentionProofFingerprint",
                ScenarioRehearsalRetentionVerifier
                        .eventFingerprint(
                                child.path("retentionProof")));
        for (String field : List.of(
                "outcome", "summary",
                "gateReady", "blockers")) {
            value.set(field, child.path(field).deepCopy());
        }
        return value;
    }

    private static ObjectNode retentionState(
            JsonNode event) {
        ObjectNode state =
                JsonNodeFactory.instance.objectNode();
        state.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_V1);
        for (String field : List.of(
                "scope", "requestId", "jobId",
                "manifestFingerprint",
                "evidenceBundleFingerprint",
                "revision", "retainUntil")) {
            state.set(field, event.path(field).deepCopy());
        }
        state.put("status", "RETAINED");
        state.putArray("activeHoldIds");
        state.set(
                "updatedAt",
                event.path("occurredAt").deepCopy());
        state.set("latestEvent", event.deepCopy());
        return state;
    }

    private static void verifyWorkbookFingerprint(
            JsonNode workbook) {
        ObjectNode material =
                ((ObjectNode) workbook).deepCopy();
        material.put("seedFingerprint", "");
        ObjectNode unsigned =
                JsonNodeFactory.instance.objectNode();
        unsigned.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        unsigned.put("materialFingerprint", "");
        unsigned.put("algorithm", "");
        unsigned.put("keyId", "");
        unsigned.put(
                "signedAt",
                "1970-01-01T00:00:00Z");
        unsigned.put("signature", "");
        material.set("workbookSeal", unsigned);
        String actual =
                EvidenceVerificationSupport.sha256Bounded(
                        material, MAXIMUM_WORKBOOK_BYTES);
        if (!actual.equals(
                workbook.path("seedFingerprint").asText())) {
            throw new IllegalArgumentException(
                    "workbook fingerprint mismatch");
        }
    }

    private static List<String> arrayText(
            JsonNode values) {
        List<String> result = new java.util.ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static Outcome map(
            ScenarioRehearsalBatchEvidenceVerifier.Outcome outcome) {
        return switch (outcome) {
            case VERIFIED -> Outcome.VERIFIED;
            case INVALID -> Outcome.INVALID;
            case KEY_UNAVAILABLE -> Outcome.KEY_UNAVAILABLE;
            case POLICY_REJECTED -> Outcome.POLICY_REJECTED;
        };
    }

    private static Outcome map(
            ScenarioRehearsalBatchRetentionVerifier.Outcome outcome) {
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
            String jobId,
            String seedFingerprint,
            boolean gateReady,
            List<String> blockers) {
        return new VerificationResult(
                outcome, reasonCode, jobId,
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

    private static final class KeyUnavailable
            extends RuntimeException {
        private KeyUnavailable() {
            super(null, null, false, false);
        }
    }

    private static final class PolicyRejected
            extends RuntimeException {
        private PolicyRejected() {
            super(null, null, false, false);
        }
    }
}
