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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowSourceResolutionAttestationVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper();
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-26T11:00:00Z");
    private static final Instant BASELINE_COMPLETED_AT =
            Instant.parse("2026-07-26T11:59:40Z");
    private static final Instant CANDIDATE_COMPLETED_AT =
            Instant.parse("2026-07-26T11:59:50Z");
    private static final Instant ADMITTED_AT =
            Instant.parse("2026-07-26T12:00:00Z");
    private static final Instant RESOLVED_AT =
            Instant.parse("2026-07-26T12:00:05Z");
    private static final Instant CONFIRMED_AT =
            Instant.parse("2026-07-26T12:00:06Z");
    private static final Instant ISSUED_AT =
            Instant.parse("2026-07-26T12:00:07Z");
    private static final Instant NOW =
            Instant.parse("2026-07-26T12:01:00Z");

    private AtomicReference<ReadOnlyShadowSourceBindingVerifier
            .VerificationResult> sourceBindingResult;
    private ReadOnlyShadowSourceResolutionAttestationVerifier
            verifier;
    private KeyPair attestationKeyPair;
    private EvidenceVerificationKey attestationKey;
    private EvidenceVerificationKey sourceBindingKey;
    private EvidenceVerificationKey candidateKey;
    private ObjectNode candidateBundle;
    private ObjectNode sourceBinding;
    private ObjectNode attestation;

    @BeforeEach
    void setUp() throws Exception {
        sourceBindingResult =
                new AtomicReference<>(
                        verifiedSourceBinding());
        verifier =
                new ReadOnlyShadowSourceResolutionAttestationVerifier(
                        (binding, key, context) ->
                                sourceBindingResult.get());
        attestationKeyPair =
                KeyPairGenerator.getInstance(
                        "Ed25519").generateKeyPair();
        attestationKey =
                key(
                        "source-resolution-key",
                        attestationKeyPair);
        sourceBindingKey =
                key(
                        "source-binding-key",
                        KeyPairGenerator.getInstance(
                                "Ed25519")
                                .generateKeyPair());
        candidateKey =
                key(
                        "candidate-key",
                        KeyPairGenerator.getInstance(
                                "Ed25519")
                                .generateKeyPair());
        candidateBundle = candidateBundle();
        sourceBinding = sourceBinding();
        attestation = signedAttestation();
    }

    @Test
    void verifiesExactIdentitySignatureBindingAndPolicyFactClosure() {
        CapabilityMirrorSchemaValidator.require(
                attestation,
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_SCHEMA_RESOURCE,
                "TEST_SOURCE_RESOLUTION_SCHEMA_INVALID");
        assertThat(attestation.path(
                "sourceBindingRef"))
                .isEqualTo(artifact(
                        "SHADOW_SOURCE_BINDING",
                        sourceBinding.path(
                                "bindingId").asText(),
                        sourceBinding.path(
                                "bindingFingerprint")
                                .asText()));
        var result =
                verifier.verify(
                        attestation,
                        attestationKey,
                        context(attestation));

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.attestationId())
                .startsWith("source-resolution-");
        assertThat(result.requestId())
                .isEqualTo("refund-shadow-request");
        assertThat(result.executionId())
                .isEqualTo("refund-shadow-execution");
        assertThat(result.attestationKeyId())
                .isEqualTo(attestationKey.keyId());
        assertThat(result.sourceBindingReason())
                .isEqualTo("VERIFIED");
        assertThat(attestation.at(
                "/comparisonPolicyRef/fingerprint")
                .asText())
                .isEqualTo(
                        "sha256:66cb081470a0492453c5a35bbf7e9b2bb530abc2dbaaf86be8a564bec4c11f43");
    }

    @Test
    void rejectsValidlyResignedCandidateFactDriftAndNestedBindingFailure()
            throws Exception {
        attestation.withObject(
                        "/candidate/normalizedFactFingerprints")
                .put(
                        "BEHAVIOR",
                        fingerprint('f'));
        resign(attestation);

        var drift =
                verifier.verify(
                        attestation,
                        attestationKey,
                        context(attestation));

        assertThat(drift.outcome())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.INVALID);
        assertThat(drift.reasonCode())
                .isEqualTo(
                        "SHADOW_SOURCE_RESOLUTION_CANDIDATE_CLOSURE_INVALID");

        attestation = signedAttestation();
        sourceBindingResult.set(
                new ReadOnlyShadowSourceBindingVerifier
                        .VerificationResult(
                        ReadOnlyShadowSourceBindingVerifier
                                .Outcome.CANDIDATE_INVALID,
                        "SHADOW_SOURCE_BINDING_CANDIDATE_CLOSURE_INVALID",
                        "refund-shadow-source",
                        1,
                        fingerprint('1'),
                        "candidate-run",
                        sourceBindingKey.keyId(),
                        candidateKey.keyId()));
        var nested =
                verifier.verify(
                        attestation,
                        attestationKey,
                        context(attestation));

        assertThat(nested.outcome())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.SOURCE_BINDING_INVALID);
        assertThat(nested.sourceBindingReason())
                .isEqualTo(
                        "SHADOW_SOURCE_BINDING_CANDIDATE_CLOSURE_INVALID");
    }

    @Test
    void rejectsExpectationSignatureAndPayloadBearingProtocolDrift()
            throws Exception {
        var wrongExecution =
                context(attestation,
                        "other-execution");
        assertThat(verifier.verify(
                attestation,
                attestationKey,
                wrongExecution).outcome())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.EXPECTATION_MISMATCH);

        attestation.withObject(
                        "/attestationSeal")
                .put(
                        "signature",
                        Base64.getEncoder()
                                .encodeToString(
                                        new byte[64]));
        assertThat(verifier.verify(
                attestation,
                attestationKey,
                context(attestation))
                .reasonCode())
                .isEqualTo(
                        "SHADOW_SOURCE_RESOLUTION_SIGNATURE_INVALID");

        attestation = signedAttestation();
        attestation.put(
                "requestPayload",
                "customer-secret");
        var payload =
                verifier.verify(
                        attestation,
                        attestationKey,
                        context(attestation));
        assertThat(payload.reasonCode())
                .isEqualTo(
                        "SHADOW_SOURCE_RESOLUTION_SCHEMA_INVALID");
        assertThat(payload.toString())
                .doesNotContain(
                        "customer-secret");
    }

    private ReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationContext context(
            ObjectNode value) {
        return context(
                value,
                "refund-shadow-execution");
    }

    private ReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationContext context(
            ObjectNode value,
            String executionId) {
        ObjectNode reference =
                artifact(
                        "SHADOW_SOURCE_RESOLUTION_ATTESTATION",
                        value.path("attestationId")
                                .asText(),
                        value.path(
                                "attestationFingerprint")
                                .asText());
        return new ReadOnlyShadowSourceResolutionAttestationVerifier
                .VerificationContext(
                scope(),
                reference,
                "refund-shadow-request",
                executionId,
                fingerprint('a'),
                sourceBinding,
                sourceBindingKey,
                new ReadOnlyShadowSourceBindingVerifier
                        .VerificationContext(
                        scope(),
                        sourceBindingRef(),
                        candidateBundle,
                        candidateKey,
                        NOW),
                NOW);
    }

    private ObjectNode signedAttestation()
            throws Exception {
        ObjectNode value =
                JSON.createObjectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V1);
        value.put(
                "attestationFingerprint", "");
        value.put(
                "attestationId", "pending");
        value.put("revision", 1);
        value.set("scope", scope());
        value.put(
                "requestId",
                "refund-shadow-request");
        value.put(
                "executionId",
                "refund-shadow-execution");
        value.set(
                "sourceBindingRef",
                sourceBindingRef());
        value.set(
                "comparisonPolicyRef",
                policyRef());
        value.put(
                "requestContextFingerprint",
                fingerprint('6'));
        value.put(
                "admissionFingerprint",
                fingerprint('a'));
        value.put(
                "admittedAt",
                ADMITTED_AT.toString());
        value.put(
                "confirmedAt",
                CONFIRMED_AT.toString());
        value.set(
                "baseline",
                baselineResolution());
        value.set(
                "candidate",
                candidateResolution());
        value.put(
                "issuedAt",
                ISSUED_AT.toString());
        value.set(
                "attestationSeal",
                unsignedSeal());
        resign(value);
        return value;
    }

    private void resign(
            ObjectNode value)
            throws Exception {
        value.put(
                "attestationId",
                deterministicId(value));
        ObjectNode material =
                value.deepCopy();
        material.put(
                "attestationFingerprint", "");
        material.remove("attestationSeal");
        value.put(
                "attestationFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                ReadOnlyShadowSourceResolutionAttestationVerifier
                                        .MAXIMUM_ATTESTATION_BYTES));
        String materialFingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                attestationMaterial(value),
                                ReadOnlyShadowSourceResolutionAttestationVerifier
                                        .MAXIMUM_SEAL_MATERIAL_BYTES);
        ObjectNode seal =
                value.withObject(
                        "/attestationSeal");
        seal.put(
                "materialFingerprint",
                materialFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put(
                "keyId", attestationKey.keyId());
        seal.put(
                "signedAt",
                ISSUED_AT.toString());
        seal.put(
                "signature",
                sign(
                        attestationKeyPair,
                        materialFingerprint));
    }

    private static String deterministicId(
            ObjectNode value) {
        ObjectNode identity =
                JSON.createObjectNode();
        identity.put(
                "domain",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_IDENTITY_V1");
        identity.put(
                "executionId",
                value.path("executionId")
                        .asText());
        identity.put(
                "admissionFingerprint",
                value.path(
                        "admissionFingerprint")
                        .asText());
        identity.put(
                "confirmedAt",
                value.path("confirmedAt")
                        .asText());
        identity.set(
                "baselineRef",
                value.at("/baseline/artifactRef")
                        .deepCopy());
        identity.put(
                "baselineResolvedAt",
                value.at("/baseline/resolvedAt")
                        .asText());
        identity.set(
                "candidateRef",
                value.at("/candidate/artifactRef")
                        .deepCopy());
        identity.put(
                "candidateResolvedAt",
                value.at("/candidate/resolvedAt")
                        .asText());
        String fingerprint =
                EvidenceVerificationSupport
                        .sha256Bounded(
                                identity,
                                64 * 1024);
        return "source-resolution-"
                + fingerprint.substring(
                "sha256:".length());
    }

    private static ObjectNode sourceBinding() {
        ObjectNode value =
                JSON.createObjectNode();
        value.put(
                "bindingId",
                "refund-shadow-source");
        value.put("revision", 1);
        value.put(
                "bindingFingerprint",
                fingerprint('1'));
        value.put(
                "baselineObservationFingerprint",
                fingerprint('2'));
        value.set("scope", scope());
        value.set(
                "comparisonPolicyRef",
                policyRef());
        value.put(
                "requestContextFingerprint",
                fingerprint('6'));
        ObjectNode baseline =
                value.putObject("baseline");
        baseline.put(
                "semanticResultFingerprint",
                fingerprint('7'));
        baseline.set(
                "normalizedFactFingerprints",
                baselineFacts());
        baseline.put(
                "observedAt",
                BASELINE_COMPLETED_AT.toString());
        baseline.put(
                "evidenceClass",
                "CERTIFIABLE");
        baseline.put(
                "evidenceComplete", true);
        baseline.put(
                "writeCredentialExposed", false);
        baseline.put(
                "writeAttemptCount", 0);
        value.set(
                "candidateEvidenceRef",
                candidateRef());
        return value;
    }

    private static ObjectNode candidateBundle() {
        ObjectNode bundle =
                JSON.createObjectNode();
        ObjectNode evidence =
                bundle.putObject("evidence");
        evidence.put(
                "semanticResultFingerprint",
                fingerprint('8'));
        evidence.put(
                "capabilityClosureFingerprint",
                fingerprint('9'));
        evidence.set(
                "externalBindings",
                JSON.createArrayNode());
        evidence.set(
                "resolutions",
                JSON.createArrayNode());
        evidence.put(
                "status", "PASSED");
        evidence.put(
                "evidenceClass",
                "CERTIFIABLE");
        evidence.put(
                "completedAt",
                CANDIDATE_COMPLETED_AT.toString());
        return bundle;
    }

    private static ObjectNode baselineResolution() {
        ObjectNode value =
                resolution(
                        "BASELINE",
                        baselineRef(),
                        fingerprint('7'),
                        baselineFacts(),
                        BASELINE_COMPLETED_AT);
        return value;
    }

    private static ObjectNode candidateResolution() {
        return resolution(
                "CANDIDATE",
                candidateRef(),
                fingerprint('8'),
                candidateFacts(),
                CANDIDATE_COMPLETED_AT);
    }

    private static ObjectNode resolution(
            String role,
            ObjectNode artifact,
            String semanticFingerprint,
            ObjectNode facts,
            Instant sourceCompletedAt) {
        ObjectNode value =
                JSON.createObjectNode();
        value.put("role", role);
        value.set("artifactRef", artifact);
        value.put(
                "semanticResultFingerprint",
                semanticFingerprint);
        value.set(
                "normalizedFactFingerprints",
                facts);
        value.put(
                "sourceCompletedAt",
                sourceCompletedAt.toString());
        value.put(
                "resolvedAt",
                RESOLVED_AT.toString());
        value.put(
                "evidenceClass",
                "CERTIFIABLE");
        value.put(
                "evidenceComplete", true);
        value.put(
                "writeCredentialExposed", false);
        value.put(
                "writeAttemptCount", 0);
        return value;
    }

    private static ObjectNode baselineFacts() {
        ObjectNode facts =
                JSON.createObjectNode();
        facts.put(
                "BEHAVIOR",
                fingerprint('7'));
        return facts;
    }

    private static ObjectNode candidateFacts() {
        ObjectNode facts =
                JSON.createObjectNode();
        facts.put(
                "BEHAVIOR",
                fingerprint('8'));
        facts.put(
                "CONTRACT",
                fingerprint('9'));
        ObjectNode effect =
                JSON.createObjectNode();
        effect.set(
                "externalBindings",
                JSON.createArrayNode());
        effect.set(
                "resolutions",
                JSON.createArrayNode());
        facts.put(
                "EFFECT",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                effect,
                                MirrorEvidenceVerifier
                                        .MAXIMUM_EVIDENCE_BYTES));
        return facts;
    }

    private static ObjectNode policyRef() {
        ObjectNode policy =
                JSON.createObjectNode();
        policy.put(
                "domain",
                "RESOURCE_GATEWAY_PAYLOAD_FREE_EQUALITY_POLICY_V1");
        ArrayNode rules =
                policy.putArray(
                        "normalizationRules");
        rules.add(
                "BEHAVIOR=semanticResultFingerprint");
        rules.add(
                "CONTRACT=capabilityClosureFingerprint");
        rules.add(
                "EFFECT=externalBindings+resolutions");
        rules.add(
                "STATE_TRANSITION=stateEvidenceFingerprint");
        ObjectNode types =
                policy.putObject("mismatchTypes");
        types.put("BEHAVIOR", "OUTPUT_VALUE");
        types.put("CONTRACT", "OUTPUT_SCHEMA");
        types.put("EFFECT", "EFFECT");
        types.put("STATE_TRANSITION", "STATE");
        return artifact(
                "SHADOW_COMPARISON_POLICY",
                "payload-free-equality-v1",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                policy,
                                64 * 1024));
    }

    private static ObjectNode sourceBindingRef() {
        return artifact(
                "SHADOW_SOURCE_BINDING",
                "refund-shadow-source",
                fingerprint('1'));
    }

    private static ObjectNode baselineRef() {
        return artifact(
                "SHADOW_BASELINE_OBSERVATION",
                "refund-shadow-source:baseline",
                fingerprint('2'));
    }

    private static ObjectNode candidateRef() {
        return artifact(
                "MIRROR_EVIDENCE_BUNDLE",
                "candidate-run",
                fingerprint('3'));
    }

    private static ObjectNode attestationMaterial(
            ObjectNode value) {
        ObjectNode material =
                JSON.createObjectNode();
        material.put(
                "domain",
                "RESOURCE_GATEWAY_READ_ONLY_SHADOW_SOURCE_RESOLUTION_ATTESTATION_V1");
        material.put(
                "schemaVersion",
                value.path("schemaVersion")
                        .asText());
        material.put(
                "attestationId",
                value.path("attestationId")
                        .asText());
        material.put(
                "revision",
                value.path("revision")
                        .asLong());
        material.set(
                "scope",
                value.path("scope")
                        .deepCopy());
        material.put(
                "issuedAt",
                value.path("issuedAt")
                        .asText());
        material.put(
                "attestationFingerprint",
                value.path(
                        "attestationFingerprint")
                        .asText());
        return material;
    }

    private static ReadOnlyShadowSourceBindingVerifier
            .VerificationResult verifiedSourceBinding() {
        return new ReadOnlyShadowSourceBindingVerifier
                .VerificationResult(
                ReadOnlyShadowSourceBindingVerifier
                        .Outcome.VERIFIED,
                "VERIFIED",
                "refund-shadow-source",
                1,
                fingerprint('1'),
                "candidate-run",
                "source-binding-key",
                "candidate-key");
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

    private static ObjectNode artifact(
            String kind,
            String id,
            String fingerprint) {
        ObjectNode value =
                JSON.createObjectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", 1);
        value.put(
                "fingerprint", fingerprint);
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value =
                JSON.createObjectNode();
        value.put(
                "tenantId", "tenant-a");
        value.put(
                "organizationId", "support");
        value.put(
                "projectId", "refunds");
        value.put(
                "environmentId", "staging");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value =
                JSON.createObjectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put(
                "materialFingerprint",
                fingerprint('0'));
        value.put("algorithm", "Ed25519");
        value.put("keyId", "unsigned");
        value.put(
                "signedAt",
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
        signer.update(
                materialFingerprint.getBytes(
                        StandardCharsets.UTF_8));
        return Base64.getEncoder()
                .encodeToString(
                        signer.sign());
    }

    private static String fingerprint(
            char value) {
        return "sha256:"
                + String.valueOf(value)
                .repeat(64);
    }
}
