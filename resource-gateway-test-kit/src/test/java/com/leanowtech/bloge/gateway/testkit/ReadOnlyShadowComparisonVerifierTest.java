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

class ReadOnlyShadowComparisonVerifierTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant COMPLETED_AT =
            Instant.parse("2026-07-25T00:00:00Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-25T00:00:01Z");
    private static final Instant SIGNED_AT =
            Instant.parse("2026-07-25T00:00:02Z");

    private ReadOnlyShadowComparisonVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode comparison;

    @BeforeEach
    void setUp() throws Exception {
        verifier =
                new ReadOnlyShadowComparisonVerifier();
        keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "shadow-comparison-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                CREATED_AT,
                "ACTIVE",
                "test");
        comparison = comparison();
        resign(comparison);
    }

    @Test
    void verifiesWithoutTrustingServerImplementationClasses() {
        ReadOnlyShadowComparisonVerifier.VerificationResult
                result = verifier.verify(comparison, key);

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.outcome()).isEqualTo(
                ReadOnlyShadowComparisonVerifier
                        .Outcome.VERIFIED);
        assertThat(result.comparisonId())
                .isEqualTo("shadow-refund-golden-1");
        assertThat(result.unitId())
                .isEqualTo("refund-golden");
    }

    @Test
    void verifiesV2PolicyAndSourceResolutionClosure()
            throws Exception {
        comparison = comparisonV2();
        resign(comparison);

        assertThat(verifier.verify(
                comparison, key).verified()).isTrue();

        comparison.remove("comparisonPolicyRef");
        resign(comparison);
        assertThat(verifier.verify(
                comparison, key).reasonCode())
                .isEqualTo(
                        "SHADOW_COMPARISON_SCHEMA_INVALID");

        comparison = comparisonV2();
        comparison.withObject(
                "/sourceResolutionAttestationRef")
                .put("kind", "UNTRUSTED_SOURCE_LABEL");
        resign(comparison);
        assertThat(verifier.verify(
                comparison, key).reasonCode())
                .isEqualTo(
                        "SHADOW_COMPARISON_SCHEMA_INVALID");
    }

    @Test
    void rejectsResignedRequestPairAndSamplingBudgetDrift()
            throws Exception {
        comparison.withObject("/candidate")
                .put(
                        "requestContextFingerprint",
                        fingerprint('9'));
        resign(comparison);
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_SOURCE_PAIR_INVALID");

        comparison = comparison();
        comparison.withObject("/accessProof")
                .put("sampleOrdinal", 101);
        resign(comparison);
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_ZERO_WRITE_PROOF_INVALID");
    }

    @Test
    void rejectsResignedForgedOutcomeAndCrossDimensionDiff()
            throws Exception {
        comparison.withObject("/results/0")
                .put("candidateFingerprint", fingerprint('8'));
        resign(comparison);
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_RESULT_DERIVATION_INVALID");

        comparison = comparison();
        ObjectNode contract =
                comparison.withObject("/results/1");
        contract.put(
                "candidateFingerprint", fingerprint('8'));
        contract.put("outcome", "MISMATCH");
        contract.putArray("diffTypes").add("RETRY");
        resign(comparison);
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_DIFF_TYPE_INVALID");
    }

    @Test
    void rejectsContentAddressSignatureAndStrictSchemaTamper()
            throws Exception {
        comparison.put(
                "comparisonFingerprint",
                fingerprint('f'));
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_FINGERPRINT_INVALID");

        comparison = comparison();
        resign(comparison);
        comparison.withObject("/comparisonSeal")
                .put(
                        "signature",
                        Base64.getEncoder()
                                .encodeToString(new byte[64]));
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_SIGNATURE_INVALID");

        comparison = comparison();
        comparison.put("requestPayload", "customer-secret");
        comparison.withObject("/accessProof")
                .put("writeCredentialExposed", true);
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_SCHEMA_INVALID");
        assertThat(verifier.verify(comparison, key)
                .toString()).doesNotContain(
                "customer-secret");
    }

    @Test
    void reportsUnavailableAndPolicyRejectedKeys() {
        assertThat(verifier.verify(
                comparison, null).outcome())
                .isEqualTo(
                        ReadOnlyShadowComparisonVerifier
                                .Outcome.KEY_UNAVAILABLE);

        EvidenceVerificationKey revoked =
                new EvidenceVerificationKey(
                        TestingProtocol
                                .EVIDENCE_VERIFICATION_KEY_V1,
                        key.keyId(),
                        key.algorithm(),
                        key.encodedPublicKey(),
                        key.createdAt(),
                        "REVOKED",
                        "test");
        assertThat(verifier.verify(
                comparison, revoked).outcome())
                .isEqualTo(
                        ReadOnlyShadowComparisonVerifier
                                .Outcome.POLICY_REJECTED);
    }

    @Test
    void appliesTheSameBoundedSigningClockSkewAsTheServer()
            throws Exception {
        resign(
                comparison,
                OBSERVED_AT.minusSeconds(120));
        assertThat(verifier.verify(comparison, key)
                .verified()).isTrue();

        resign(
                comparison,
                OBSERVED_AT.minusSeconds(121));
        assertThat(verifier.verify(comparison, key)
                .reasonCode()).isEqualTo(
                "SHADOW_COMPARISON_KEY_POLICY_REJECTED");
    }

    @Test
    void packagesStrictSchemaAndReferenceClosure() {
        assertThat(
                getClass().getResource(
                        CapabilityMirrorProtocol
                                .READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE))
                .isNotNull();
        CapabilityMirrorSchemaValidator.require(
                comparison,
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_SCHEMA_RESOURCE,
                "SHADOW_COMPARISON_INVALID");
    }

    private void resign(ObjectNode value)
            throws Exception {
        resign(value, SIGNED_AT);
    }

    private void resign(
            ObjectNode value,
            Instant signedAt)
            throws Exception {
        value.set(
                "comparisonSeal", unsignedSeal());
        value.put("comparisonFingerprint", "");
        String fingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        ReadOnlyShadowComparisonVerifier
                                .MAXIMUM_COMPARISON_BYTES);
        value.put(
                "comparisonFingerprint",
                fingerprint);
        String materialFingerprint =
                EvidenceVerificationSupport.sha256Bounded(
                        attestationMaterial(value),
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
        seal.put("signedAt", signedAt.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(
                        signature.sign()));
    }

    private static ObjectNode comparison() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V1);
        value.put(
                "comparisonId",
                "shadow-refund-golden-1");
        value.put("revision", 1);
        value.put("comparisonFingerprint", "");
        value.set("scope", scope());
        value.set(
                "inventoryRef",
                ref(
                        "DOMAIN_FIDELITY_INVENTORY",
                        "refund-domain",
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
        ObjectNode access =
                value.putObject("accessProof");
        access.put("accessMode", "READ_ONLY");
        access.set(
                "samplingGrantRef",
                ref(
                        "SHADOW_SAMPLING_GRANT",
                        "grant-1",
                        '4'));
        access.set(
                "egressAuthorityRef",
                ref(
                        "MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION",
                        "egress-1",
                        '5'));
        access.set(
                "killSwitchRef",
                ref(
                        "SHADOW_KILL_SWITCH_STATE",
                        "kill-switch-1",
                        '6'));
        access.put("sampleOrdinal", 1);
        access.put("maximumSamples", 100);
        access.put(
                "writeCredentialExposed", false);
        access.put("writeAttemptCount", 0);
        value.set(
                "baseline",
                observation(
                        "BASELINE",
                        "SHADOW_BASELINE_OBSERVATION",
                        "baseline-1",
                        '7'));
        value.set(
                "candidate",
                observation(
                        "CANDIDATE",
                        "MIRROR_EVIDENCE_BUNDLE",
                        "candidate-1",
                        '8'));
        value.put(
                "observedAt",
                OBSERVED_AT.toString());
        ArrayNode results =
                value.putArray("results");
        ObjectNode behavior =
                results.addObject();
        behavior.put("dimension", "BEHAVIOR");
        behavior.put(
                "baselineFingerprint",
                fingerprint('a'));
        behavior.put(
                "candidateFingerprint",
                fingerprint('a'));
        behavior.put("outcome", "MATCH");
        behavior.putArray("diffTypes");
        ObjectNode contract =
                results.addObject();
        contract.put("dimension", "CONTRACT");
        contract.put(
                "baselineFingerprint",
                fingerprint('b'));
        contract.put(
                "candidateFingerprint",
                fingerprint('b'));
        contract.put("outcome", "MATCH");
        contract.putArray("diffTypes");
        value.set(
                "comparisonSeal", unsignedSeal());
        return value;
    }

    private static ObjectNode comparisonV2() {
        ObjectNode value = comparison();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V2);
        value.set(
                "comparisonPolicyRef",
                ref(
                        "SHADOW_COMPARISON_POLICY",
                        "refund-semantic-v1",
                        'e'));
        value.set(
                "sourceResolutionAttestationRef",
                ref(
                        "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                        "source-verification-1",
                        'f'));
        return value;
    }

    private static ObjectNode observation(
            String role,
            String kind,
            String id,
            char material) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("role", role);
        value.set(
                "artifactRef",
                ref(kind, id, material));
        value.set("scope", scope());
        value.set(
                "targetCapabilityRef",
                ref("CAPABILITY", "refund", '3'));
        value.put(
                "requestContextFingerprint",
                fingerprint('c'));
        value.put(
                "semanticResultFingerprint",
                fingerprint('d'));
        value.put(
                "completedAt",
                COMPLETED_AT.toString());
        value.put(
                "evidenceClass", "CERTIFIABLE");
        value.put("evidenceComplete", true);
        return value;
    }

    private static ObjectNode attestationMaterial(
            ObjectNode value) {
        ObjectNode material =
                JsonNodeFactory.instance.objectNode();
        material.put(
                "domain",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_COMPARISON_V1
                        .equals(value.path(
                                "schemaVersion").asText())
                        ? "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V1"
                        : "RESOURCE_GATEWAY_READ_ONLY_SHADOW_COMPARISON_V2");
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
        return material;
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
            String kind, String id, char material) {
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

    private static String fingerprint(char material) {
        return "sha256:"
                + String.valueOf(material).repeat(64);
    }
}
