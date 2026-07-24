package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Dependency-light offline verifier for signed terminal Scenario batch evidence.
 *
 * <p>The verifier applies the packaged strict Schema and independently re-derives the original
 * request fingerprint, immutable manifest fingerprint, full-scope batch and child run identities,
 * terminal job fingerprint, ordered item summary, index fingerprint, bundle fingerprint, key
 * policy, and domain-separated Ed25519 signature. Child aggregate payload-free evidence remains
 * separately addressable by each indexed run and bundle fingerprint.</p>
 */
public final class ScenarioRehearsalBatchEvidenceVerifier {
    /** Maximum canonical request bytes accepted by the producer protocol. */
    public static final int MAXIMUM_REQUEST_BYTES =
            2 * 1024 * 1024;
    /** Maximum canonical manifest bytes accepted by the producer protocol. */
    public static final int MAXIMUM_MANIFEST_BYTES =
            4 * 1024 * 1024;
    /** Maximum canonical job bytes accepted by the producer protocol. */
    public static final int MAXIMUM_JOB_BYTES =
            256 * 1024;
    /** Maximum canonical index bytes accepted by the producer protocol. */
    public static final int MAXIMUM_INDEX_BYTES =
            16 * 1024 * 1024;
    /** Maximum canonical bundle bytes accepted by the producer protocol. */
    public static final int MAXIMUM_BUNDLE_BYTES =
            18 * 1024 * 1024;
    private static final int MAXIMUM_SIGNATURE_MATERIAL_BYTES =
            8 * 1024;
    private static final int MAXIMUM_IDENTITY_MATERIAL_BYTES =
            16 * 1024;
    private static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_EVIDENCE_V1";
    private static final String BATCH_ID_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_ID_V1";
    private static final String RUN_ID_DOMAIN =
            "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1";

    /** Creates an offline verifier with the fixed Scenario batch v1 policy. */
    public ScenarioRehearsalBatchEvidenceVerifier() {
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
     * @param jobId canonical Scenario batch identity, or blank when unavailable
     * @param status terminal batch status, or blank when unavailable
     * @param requestFingerprint original batch request identity, or blank when unavailable
     * @param manifestFingerprint exact execution closure, or blank when unavailable
     * @param indexFingerprint complete ordered terminal index, or blank when unavailable
     * @param bundleFingerprint portable bundle identity, or blank when unavailable
     * @param keyId attestation key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String status,
            String requestFingerprint,
            String manifestFingerprint,
            String indexFingerprint,
            String bundleFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe verification output. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            jobId = normalized(jobId);
            status = normalized(status);
            requestFingerprint = normalized(requestFingerprint);
            manifestFingerprint = normalized(manifestFingerprint);
            indexFingerprint = normalized(indexFingerprint);
            bundleFingerprint = normalized(bundleFingerprint);
            keyId = normalized(keyId);
            if (outcome == null
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Scenario batch evidence verification result is invalid");
            }
        }

