package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalBatchEvidenceVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant CREATED =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final Instant COMPLETED =
            CREATED.plusSeconds(5);
    private static final Instant SIGNED =
            COMPLETED.plusSeconds(1);
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ScenarioRehearsalBatchEvidenceVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "scenario-batch-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                SIGNED.minusSeconds(60),
                "ACTIVE",
                "test");
        verifier = new ScenarioRehearsalBatchEvidenceVerifier();
    }

    @Test
    void independentlyVerifiesAllNestedAddressesIdentitiesAndSignature()
            throws Exception {
        ObjectNode bundle = bundle(1, true);

        ScenarioRehearsalBatchEvidenceVerifier.VerificationResult
                result = verifier.verify(bundle, key);

        assertThat(result.verified()).isTrue();
        assertThat(result.jobId()).startsWith(
                "scenario-batch-");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.indexFingerprint())
                .isEqualTo(
                        bundle.path("index")
                                .path("indexFingerprint").asText());
    }

    @Test
    void rejectsAValidSignatureOverProducerChosenSummary()
            throws Exception {
        ObjectNode bundle = bundle(0, true);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("SCENARIO_BATCH_SUMMARY_INVALID");
    }

    @Test
    void rejectsAValidSignatureOverWrongDerivedChildRun()
            throws Exception {
        ObjectNode bundle = bundle(1, false);

        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo("SCENARIO_BATCH_ITEM_IDENTITY_INVALID");
    }

    @Test
    void distinguishesMissingKeyFromSignatureTampering()
            throws Exception {
        ObjectNode bundle = bundle(1, true);

        assertThat(verifier.verify(bundle, null).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceVerifier
                                .Outcome.KEY_UNAVAILABLE);
        ((ObjectNode) bundle.path("attestation"))
                .put(
                        "signature",
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        sealBundle(bundle);
        assertThat(verifier.verify(bundle, key).reasonCode())
                .isEqualTo(
                        "SCENARIO_BATCH_EVIDENCE_SIGNATURE_INVALID");
    }

    private ObjectNode bundle(
            int reportedPassed,
            boolean correctRunId) throws Exception {
        ObjectNode scope = scope();
        String requestId = "refund-regression";
        ObjectNode request = request(requestId);
        String requestFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        request,
                        ScenarioRehearsalBatchEvidenceVerifier
                                .MAXIMUM_REQUEST_BYTES);
        String jobId = "scenario-batch-" + hashSuffix(
                identityMaterial(
                        "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_ID_V1",
                        scope,
                        requestId));
        String childRequest = requestId + ":plan:000";
        String childRun = "scenario-" + (
                correctRunId
                        ? hashSuffix(
                        identityMaterial(
                                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_RUN_ID_V1",
                                scope,
                                childRequest))
                        : "9".repeat(64));
        ObjectNode manifest = manifest(
                jobId,
                requestId,
                childRequest,
                childRun,
                scope);
        sealFingerprint(
                manifest,
                "manifestFingerprint",
                ScenarioRehearsalBatchEvidenceVerifier
                        .MAXIMUM_MANIFEST_BYTES);
        ObjectNode item = item(childRequest, childRun);
        ObjectNode job = job(
                jobId,
                requestId,
                requestFingerprint,
                manifest.path("manifestFingerprint").asText(),
                scope,
                reportedPassed);
        sealFingerprint(
                job,
                "recordFingerprint",
                ScenarioRehearsalBatchEvidenceVerifier
                        .MAXIMUM_JOB_BYTES);

        ObjectNode index = JSON.createObjectNode();
        index.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_EVIDENCE_INDEX_V1);
        index.put("indexFingerprint", "");
        index.set("request", request);
        index.set("manifest", manifest);
        index.set("job", job);
        index.putArray("items").add(item);
        sealFingerprint(
                index,
                "indexFingerprint",
                ScenarioRehearsalBatchEvidenceVerifier
                        .MAXIMUM_INDEX_BYTES);

        ObjectNode attestation = JSON.createObjectNode();
        attestation.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_EVIDENCE_ATTESTATION_V1);
        attestation.put("signatureStatus", "VERIFIED");
        attestation.put("jobId", jobId);
        attestation.put(
                "requestFingerprint", requestFingerprint);
        attestation.put(
                "manifestFingerprint",
                manifest.path("manifestFingerprint").asText());
        attestation.put(
                "terminalJobFingerprint",
                job.path("recordFingerprint").asText());
        attestation.put(
                "indexFingerprint",
                index.path("indexFingerprint").asText());
        attestation.put("signedAt", SIGNED.toString());
        attestation.put("keyId", key.keyId());
        attestation.put("algorithm", "Ed25519");
        attestation.put(
                "signature",
                sign(signatureMaterial(attestation)));
        attestation.put("independentlyVerifiable", true);

        ObjectNode bundle = JSON.createObjectNode();
        bundle.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_EVIDENCE_BUNDLE_V1);
        bundle.put("bundleFingerprint", fingerprint('0'));
        bundle.put("payloadPolicy", "HASH_ONLY");
        bundle.set("attestation", attestation);
        bundle.set("index", index);
        sealBundle(bundle);
        return bundle;
    }

    private static ObjectNode request(String requestId) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_REQUEST_V1);
        value.put("requestId", requestId);
        ObjectNode entry = value.putArray("entries").addObject();
        entry.put("entryId", "refund-happy-path");
        entry.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        return value;
    }

    private static ObjectNode manifest(
            String jobId,
            String requestId,
            String childRequest,
            String childRun,
            ObjectNode scope) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_MANIFEST_V1);
        value.put("batchId", jobId);
        value.put("manifestFingerprint", "");
        value.set("scope", scope);
        value.put("requestId", requestId);
        ObjectNode entry = value.putArray("entries").addObject();
        entry.put("entryIndex", 0);
        entry.put("entryId", "refund-happy-path");
        entry.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        entry.put("aggregateRequestId", childRequest);
        entry.put("aggregateRunId", childRun);
        entry.put("caseCount", 2);
        entry.put("executionTimeout", "PT20S");
        value.put("totalCases", 2);
        return value;
    }

    private static ObjectNode item(
            String childRequest,
            String childRun) {
        ObjectNode value = JSON.createObjectNode();
        value.put("itemIndex", 0);
        value.set(
                "compiledPlanRef",
                ref("COMPILED_REHEARSAL_PLAN", "refund-plan", 'a'));
        value.put("childRequestId", childRequest);
        value.put("status", "PASSED");
        value.put("attemptCount", 1);
        value.put("runId", childRun);
        value.put(
                "evidenceBundleFingerprint",
                fingerprint('e'));
        value.put(
                "workbookSeedFingerprint",
                fingerprint('d'));
        value.put("failureCode", "");
        value.put(
                "startedAt",
                CREATED.plusSeconds(1).toString());
        value.put("completedAt", COMPLETED.toString());
        return value;
    }

    private static ObjectNode job(
            String jobId,
            String requestId,
            String requestFingerprint,
            String manifestFingerprint,
            ObjectNode scope,
            int reportedPassed) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .SCENARIO_REHEARSAL_BATCH_JOB_V1);
        value.put("jobId", jobId);
        value.put("requestId", requestId);
        value.put(
                "requestFingerprint", requestFingerprint);
        value.put(
                "manifestFingerprint", manifestFingerprint);
        value.set("scope", scope);
        value.put("status", "SUCCEEDED");
        value.put("failureMode", "COLLECT_ALL");
        value.put("priority", "NORMAL");
        value.put("maximumItemAttempts", 3);
        ObjectNode summary = value.putObject("summary");
        summary.put("totalItems", 1);
        summary.put("completedItems", 1);
        summary.put("passedItems", reportedPassed);
        summary.put("failedItems", 1 - reportedPassed);
        summary.put("indeterminateItems", 0);
        summary.put("cancelledItems", 0);
        value.put(
                "deadlineAt",
                CREATED.plusSeconds(30).toString());
        value.put("failureCode", "");
        value.put("cancellationRequestId", "");
        value.put("cancellationReasonCode", "");
        value.put("createdAt", CREATED.toString());
        value.put("updatedAt", COMPLETED.toString());
        value.put("completedAt", COMPLETED.toString());
        value.put("recordFingerprint", "");
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "support");
        value.put("environmentId", "test");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 3);
        value.put("fingerprint", fingerprint(fingerprint));
        return value;
    }

    private static ObjectNode identityMaterial(
            String domain,
            ObjectNode scope,
            String requestId) {
        ObjectNode value = JSON.createObjectNode();
        value.put("domain", domain);
        value.set("scope", scope);
        value.put("requestId", requestId);
        return value;
    }

    private static String hashSuffix(ObjectNode value) {
        return EvidenceVerificationSupport.sha256Bounded(
                        value, 16 * 1024)
                .substring("sha256:".length());
    }

    private static void sealFingerprint(
            ObjectNode value,
            String field,
            int maximumBytes) {
        value.put(field, "");
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value, maximumBytes));
    }

    private static ObjectNode signatureMaterial(
            ObjectNode attestation) {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_EVIDENCE_V1");
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

    private String sign(ObjectNode material) throws Exception {
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        material, 8 * 1024);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(
                fingerprint.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                signature.sign());
    }

    private static void sealBundle(ObjectNode bundle) {
        ObjectNode material = JSON.createObjectNode();
        material.set(
                "schemaVersion",
                bundle.path("schemaVersion"));
        material.set(
                "payloadPolicy",
                bundle.path("payloadPolicy"));
        material.set(
                "attestation",
                bundle.path("attestation"));
        material.set("index", bundle.path("index"));
        bundle.put(
                "bundleFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        material,
                        ScenarioRehearsalBatchEvidenceVerifier
                                .MAXIMUM_BUNDLE_BYTES));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
