package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

class ReadOnlyShadowJobVerifierTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-26T00:00:00Z");
    private static final Instant DEADLINE_AT =
            Instant.parse("2026-07-26T00:30:00Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-26T00:00:06Z");
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-26T00:00:10Z");

    private ReadOnlyShadowJobVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode request;
    private ObjectNode comparison;
    private ObjectNode job;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new ReadOnlyShadowJobVerifier();
        keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol
                        .EVIDENCE_VERIFICATION_KEY_V1,
                "shadow-job-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                CREATED_AT.minusSeconds(60),
                "ACTIVE",
                "test");
        request = request();
        comparison = comparison();
        resignComparison(comparison);
        job = job("SUCCEEDED", comparison);
    }

    @Test
    void independentlyVerifiesSuccessfulJobAndComparisonClosure() {
        ReadOnlyShadowJobVerifier.VerificationResult result =
                verifier.verify(
                        job,
                        request,
                        comparison,
                        key);

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.comparisonFingerprint())
                .isEqualTo(
                        comparison.path(
                                "comparisonFingerprint")
                                .asText());
    }

    @Test
    void rejectsRequestAndMutableRecordFingerprintDrift() {
        request.withObject("/accessGrant")
                .put("sampleOrdinal", 2);
        assertThat(verifier.verify(
                job, request, comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_JOB_REQUEST_CLOSURE_INVALID");

        request = request();
        job.put("attemptCount", 2);
        assertThat(verifier.verify(
                job, request, comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_JOB_RECORD_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsResignedComparisonThatDriftsFromTheJobPolicy()
            throws Exception {
        comparison.withObject("/comparisonPolicyRef")
                .put("fingerprint", fingerprint('f'));
        resignComparison(comparison);
        job = job("SUCCEEDED", comparison);

        assertThat(verifier.verify(
                job, request, comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_JOB_COMPARISON_CLOSURE_INVALID");
    }

    @Test
    void rejectsMissingOrUnexpectedTerminalComparison() {
        assertThat(verifier.verify(
                job, request, null, key)
                .reasonCode()).isEqualTo(
                "SHADOW_JOB_COMPARISON_MISSING");

        ObjectNode queued = job("QUEUED", null);
        assertThat(verifier.verify(
                queued, request, comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_JOB_UNEXPECTED_COMPARISON");
    }

    @Test
    void propagatesComparisonKeyAvailabilityWithoutPayload() {
        ReadOnlyShadowJobVerifier.VerificationResult result =
                verifier.verify(
                        job,
                        request,
                        comparison,
                        null);

        assertThat(result.outcome()).isEqualTo(
                ReadOnlyShadowJobVerifier
                        .Outcome
                        .COMPARISON_KEY_UNAVAILABLE);
        assertThat(result.toString())
                .doesNotContain("payload")
                .doesNotContain("credential");
    }

    private ObjectNode request() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_REQUEST_V1);
        value.put("requestId", "shadow-refund-1");
        value.set("scope", scope());
        value.set(
                "inventoryRef",
                ref(
                        "DOMAIN_FIDELITY_INVENTORY",
                        "refund-fidelity",
                        '1'));
        value.put("unitId", "refund-golden");
        value.set(
                "scenarioCaseRef",
                ref(
                        "SCENARIO_CASE",
                        "refund-golden",
                        '2'));
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "refund", '3'));
        value.set(
                "candidatePlanRef",
                ref(
                        "MIRROR_PLAN",
                        "refund-shadow-plan",
                        '4'));
        value.set(
                "baselineBindingRef",
                ref(
                        "SHADOW_BASELINE_BINDING",
                        "refund-production",
                        '5'));
        value.set(
                "comparisonPolicyRef",
                ref(
                        "SHADOW_COMPARISON_POLICY",
                        "refund-semantic-v1",
                        '6'));
        ObjectNode grant =
                value.putObject("accessGrant");
        grant.put("accessMode", "READ_ONLY");
        grant.set(
                "samplingGrantRef",
                ref(
                        "SHADOW_SAMPLING_GRANT",
                        "grant-1",
                        '7'));
        grant.set(
                "egressAuthorityRef",
                ref(
                        "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION",
                        "egress-1",
                        '8'));
        grant.set(
                "killSwitchRef",
                ref(
                        "SHADOW_KILL_SWITCH_STATE",
                        "kill-switch-1",
                        '9'));
        grant.put("sampleOrdinal", 1);
        grant.put("maximumSamples", 100);
        value.put("deadlineAt", DEADLINE_AT.toString());
        return value;
    }

    private ObjectNode comparison() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V2);
        String requestFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                request,
                                ReadOnlyShadowJobVerifier
                                        .MAXIMUM_REQUEST_BYTES);
        value.put(
                "comparisonId",
                "shadow-" + requestFingerprint.substring(
                        "sha256:".length()));
        value.put("revision", 1);
        value.put("comparisonFingerprint", "");
        value.set("scope", scope());
        value.set(
                "inventoryRef",
                request.path("inventoryRef").deepCopy());
        value.put("unitId", "refund-golden");
        value.set(
                "scenarioCaseRef",
                request.path("scenarioCaseRef").deepCopy());
        value.set(
                "targetCapabilityRef",
                request.path(
                        "targetCapabilityRef").deepCopy());
        value.set(
                "comparisonPolicyRef",
                request.path(
                        "comparisonPolicyRef").deepCopy());
        value.set(
                "sourceResolutionAttestationRef",
                ref(
                        "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                        "sources-1",
                        'a'));
        ObjectNode proof =
                value.putObject("accessProof");
        ObjectNode grant =
                (ObjectNode) request.path(
                        "accessGrant");
        proof.set(
                "accessMode",
                grant.path("accessMode").deepCopy());
        proof.set(
                "samplingGrantRef",
                grant.path(
                        "samplingGrantRef").deepCopy());
        proof.set(
                "egressAuthorityRef",
                grant.path(
                        "egressAuthorityRef").deepCopy());
        proof.set(
                "killSwitchRef",
                grant.path("killSwitchRef").deepCopy());
        proof.set(
                "sampleOrdinal",
                grant.path("sampleOrdinal").deepCopy());
        proof.set(
                "maximumSamples",
                grant.path("maximumSamples").deepCopy());
        proof.put("writeCredentialExposed", false);
        proof.put("writeAttemptCount", 0);
        value.set(
                "baseline",
                observation(
                        "BASELINE",
                        "SHADOW_BASELINE_OBSERVATION",
                        'b'));
        value.set(
                "candidate",
                observation(
                        "CANDIDATE",
                        "MIRROR_EVIDENCE_BUNDLE",
                        'c'));
        value.put("observedAt", OBSERVED_AT.toString());
        ArrayNode results =
                value.putArray("results");
        ObjectNode behavior =
                results.addObject();
        behavior.put("dimension", "BEHAVIOR");
        behavior.put(
                "baselineFingerprint",
                fingerprint('d'));
        behavior.put(
                "candidateFingerprint",
                fingerprint('d'));
        behavior.put("outcome", "MATCH");
        behavior.putArray("diffTypes");
        value.set(
                "comparisonSeal",
                unsignedSeal());
        return value;
    }

    private ObjectNode job(
            String status,
            ObjectNode terminalComparison) {
        String requestFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                request,
                                ReadOnlyShadowJobVerifier
                                        .MAXIMUM_REQUEST_BYTES);
        String jobId = "shadow-"
                + requestFingerprint.substring(
                "sha256:".length());
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_V1);
        value.put("jobId", jobId);
        value.put(
                "requestId",
                request.path("requestId").asText());
        value.put(
                "requestFingerprint",
                requestFingerprint);
        value.set("scope", scope());
        value.put("status", status);
        value.put(
                "attemptCount",
                "QUEUED".equals(status) ? 0 : 1);
        value.put("maximumAttempts", 3);
        value.put(
                "nextEligibleAt",
                CREATED_AT.toString());
        value.put(
                "deadlineAt",
                DEADLINE_AT.toString());
        value.put(
                "leaseEpoch",
                "QUEUED".equals(status) ? 0 : 1);
        value.put(
                "leaseExpiresAt",
                ("QUEUED".equals(status)
                        ? CREATED_AT
                        : CREATED_AT.plusSeconds(60))
                        .toString());
        if (terminalComparison == null) {
            value.putNull("comparisonRef");
        } else {
            value.set(
                    "comparisonRef",
                    comparisonRef(terminalComparison));
        }
        value.put(
                "failureCode",
                "FAILED".equals(status)
                        ? "RG.MIRROR.SHADOW.FAILED" : "");
        value.put("createdAt", CREATED_AT.toString());
        value.put(
                "updatedAt",
                "QUEUED".equals(status)
                        ? CREATED_AT.toString()
                        : SIGNED_AT.toString());
        if ("SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "EXPIRED".equals(status)) {
            value.put(
                    "completedAt",
                    SIGNED_AT.toString());
        } else {
            value.putNull("completedAt");
        }
        value.put("recordFingerprint", "");
        value.put(
                "recordFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                value,
                                ReadOnlyShadowJobVerifier
                                        .MAXIMUM_JOB_BYTES));
        return value;
    }

    private void resignComparison(
            ObjectNode value) throws Exception {
        value.set(
                "comparisonSeal", unsignedSeal());
        value.put("comparisonFingerprint", "");
        value.put(
                "comparisonFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                value,
                                ReadOnlyShadowComparisonVerifier
                                        .MAXIMUM_COMPARISON_BYTES));
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V2");
        for (String field : new String[]{
                "schemaVersion",
                "comparisonId",
                "revision",
                "inventoryRef",
                "unitId",
                "observedAt",
                "comparisonFingerprint"}) {
            material.set(
                    field,
                    value.path(field).deepCopy());
        }
        String materialFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                ReadOnlyShadowComparisonVerifier
                                        .MAXIMUM_ATTESTATION_BYTES);
        Signature signature =
                Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(
                materialFingerprint.getBytes(
                        StandardCharsets.UTF_8));
        ObjectNode seal =
                value.putObject("comparisonSeal");
        seal.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        seal.put(
                "materialFingerprint",
                materialFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", key.keyId());
        seal.put("signedAt", SIGNED_AT.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(
                        signature.sign()));
    }

    private static ObjectNode observation(
            String role,
            String kind,
            char material) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("role", role);
        value.set(
                "artifactRef",
                ref(
                        kind,
                        role.toLowerCase(),
                        material));
        value.set("scope", scope());
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "refund", '3'));
        value.put(
                "requestContextFingerprint",
                fingerprint('e'));
        value.put(
                "semanticResultFingerprint",
                fingerprint(material));
        value.put(
                "completedAt",
                OBSERVED_AT.minusSeconds(1)
                        .toString());
        value.put(
                "evidenceClass", "CERTIFIABLE");
        value.put("evidenceComplete", true);
        return value;
    }

    private static ObjectNode comparisonRef(
            ObjectNode comparison) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "kind",
                "FIDELITY_SHADOW_COMPARISON");
        value.put(
                "id",
                comparison.path("comparisonId").asText());
        value.put("revision", 1);
        value.put(
                "fingerprint",
                comparison.path(
                        "comparisonFingerprint").asText());
        return value;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put("materialFingerprint", "");
        value.put("algorithm", "");
        value.put("keyId", "");
        value.put(
                "signedAt",
                Instant.EPOCH.toString());
        value.put("signature", "");
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "refunds");
        value.put("environmentId", "staging");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char material) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put(
                "fingerprint",
                fingerprint(material));
        return value;
    }

    private static String fingerprint(
            char material) {
        return "sha256:" + String.valueOf(material)
                .repeat(64);
    }
}