        /**
         * Reports whether every identity, content address, key policy, and signature was verified.
         *
         * @return true only for a fully verified bundle
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Independently verifies one decoded Scenario batch evidence bundle.
     *
     * @param bundle decoded v1 evidence bundle
     * @param key public key resolved from the attestation key id; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode bundle,
            EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(bundle);
        try {
            CapabilityMirrorSchemaValidator.require(
                    bundle,
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_BATCH_EVIDENCE_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_EVIDENCE_SCHEMA_INVALID",
                    coordinates);
        }

        JsonNode index = bundle.path("index");
        JsonNode attestation = bundle.path("attestation");
        try {
            verifyIndex(index);
            verifyAttestationClosure(attestation, index);
            if (!EvidenceVerificationSupport.sha256Bounded(
                    bundleMaterial(bundle),
                    MAXIMUM_BUNDLE_BYTES).equals(
                    bundle.path("bundleFingerprint").asText())) {
                fail("SCENARIO_BATCH_EVIDENCE_BUNDLE_FINGERPRINT_INVALID");
            }
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_EVIDENCE_MATERIAL_INVALID",
                    coordinates);
        }

        if (key == null) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    "SCENARIO_BATCH_EVIDENCE_KEY_UNAVAILABLE",
                    coordinates);
        }
        if (!key.keyId().equals(attestation.path("keyId").asText())) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_EVIDENCE_KEY_ID_MISMATCH",
                    coordinates);
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                attestation.path("algorithm").asText())) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SCENARIO_BATCH_EVIDENCE_ALGORITHM_REJECTED",
                    coordinates);
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(
                    attestation.path("signedAt").asText());
        } catch (DateTimeParseException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_EVIDENCE_SIGNING_TIME_INVALID",
                    coordinates);
        }
        if (!key.verificationAllowed()
                || signedAt.isBefore(
                key.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))) {
            return result(
                    Outcome.POLICY_REJECTED,
                    "SCENARIO_BATCH_EVIDENCE_KEY_POLICY_REJECTED",
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
                        "SCENARIO_BATCH_EVIDENCE_SIGNATURE_INVALID",
                        coordinates);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates);
        } catch (RuntimeException | GeneralSecurityException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_EVIDENCE_SIGNATURE_MATERIAL_INVALID",
                    coordinates);
        }
    }

    private static void verifyIndex(JsonNode index) {
        JsonNode request = index.path("request");
        JsonNode manifest = index.path("manifest");
        JsonNode job = index.path("job");
        ArrayNode items = (ArrayNode) index.path("items");
        String requestFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        request, MAXIMUM_REQUEST_BYTES);
        if (!requestFingerprint.equals(
                job.path("requestFingerprint").asText())) {
            fail("SCENARIO_BATCH_REQUEST_FINGERPRINT_INVALID");
        }

        ObjectNode manifestMaterial =
                manifest.deepCopy();
        manifestMaterial.put("manifestFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                manifestMaterial,
                MAXIMUM_MANIFEST_BYTES).equals(
                manifest.path("manifestFingerprint").asText())) {
            fail("SCENARIO_BATCH_MANIFEST_FINGERPRINT_INVALID");
        }
        JsonNode scope = manifest.path("scope");
        String requestId = request.path("requestId").asText();
        String expectedBatchId =
                "scenario-batch-" + hashSuffix(
                        identityMaterial(
                                BATCH_ID_DOMAIN,
                                scope,
                                requestId));
        if (!expectedBatchId.equals(
                manifest.path("batchId").asText())
                || !expectedBatchId.equals(
                job.path("jobId").asText())
                || !requestId.equals(
                manifest.path("requestId").asText())
                || !requestId.equals(
                job.path("requestId").asText())
                || !scope.equals(job.path("scope"))
                || !manifest.path("manifestFingerprint").asText()
                .equals(job.path("manifestFingerprint").asText())) {
            fail("SCENARIO_BATCH_SOURCE_IDENTITY_INVALID");
        }

        ArrayNode requested = (ArrayNode) request.path("entries");
        ArrayNode planned = (ArrayNode) manifest.path("entries");
        if (requested.size() != planned.size()
                || planned.size() != items.size()) {
            fail("SCENARIO_BATCH_ITEM_COUNT_INVALID");
        }
        int totalCases = 0;
        int passed = 0;
        int failed = 0;
        int indeterminate = 0;
        int cancelled = 0;
        Instant jobCompleted = instant(
                job.path("completedAt"),
                "SCENARIO_BATCH_JOB_TIME_INVALID");
        for (int itemIndex = 0;
             itemIndex < items.size();
             itemIndex++) {
            JsonNode requestedEntry = requested.get(itemIndex);
            JsonNode plannedEntry = planned.get(itemIndex);
            JsonNode item = items.get(itemIndex);
            totalCases = Math.addExact(
                    totalCases,
                    plannedEntry.path("caseCount").asInt());
            String childRequest =
                    plannedEntry.path(
                            "aggregateRequestId").asText();
            String expectedRunId =
                    "scenario-" + hashSuffix(
                            identityMaterial(
                                    RUN_ID_DOMAIN,
                                    scope,
                                    childRequest));
            if (plannedEntry.path("entryIndex").asInt(-1)
                    != itemIndex
                    || item.path("itemIndex").asInt(-1)
                    != itemIndex
                    || !requestedEntry.path("entryId").asText()
                    .equals(
                            plannedEntry.path("entryId").asText())
                    || !requestedEntry.path("compiledPlanRef")
                    .equals(plannedEntry.path("compiledPlanRef"))
                    || !item.path("compiledPlanRef")
                    .equals(plannedEntry.path("compiledPlanRef"))
                    || !childRequest.equals(
                    item.path("childRequestId").asText())
                    || !expectedRunId.equals(
                    plannedEntry.path("aggregateRunId").asText())) {
                fail("SCENARIO_BATCH_ITEM_IDENTITY_INVALID");
            }
            String status = item.path("status").asText();
            switch (status) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "INDETERMINATE" -> indeterminate++;
                case "CANCELLED" -> cancelled++;
                default -> fail("SCENARIO_BATCH_ITEM_NOT_TERMINAL");
            }
            String runId = item.path("runId").asText();
            String evidence =
                    item.path(
                            "evidenceBundleFingerprint").asText();
            String workbook =
                    item.path(
                            "workbookSeedFingerprint").asText();
            boolean completeEvidence =
                    !runId.isBlank()
                            && fingerprint(evidence)
                            && fingerprint(workbook);
            boolean noEvidence =
                    runId.isBlank()
                            && evidence.isBlank()
                            && workbook.isBlank();
            if (!(completeEvidence || noEvidence)
                    || (completeEvidence
                            && !expectedRunId.equals(runId))
                    || ("PASSED".equals(status)
                            && !completeEvidence)) {
                fail("SCENARIO_BATCH_ITEM_EVIDENCE_INVALID");
            }
            Instant completed = instant(
                    item.path("completedAt"),
                    "SCENARIO_BATCH_ITEM_TIME_INVALID");
            if (completed.isAfter(jobCompleted)) {
                fail("SCENARIO_BATCH_ITEM_TIME_INVALID");
            }
        }
        if (totalCases != manifest.path("totalCases").asInt()) {
            fail("SCENARIO_BATCH_TOTAL_CASES_INVALID");
        }
        JsonNode summary = job.path("summary");
        int completed = passed + failed
                + indeterminate + cancelled;
        if (summary.path("totalItems").asInt() != items.size()
                || summary.path("completedItems").asInt()
                != completed
                || summary.path("passedItems").asInt()
                != passed
                || summary.path("failedItems").asInt()
                != failed
                || summary.path("indeterminateItems").asInt()
                != indeterminate
                || summary.path("cancelledItems").asInt()
                != cancelled
                || completed != items.size()) {
            fail("SCENARIO_BATCH_SUMMARY_INVALID");
        }
        boolean allPassed = passed == items.size();
        if (allPassed
                != "SUCCEEDED".equals(
                job.path("status").asText())) {
            fail("SCENARIO_BATCH_STATUS_INVALID");
        }
        ObjectNode jobMaterial = job.deepCopy();
        jobMaterial.put("recordFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                jobMaterial, MAXIMUM_JOB_BYTES).equals(
                job.path("recordFingerprint").asText())) {
            fail("SCENARIO_BATCH_JOB_FINGERPRINT_INVALID");
        }
        ObjectNode indexMaterial = index.deepCopy();
        indexMaterial.put("indexFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                indexMaterial, MAXIMUM_INDEX_BYTES).equals(
                index.path("indexFingerprint").asText())) {
            fail("SCENARIO_BATCH_INDEX_FINGERPRINT_INVALID");
        }
    }

    private static void verifyAttestationClosure(
            JsonNode attestation,
            JsonNode index) {
        JsonNode job = index.path("job");
        JsonNode manifest = index.path("manifest");
        if (!attestation.path("jobId").asText()
                .equals(job.path("jobId").asText())
                || !attestation.path("requestFingerprint").asText()
                .equals(job.path("requestFingerprint").asText())
                || !attestation.path("manifestFingerprint").asText()
                .equals(
                        manifest.path(
                                "manifestFingerprint").asText())
                || !attestation.path("terminalJobFingerprint").asText()
                .equals(job.path("recordFingerprint").asText())
                || !attestation.path("indexFingerprint").asText()
                .equals(index.path("indexFingerprint").asText())) {
            fail("SCENARIO_BATCH_ATTESTATION_IDENTITY_INVALID");
        }
        Instant signedAt = instant(
                attestation.path("signedAt"),
                "SCENARIO_BATCH_ATTESTATION_TIME_INVALID");
        Instant completedAt = instant(
                job.path("completedAt"),
                "SCENARIO_BATCH_JOB_TIME_INVALID");
        if (signedAt.isBefore(completedAt)) {
            fail("SCENARIO_BATCH_ATTESTATION_TIME_INVALID");
        }
    }

    private static ObjectNode identityMaterial(
            String domain,
            JsonNode scope,
            String requestId) {
        ObjectNode value =
                com.fasterxml.jackson.databind.node.JsonNodeFactory
                        .instance.objectNode();
        value.put("domain", domain);
        value.set("scope", scope);
        value.put("requestId", requestId);
        return value;
    }

    private static String hashSuffix(JsonNode material) {
        return EvidenceVerificationSupport.sha256Bounded(
                        material,
                        MAXIMUM_IDENTITY_MATERIAL_BYTES)
                .substring("sha256:".length());
    }

    private static ObjectNode bundleMaterial(JsonNode bundle) {
        ObjectNode value =
                com.fasterxml.jackson.databind.node.JsonNodeFactory
                        .instance.objectNode();
        value.set("schemaVersion", bundle.path("schemaVersion"));
        value.set("payloadPolicy", bundle.path("payloadPolicy"));
        value.set("attestation", bundle.path("attestation"));
        value.set("index", bundle.path("index"));
        return value;
    }

    private static ObjectNode signatureMaterial(
            JsonNode attestation) {
        ObjectNode value =
                com.fasterxml.jackson.databind.node.JsonNodeFactory
                        .instance.objectNode();
        value.put("domain", SIGNATURE_DOMAIN);
        value.set(
                "schemaVersion",
                attestation.path("schemaVersion"));
        value.set("jobId", attestation.path("jobId"));
        value.set(
                "requestFingerprint",
                attestation.path("requestFingerprint"));
        value.set(
                "manifestFingerprint",
                attestation.path("manifestFingerprint"));
        value.set(
                "terminalJobFingerprint",
                attestation.path("terminalJobFingerprint"));
        value.set(
                "indexFingerprint",
                attestation.path("indexFingerprint"));
        value.set("signedAt", attestation.path("signedAt"));
        return value;
    }

    private static Instant instant(
            JsonNode value,
            String reasonCode) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException invalid) {
            fail(reasonCode);
            throw new IllegalStateException(
                    "unreachable", invalid);
        }
    }

    private static boolean fingerprint(String value) {
        return value != null
                && value.matches("sha256:[a-f0-9]{64}");
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.jobId,
                coordinates.status,
                coordinates.requestFingerprint,
                coordinates.manifestFingerprint,
                coordinates.indexFingerprint,
                coordinates.bundleFingerprint,
                coordinates.keyId);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private record Coordinates(
            String jobId,
            String status,
            String requestFingerprint,
            String manifestFingerprint,
            String indexFingerprint,
            String bundleFingerprint,
            String keyId) {
        private static Coordinates from(JsonNode bundle) {
            JsonNode safe = bundle == null
                    ? com.fasterxml.jackson.databind.node
                    .MissingNode.getInstance()
                    : bundle;
            JsonNode index = safe.path("index");
            JsonNode job = index.path("job");
            return new Coordinates(
                    job.path("jobId").asText(),
                    job.path("status").asText(),
                    job.path("requestFingerprint").asText(),
                    index.path("manifest")
                            .path("manifestFingerprint").asText(),
                    index.path("indexFingerprint").asText(),
                    safe.path("bundleFingerprint").asText(),
                    safe.path("attestation")
                            .path("keyId").asText());
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(reasonCode, null, false, false);
            this.reasonCode = reasonCode;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
