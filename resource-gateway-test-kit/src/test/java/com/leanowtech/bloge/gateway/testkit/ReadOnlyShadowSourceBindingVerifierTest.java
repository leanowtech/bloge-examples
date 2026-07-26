package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowSourceBindingVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper();
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-26T11:00:00Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-26T11:59:40Z");
    private static final Instant ISSUED_AT =
            Instant.parse("2026-07-26T11:59:50Z");
    private static final Instant VALID_FROM =
            Instant.parse("2026-07-26T12:00:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-26T13:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-07-26T12:01:00Z");

    private AtomicReference<MirrorEvidenceVerifier
            .VerificationResult> candidateVerification;
    private ReadOnlyShadowSourceBindingVerifier verifier;
    private KeyPair bindingKeyPair;
    private EvidenceVerificationKey bindingKey;
    private EvidenceVerificationKey candidateKey;
    private ObjectNode candidate;
    private ObjectNode binding;

    @BeforeEach
    void setUp() throws Exception {
        candidateVerification =
                new AtomicReference<>();
        verifier =
                new ReadOnlyShadowSourceBindingVerifier(
                        (bundle, key) ->
                                candidateVerification.get());
        bindingKeyPair =
                KeyPairGenerator.getInstance(
                        "Ed25519").generateKeyPair();
        bindingKey = key(
                "source-binding-key",
                bindingKeyPair);
        candidateKey = key(
                "candidate-key",
                KeyPairGenerator.getInstance(
                        "Ed25519").generateKeyPair());
        candidate = candidate();
        candidateVerification.set(
                candidateVerification(true));
        binding = signedBinding();
    }

    @Test
    void verifiesExactJobCandidateAndDoubleAddressClosure()
            throws Exception {
        ReadOnlyShadowSourceBindingVerifier
                .VerificationResult result =
                verifier.verify(
                        binding,
                        bindingKey,
                        context(binding));

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.bindingId())
                .isEqualTo("refund-shadow-source-1");
        assertThat(result.candidateRunId())
                .isEqualTo("candidate-run-1");
        assertThat(result.bindingKeyId())
                .isEqualTo(bindingKey.keyId());
        assertThat(result.candidateKeyId())
                .isEqualTo(candidateKey.keyId());
    }

    @Test
    void rejectsResignedNestedBaselineAndJobReferenceDrift()
            throws Exception {
        binding.withObject("/baseline")
                .put(
                        "semanticResultFingerprint",
                        fingerprint('9'));
        resignOuter(binding);
        assertThat(verifier.verify(
                binding, bindingKey, context(binding))
                .reasonCode()).isEqualTo(
                "SHADOW_SOURCE_BINDING_BASELINE_FINGERPRINT_INVALID");

        binding = signedBinding();
        ObjectNode wrongReference =
                sourceReference(binding);
        wrongReference.put(
                "fingerprint", fingerprint('8'));
        assertThat(verifier.verify(
                binding,
                bindingKey,
                context(binding, wrongReference))
                .outcome()).isEqualTo(
                ReadOnlyShadowSourceBindingVerifier
                        .Outcome.BINDING_MISMATCH);
    }

    @Test
    void rejectsInvalidCandidateAndValidlySignedCandidateCoordinateDrift()
            throws Exception {
        candidateVerification.set(
                candidateVerification(false));
        assertThat(verifier.verify(
                binding, bindingKey, context(binding))
                .reasonCode()).isEqualTo(
                "SHADOW_SOURCE_BINDING_CANDIDATE_MIRROR_EVIDENCE_FINGERPRINT_INVALID");

        candidateVerification.set(
                candidateVerification(true));
        candidate.withObject("/evidence")
                .put(
                        "requestContextFingerprint",
                        fingerprint('9'));
        assertThat(verifier.verify(
                binding, bindingKey, context(binding))
                .reasonCode()).isEqualTo(
                "SHADOW_SOURCE_BINDING_CANDIDATE_CLOSURE_INVALID");
    }

    @Test
    void rejectsExpiredKeyPolicySignatureAndPayloadBearingBinding()
            throws Exception {
        ReadOnlyShadowSourceBindingVerifier
                .VerificationContext expired =
                new ReadOnlyShadowSourceBindingVerifier
                        .VerificationContext(
                        binding.path("scope"),
                        sourceReference(binding),
                        candidate,
                        candidateKey,
                        EXPIRES_AT);
        assertThat(verifier.verify(
                binding, bindingKey, expired)
                .outcome()).isEqualTo(
                ReadOnlyShadowSourceBindingVerifier
                        .Outcome.WINDOW_REJECTED);

        EvidenceVerificationKey disabled =
                new EvidenceVerificationKey(
                        TestingProtocol
                                .EVIDENCE_VERIFICATION_KEY_V1,
                        bindingKey.keyId(),
                        bindingKey.algorithm(),
                        bindingKey.encodedPublicKey(),
                        bindingKey.createdAt(),
                        "REVOKED",
                        "test");
        assertThat(verifier.verify(
                binding, disabled, context(binding))
                .outcome()).isEqualTo(
                ReadOnlyShadowSourceBindingVerifier
                        .Outcome.POLICY_REJECTED);

        binding.withObject("/bindingSeal")
                .put(
                        "signature",
                        Base64.getEncoder()
                                .encodeToString(
                                        new byte[64]));
        assertThat(verifier.verify(
                binding, bindingKey, context(binding))
                .reasonCode()).isEqualTo(
                "SHADOW_SOURCE_BINDING_SIGNATURE_INVALID");

        binding = signedBinding();
        binding.put("requestPayload", "customer-secret");
        ReadOnlyShadowSourceBindingVerifier
                .VerificationResult invalid =
                verifier.verify(
                        binding,
                        bindingKey,
                        context(binding));
        assertThat(invalid.reasonCode()).isEqualTo(
                "SHADOW_SOURCE_BINDING_SCHEMA_INVALID");
        assertThat(invalid.toString())
                .doesNotContain("customer-secret");
    }

    private ReadOnlyShadowSourceBindingVerifier
            .VerificationContext context(
            ObjectNode value) {
        return context(value, sourceReference(value));
    }

    private ReadOnlyShadowSourceBindingVerifier
            .VerificationContext context(
            ObjectNode value,
            ObjectNode reference) {
        return new ReadOnlyShadowSourceBindingVerifier
                .VerificationContext(
                value.path("scope"),
                reference,
                candidate,
                candidateKey,
                NOW);
    }

    private ObjectNode signedBinding() throws Exception {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_SOURCE_BINDING_V1);
        value.put("bindingFingerprint", "");
        value.put("bindingId",
                "refund-shadow-source-1");
        value.put("revision", 1);
        value.set("scope", scope());
        value.set("scenarioCaseRef",
                artifact(
                        "SCENARIO_CASE",
                        "refund-golden",
                        fingerprint('1')));
        value.set("targetCapabilityRef",
                artifact(
                        "CAPABILITY",
                        "refund",
                        fingerprint('2')));
        value.set("candidatePlanRef",
                artifact(
                        "MIRROR_PLAN",
                        "candidate-plan-1",
                        fingerprint('3')));
        value.set("baselineBindingRef",
                artifact(
                        "SHADOW_BASELINE_BINDING",
                        "production-read-v1",
                        fingerprint('4')));
        value.set("comparisonPolicyRef",
                artifact(
                        "SHADOW_COMPARISON_POLICY",
                        "behavior-v1",
                        fingerprint('5')));
        value.put(
                "requestContextFingerprint",
                fingerprint('6'));
        value.put(
                "baselineObservationFingerprint",
                "");
        ObjectNode baseline =
                value.putObject("baseline");
        baseline.put(
                "semanticResultFingerprint",
                fingerprint('7'));
        baseline.putObject(
                        "normalizedFactFingerprints")
                .put("BEHAVIOR", fingerprint('7'));
        baseline.put(
                "observedAt",
                OBSERVED_AT.toString());
        baseline.put(
                "evidenceClass", "CERTIFIABLE");
        baseline.put("evidenceComplete", true);
        baseline.put(
                "writeCredentialExposed", false);
        baseline.put("writeAttemptCount", 0);
        value.set(
                "candidateEvidenceRef",
                artifact(
                        "MIRROR_EVIDENCE_BUNDLE",
                        "candidate-run-1",
                        fingerprint('8')));
        value.put("validFrom",
                VALID_FROM.toString());
        value.put("expiresAt",
                EXPIRES_AT.toString());
        value.put("issuedAt",
                ISSUED_AT.toString());
        value.set("bindingSeal",
                unsignedSeal());
        String baselineFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                baselineMaterial(value),
                                ReadOnlyShadowSourceBindingVerifier
                                        .MAXIMUM_BINDING_BYTES);
        value.put(
                "baselineObservationFingerprint",
                baselineFingerprint);
        resignOuter(value);
        return value;
    }

    private void resignOuter(ObjectNode value)
            throws Exception {
        ObjectNode material = value.deepCopy();
        material.put("bindingFingerprint", "");
        material.remove("bindingSeal");
        value.put(
                "bindingFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                ReadOnlyShadowSourceBindingVerifier
                                        .MAXIMUM_BINDING_BYTES));
        ObjectNode seal =
                value.withObject("/bindingSeal");
        String materialFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                attestationMaterial(value),
                                ReadOnlyShadowSourceBindingVerifier
                                        .MAXIMUM_ATTESTATION_BYTES);
        seal.put("materialFingerprint",
                materialFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", bindingKey.keyId());
        seal.put("signedAt",
                VALID_FROM.toString());
        seal.put("signature",
                sign(
                        bindingKeyPair,
                        materialFingerprint));
    }

    private static ObjectNode baselineMaterial(
            ObjectNode value) {
        ObjectNode material = JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1");
        material.put(
                "schemaVersion",
                value.path("schemaVersion").asText());
        material.put("bindingId",
                value.path("bindingId").asText());
        material.put("revision",
                value.path("revision").asLong());
        material.set("scope",
                value.path("scope").deepCopy());
        material.set("targetCapabilityRef",
                value.path("targetCapabilityRef")
                        .deepCopy());
        material.set("baselineBindingRef",
                value.path("baselineBindingRef")
                        .deepCopy());
        material.set("comparisonPolicyRef",
                value.path("comparisonPolicyRef")
                        .deepCopy());
        material.put(
                "requestContextFingerprint",
                value.path(
                        "requestContextFingerprint")
                        .asText());
        material.set("baseline",
                value.path("baseline").deepCopy());
        return material;
    }

    private static ObjectNode attestationMaterial(
            ObjectNode value) {
        ObjectNode material = JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_BINDING_V1");
        material.put(
                "schemaVersion",
                value.path("schemaVersion").asText());
        material.put("bindingId",
                value.path("bindingId").asText());
        material.put("revision",
                value.path("revision").asLong());
        material.set("scope",
                value.path("scope").deepCopy());
        material.put("issuedAt",
                value.path("issuedAt").asText());
        material.put(
                "bindingFingerprint",
                value.path(
                        "bindingFingerprint").asText());
        return material;
    }

    private static ObjectNode candidate() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .MIRROR_EVIDENCE_BUNDLE_V1);
        value.put(
                "bundleFingerprint",
                fingerprint('8'));
        ObjectNode evidence =
                value.putObject("evidence");
        evidence.put("runId",
                "candidate-run-1");
        evidence.put("planId",
                "candidate-plan-1");
        evidence.put(
                "planFingerprint",
                fingerprint('3'));
        evidence.set("scope", scope());
        evidence.set("rootCapability",
                artifact(
                        "CAPABILITY",
                        "refund",
                        fingerprint('2')));
        evidence.put(
                "requestContextFingerprint",
                fingerprint('6'));
        evidence.put(
                "completedAt",
                ISSUED_AT.minusSeconds(1)
                        .toString());
        return value;
    }

    private MirrorEvidenceVerifier.VerificationResult
            candidateVerification(boolean verified) {
        return new MirrorEvidenceVerifier
                .VerificationResult(
                verified
                        ? MirrorEvidenceVerifier
                        .Outcome.VERIFIED
                        : MirrorEvidenceVerifier
                        .Outcome.INVALID,
                verified
                        ? "VERIFIED"
                        : "MIRROR_EVIDENCE_FINGERPRINT_INVALID",
                "candidate-run-1",
                fingerprint('3'),
                fingerprint('8'),
                fingerprint('9'),
                candidateKey.keyId());
    }

    private static EvidenceVerificationKey key(
            String keyId,
            KeyPair keyPair) {
        return new EvidenceVerificationKey(
                TestingProtocol
                        .EVIDENCE_VERIFICATION_KEY_V1,
                keyId,
                "Ed25519",
                Base64.getEncoder()
                        .encodeToString(
                                keyPair.getPublic()
                                        .getEncoded()),
                CREATED_AT,
                "ACTIVE",
                "test");
    }

    private static ObjectNode sourceReference(
            ObjectNode value) {
        return artifact(
                "SHADOW_SOURCE_BINDING",
                value.path("bindingId").asText(),
                value.path(
                        "bindingFingerprint").asText());
    }

    private static ObjectNode artifact(
            String kind,
            String id,
            String fingerprint) {
        ObjectNode value = JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value = JSON.createObjectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "support");
        value.put("projectId", "refunds");
        value.put("environmentId", "staging");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value = JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put(
                "materialFingerprint",
                fingerprint('0'));
        value.put("algorithm", "Ed25519");
        value.put("keyId", "unsigned");
        value.put("signedAt",
                Instant.EPOCH.toString());
        value.put("signature", "unsigned");
        return value;
    }

    private static String sign(
            KeyPair keyPair,
            String materialFingerprint)
            throws Exception {
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(materialFingerprint.getBytes(
                StandardCharsets.UTF_8));
        return Base64.getEncoder()
                .encodeToString(signer.sign());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value)
                .repeat(64);
    }
}
