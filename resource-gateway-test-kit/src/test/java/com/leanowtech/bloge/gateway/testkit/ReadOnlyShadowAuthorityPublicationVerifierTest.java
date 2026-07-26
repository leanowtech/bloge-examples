package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityPublicationVerifierTest {
    private static final Instant ISSUED_AT =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SIGNED_AT =
            Instant.parse("2026-01-01T00:00:10Z");
    private static final Instant VALID_FROM =
            Instant.parse("2026-01-01T00:00:30Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-01-01T00:10:00Z");
    private static final Instant VERIFICATION_TIME =
            Instant.parse("2026-01-01T00:05:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReadOnlyShadowAuthorityPublicationVerifier verifier =
            new ReadOnlyShadowAuthorityPublicationVerifier();

    @Test
    void independentlyVerifiesAllThreeDomainSeparatedCurrentHeads() throws Exception {
        KeyPair pair = keyPair();

        for (ReadOnlyShadowAuthorityBinding.Type type
                : ReadOnlyShadowAuthorityBinding.Type.values()) {
            JsonNode publication = publication(type, pair, type.signatureDomain(), ignored -> {
            });
            var result = verifier.verify(
                    publication,
                    binding(type, publication),
                    key(pair, type,
                            ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                    VERIFICATION_TIME);

            assertThat(result.verified()).as(type.name()).isTrue();
            assertThat(result.reasonCode()).isEqualTo("VERIFIED");
            assertThat(result.streamId()).isEqualTo(streamId(type));
            assertThat(result.revision()).isEqualTo(1);
        }
    }

    @Test
    void rejectsUnknownFieldsTamperingAndCrossProtocolSignatureReplay() throws Exception {
        KeyPair pair = keyPair();
        JsonNode grant = publication(
                ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT.signatureDomain(),
                ignored -> {
                });
        ObjectNode unknown = grant.deepCopy();
        unknown.put("trusted", true);
        assertThat(verifier.verify(
                unknown,
                binding(ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT, grant),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_SCHEMA_INVALID");

        ObjectNode tampered = grant.deepCopy();
        ((ObjectNode) tampered.path("material")).put("maximumSamples", 999);
        assertThat(verifier.verify(
                tampered,
                binding(ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT, tampered),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_MATERIAL_FINGERPRINT_INVALID");

        JsonNode replay = publication(
                ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT.signatureDomain(),
                ignored -> {
                });
        assertThat(verifier.verify(
                replay,
                binding(ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH, replay),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_MATERIAL_FINGERPRINT_INVALID");
    }

    @Test
    void exactCurrentHeadAndFullScopePreventStaleOrCrossTenantUse() throws Exception {
        KeyPair pair = keyPair();
        JsonNode publication = publication(
                ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH.signatureDomain(),
                ignored -> {
                });
        ReadOnlyShadowAuthorityBinding current =
                binding(ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH, publication);
        ReadOnlyShadowAuthorityBinding stale =
                new ReadOnlyShadowAuthorityBinding(
                        current.type(),
                        current.streamId(),
                        current.revision(),
                        fingerprint('f'),
                        current.scope(),
                        current.issuer());
        assertThat(verifier.verify(
                publication,
                stale,
                key(pair, ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_CURRENT_HEAD_BINDING_MISMATCH");

        var wrongScope = new ReadOnlyShadowAuthorityBinding.Scope(
                "tenant-b",
                current.scope().organizationId(),
                current.scope().projectId(),
                current.scope().environmentId(),
                current.scope().region());
        assertThat(verifier.verify(
                publication,
                new ReadOnlyShadowAuthorityBinding(
                        current.type(),
                        current.streamId(),
                        current.revision(),
                        current.publicationFingerprint(),
                        wrongScope,
                        current.issuer()),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_CURRENT_HEAD_BINDING_MISMATCH");
    }

    @Test
    void keyAbsenceRevocationWrongIssuerAndSigningWindowFailClosed() throws Exception {
        KeyPair pair = keyPair();
        JsonNode publication = publication(
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY.signatureDomain(),
                ignored -> {
                });
        ReadOnlyShadowAuthorityBinding binding =
                binding(ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY, publication);

        assertThat(verifier.verify(
                publication, binding, null, VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_UNAVAILABLE");
        assertThat(verifier.verify(
                publication,
                binding,
                key(pair, ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                        ReadOnlyShadowAuthorityVerificationKey.State.REVOKED),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");
        assertThat(verifier.verify(
                publication,
                binding,
                new ReadOnlyShadowAuthorityVerificationKey(
                        "shadow-authority-key",
                        "Ed25519",
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        "wrong-authority",
                        binding.scope(),
                        binding.type(),
                        ISSUED_AT.minusSeconds(60),
                        EXPIRES_AT.plusSeconds(60),
                        null,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");
        assertThat(verifier.verify(
                publication,
                binding,
                new ReadOnlyShadowAuthorityVerificationKey(
                        "shadow-authority-key",
                        "Ed25519",
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        "shadow-governance",
                        binding.scope(),
                        binding.type(),
                        SIGNED_AT.plusSeconds(1),
                        EXPIRES_AT.plusSeconds(60),
                        null,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");
    }

    @Test
    void exclusiveExpiryAndBoundedPolicyDurationsAreEnforcedIndependently()
            throws Exception {
        KeyPair pair = keyPair();
        JsonNode publication = publication(
                ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT.signatureDomain(),
                ignored -> {
                });
        assertThat(verifier.verify(
                publication,
                binding(ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT, publication),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                EXPIRES_AT).reasonCode())
                .isEqualTo("PUBLICATION_OUTSIDE_VALIDITY_WINDOW");

        JsonNode unboundedPolicy = publication(
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY.signatureDomain(),
                material -> ((ObjectNode) material.path("limits"))
                        .put("startWindow", "P2D"));
        assertThat(verifier.verify(
                unboundedPolicy,
                binding(ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY, unboundedPolicy),
                key(pair, ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_POLICY_INVALID");
    }

    @Test
    void keyDelegationRetirementAndCanonicalSignatureEncodingAreExact()
            throws Exception {
        KeyPair pair = keyPair();
        JsonNode publication = publication(
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                pair,
                ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY
                        .signatureDomain(),
                ignored -> {
                });
        ReadOnlyShadowAuthorityBinding binding =
                binding(
                        ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                        publication);

        assertThat(verifier.verify(
                publication,
                binding,
                key(pair,
                        ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");

        var historical =
                new ReadOnlyShadowAuthorityVerificationKey(
                        "shadow-authority-key",
                        "Ed25519",
                        Base64.getEncoder().encodeToString(
                                pair.getPublic().getEncoded()),
                        "shadow-governance",
                        binding.scope(),
                        binding.type(),
                        ISSUED_AT.minusSeconds(60),
                        EXPIRES_AT.plusSeconds(60),
                        SIGNED_AT.plusSeconds(1),
                        ReadOnlyShadowAuthorityVerificationKey
                                .State.RETIRED);
        var retiredAtSigning =
                new ReadOnlyShadowAuthorityVerificationKey(
                        historical.keyId(),
                        historical.algorithm(),
                        historical.encodedPublicKey(),
                        historical.issuer(),
                        historical.scope(),
                        historical.publicationType(),
                        historical.notBefore(),
                        historical.notAfter(),
                        SIGNED_AT,
                        ReadOnlyShadowAuthorityVerificationKey
                                .State.RETIRED);
        assertThat(verifier.verify(
                publication,
                binding,
                historical,
                VERIFICATION_TIME).verified()).isTrue();
        assertThat(verifier.verify(
                publication,
                binding,
                retiredAtSigning,
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("AUTHORITY_KEY_POLICY_REJECTED");

        ObjectNode unpadded = publication.deepCopy();
        ObjectNode seal = (ObjectNode) unpadded.path("seal");
        seal.put(
                "signature",
                seal.path("signature").asText()
                        .replaceAll("=+$", ""));
        assertThat(verifier.verify(
                unpadded,
                binding(
                        ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                        unpadded),
                key(pair,
                        ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY,
                        ReadOnlyShadowAuthorityVerificationKey.State.ACTIVE),
                VERIFICATION_TIME).reasonCode())
                .isEqualTo("PUBLICATION_SCHEMA_INVALID");
    }

    private JsonNode publication(
            ReadOnlyShadowAuthorityBinding.Type type,
            KeyPair pair,
            String signingDomain,
            Consumer<ObjectNode> materialMutation) throws Exception {
        ObjectNode material = material(type);
        materialMutation.accept(material);
        ObjectNode signatureMaterial = mapper.createObjectNode();
        signatureMaterial.put("domain", signingDomain);
        signatureMaterial.put("schemaVersion", type.schemaVersion());
        signatureMaterial.set("material", material);
        String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                signatureMaterial,
                ReadOnlyShadowAuthorityPublicationVerifier.MAXIMUM_MATERIAL_BYTES);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode seal = mapper.createObjectNode();
        seal.put("materialFingerprint", materialFingerprint);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", "shadow-authority-key");
        seal.put("signedAt", SIGNED_AT.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(signer.sign()));

        ObjectNode fingerprintMaterial = mapper.createObjectNode();
        fingerprintMaterial.put("schemaVersion", type.schemaVersion());
        fingerprintMaterial.put("publicationFingerprint", "");
        fingerprintMaterial.put("materialFingerprint", materialFingerprint);
        fingerprintMaterial.set("material", material);
        fingerprintMaterial.set("seal", seal);
        String publicationFingerprint = EvidenceVerificationSupport.sha256Bounded(
                fingerprintMaterial,
                ReadOnlyShadowAuthorityPublicationVerifier.MAXIMUM_PUBLICATION_BYTES);

        ObjectNode publication = fingerprintMaterial.deepCopy();
        publication.put("publicationFingerprint", publicationFingerprint);
        return publication;
    }

    private ObjectNode material(ReadOnlyShadowAuthorityBinding.Type type) {
        ObjectNode material = mapper.createObjectNode();
        material.put(type.streamIdField(), streamId(type));
        material.put("revision", 1);
        material.put("previousPublicationFingerprint", "");
        if (type == ReadOnlyShadowAuthorityBinding.Type.GUARD_POLICY) {
            material.set("guardScope", scope());
            ObjectNode limits = mapper.createObjectNode();
            limits.put("maximumConcurrent", 8);
            limits.put("maximumStartsPerWindow", 100);
            limits.put("startWindow", "PT1M");
            limits.put("circuitFailureThreshold", 5);
            limits.put("circuitCoolDown", "PT30S");
            material.set("limits", limits);
        } else {
            material.set("scope", scope());
        }
        if (type == ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT) {
            material.put("active", true);
            material.put("maximumSamples", 10_000);
            material.set("guardScope", scope());
            ObjectNode policyRef = mapper.createObjectNode();
            policyRef.put("kind", "SHADOW_EXECUTION_GUARD_POLICY");
            policyRef.put("id", "customer-support-pressure-policy");
            policyRef.put("revision", 1);
            policyRef.put("fingerprint", fingerprint('a'));
            material.set("guardPolicyRef", policyRef);
        }
        if (type == ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH) {
            material.put("enabled", true);
        }
        material.put("issuedAt", ISSUED_AT.toString());
        material.put(
                type == ReadOnlyShadowAuthorityBinding.Type.KILL_SWITCH
                        ? "effectiveAt" : "validFrom",
                VALID_FROM.toString());
        material.put("expiresAt", EXPIRES_AT.toString());
        material.put("issuer", "shadow-governance");
        return material;
    }

    private ObjectNode scope() {
        ObjectNode scope = mapper.createObjectNode();
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "support");
        scope.put("projectId", "refunds");
        scope.put("environmentId", "staging");
        scope.put("region", "sg");
        return scope;
    }

    private ReadOnlyShadowAuthorityBinding binding(
            ReadOnlyShadowAuthorityBinding.Type type,
            JsonNode publication) {
        return new ReadOnlyShadowAuthorityBinding(
                type,
                streamId(type),
                1,
                publication.path("publicationFingerprint").asText(),
                new ReadOnlyShadowAuthorityBinding.Scope(
                        "tenant-a", "support", "refunds", "staging", "sg"),
                "shadow-governance");
    }

    private ReadOnlyShadowAuthorityVerificationKey key(
            KeyPair pair,
            ReadOnlyShadowAuthorityBinding.Type publicationType,
            ReadOnlyShadowAuthorityVerificationKey.State state) {
        return new ReadOnlyShadowAuthorityVerificationKey(
                "shadow-authority-key",
                "Ed25519",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "shadow-governance",
                new ReadOnlyShadowAuthorityBinding.Scope(
                        "tenant-a",
                        "support",
                        "refunds",
                        "staging",
                        "sg"),
                publicationType,
                ISSUED_AT.minusSeconds(60),
                EXPIRES_AT.plusSeconds(60),
                null,
                state);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String streamId(ReadOnlyShadowAuthorityBinding.Type type) {
        return switch (type) {
            case GUARD_POLICY -> "customer-support-pressure-policy";
            case SAMPLING_GRANT -> "refund-shadow-grant";
            case KILL_SWITCH -> "refund-shadow-switch";
        };
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
