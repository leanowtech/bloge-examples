package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowAuthorityKeySetVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-26T10:30:00Z");
    private static final String KEY_SET_ID = "shadow-sampling-keys:staging";
    private static final String ISSUER = "data-governance:shadow";
    private static final String TRUST_DOMAIN = "security:shadow-bootstrap";
    private static final String POLICY = fingerprint('a');

    private KeyPair rootA;
    private KeyPair rootB;
    private KeyPair authorityA;
    private KeyPair authorityB;
    private ReadOnlyShadowAuthorityKeySetVerifier.ExpectedBinding binding;
    private List<ReadOnlyShadowAuthorityKeySetVerifier.RootVerificationKey> roots;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        rootA = generator.generateKeyPair();
        rootB = generator.generateKeyPair();
        authorityA = generator.generateKeyPair();
        authorityB = generator.generateKeyPair();
        binding = new ReadOnlyShadowAuthorityKeySetVerifier.ExpectedBinding(
                scope(), ReadOnlyShadowAuthorityBinding.Type.SAMPLING_GRANT,
                ISSUER, KEY_SET_ID, TRUST_DOMAIN, 2, Set.of(POLICY));
        roots = List.of(
                root("security-root:a", "root-key:a", rootA, true),
                root("security-root:b", "root-key:b", rootB, true));
    }

    @Test
    void verifiesTerminalGenesisAndExposesOnlyVerifiedRetainedKeys() throws Exception {
        ObjectNode genesis = publication(1, "", List.of(
                key("authority:a", authorityA, "ACTIVE")));
        ObjectNode page = page(0, "", List.of(genesis), genesis, false);

        var result = new ReadOnlyShadowAuthorityKeySetVerifier().verifyPage(
                page, binding, roots, null, NOW.plusSeconds(1));

        assertThat(result.current()).isTrue();
        assertThat(result.trustedState()).isNotNull();
        assertThat(result.trustedState().generation()).isEqualTo(1);
        assertThat(result.trustedState().keys())
                .extracting(ReadOnlyShadowAuthorityVerificationKey::keyId)
                .containsExactly("authority:a");
    }

    @Test
    void advancesAcrossBoundedPagesAndMakesRevocationIrreversible() throws Exception {
        ObjectNode activeA = key("authority:a", authorityA, "ACTIVE");
        ObjectNode genesis = publication(1, "", List.of(activeA));
        ObjectNode revokedA = key("authority:a", authorityA, "REVOKED");
        ObjectNode activeB = key("authority:b", authorityB, "ACTIVE");
        ObjectNode second = publication(2, genesis.path("publicationFingerprint").asText(),
                List.of(revokedA, activeB));
        var verifier = new ReadOnlyShadowAuthorityKeySetVerifier();

        var firstPage = verifier.verifyPage(
                page(0, "", List.of(genesis), second, true),
                binding, roots, null, NOW.plusSeconds(1));
        assertThat(firstPage.verified()).isTrue();
        assertThat(firstPage.current()).isFalse();
        assertThat(firstPage.hasMore()).isTrue();

        var terminal = verifier.verifyPage(
                page(1, genesis.path("publicationFingerprint").asText(),
                        List.of(second), second, false),
                binding, roots, firstPage.trustedState(), NOW.plusSeconds(1));
        assertThat(terminal.current()).isTrue();
        assertThat(terminal.trustedState().keys())
                .filteredOn(key -> key.keyId().equals("authority:a"))
                .extracting(ReadOnlyShadowAuthorityVerificationKey::state)
                .containsExactly(ReadOnlyShadowAuthorityVerificationKey.State.REVOKED);

        ObjectNode reactivated = publication(
                3, second.path("publicationFingerprint").asText(),
                List.of(activeA, activeB));
        var rejected = verifier.verifyPage(
                page(2, second.path("publicationFingerprint").asText(),
                        List.of(reactivated), reactivated, false),
                binding, roots, terminal.trustedState(), NOW.plusSeconds(1));
        assertThat(rejected.verified()).isFalse();
        assertThat(rejected.reasonCode()).isEqualTo("AUTHORITY_KEY_LIFECYCLE_INVALID");
        assertThat(rejected.trustedState()).isNull();
    }

    @Test
    void rejectsCheckpointDriftRevokedRootsAndExpiredCurrentHead() throws Exception {
        ObjectNode genesis = publication(1, "", List.of(
                key("authority:a", authorityA, "ACTIVE")));
        var verifier = new ReadOnlyShadowAuthorityKeySetVerifier();
        var current = verifier.verifyPage(
                page(0, "", List.of(genesis), genesis, false),
                binding, roots, null, NOW.plusSeconds(1));

        ObjectNode wrongCheckpoint = page(
                1, fingerprint('f'), List.of(), genesis, false);
        assertThat(verifier.verifyPage(
                wrongCheckpoint, binding, roots, current.trustedState(),
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("PAGE_CHECKPOINT_MISMATCH");

        ObjectNode invalidHighWater = page(
                0, "", List.of(), genesis.deepCopy(), true);
        ((ObjectNode) invalidHighWater.path("highWaterPublication"))
                .put("publicationFingerprint", fingerprint('e'));
        assertThat(verifier.verifyPage(
                invalidHighWater, binding, roots, null,
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("PAGE_HIGH_WATER_INVALID");

        var revokedRoot = root(
                "security-root:a", "root-key:a", rootA, false);
        assertThat(verifier.verifyPage(
                page(0, "", List.of(genesis), genesis, false),
                binding, List.of(revokedRoot, roots.get(1)), null,
                NOW.plusSeconds(1)).reasonCode())
                .isEqualTo("BOOTSTRAP_ROOT_POLICY_REJECTED");

        assertThat(verifier.verifyPage(
                page(0, "", List.of(genesis), genesis, false),
                binding, roots, null, NOW.plusSeconds(3_600)).reasonCode())
                .isEqualTo("PUBLICATION_OUTSIDE_VALIDITY_WINDOW");
    }

    private ObjectNode publication(
            long generation, String previous, List<ObjectNode> keys) throws Exception {
        ObjectNode material = JSON.createObjectNode();
        material.put("keySetId", KEY_SET_ID);
        material.put("generation", generation);
        material.put("previousPublicationFingerprint", previous);
        material.set("scope", scopeJson());
        material.put("publicationKind", "SAMPLING_GRANT");
        material.put("issuer", ISSUER);
        material.put("rootTrustDomain", TRUST_DOMAIN);
        material.put("rootThreshold", 2);
        material.put("policyFingerprint", POLICY);
        material.put("issuedAt", NOW.minusSeconds(1).toString());
        material.put("notBefore", NOW.toString());
        material.put("expiresAt", NOW.plusSeconds(3_600).toString());
        ArrayNode keyArray = material.putArray("keys");
        keys.forEach(keyArray::add);

        ObjectNode signedMaterial = JSON.createObjectNode();
        signedMaterial.put("domain",
                ReadOnlyShadowAuthorityKeySetVerifier.SIGNATURE_DOMAIN);
        signedMaterial.put("schemaVersion",
                CapabilityMirrorProtocol.READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PUBLICATION_V1);
        signedMaterial.set("material", material);
        String materialFingerprint = EvidenceVerificationSupport.sha256Bounded(
                signedMaterial, ReadOnlyShadowAuthorityKeySetVerifier.MAXIMUM_MATERIAL_BYTES);

        ArrayNode signatures = JSON.createArrayNode();
        signatures.add(rootSignature(
                "security-root:a", "root-key:a", rootA, materialFingerprint));
        signatures.add(rootSignature(
                "security-root:b", "root-key:b", rootB, materialFingerprint));

        ObjectNode publication = JSON.createObjectNode();
        publication.put("schemaVersion",
                CapabilityMirrorProtocol.READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PUBLICATION_V1);
        publication.put("publicationFingerprint", "");
        publication.put("materialFingerprint", materialFingerprint);
        publication.set("material", material);
        publication.set("signatures", signatures);
        String publicationFingerprint = EvidenceVerificationSupport.sha256Bounded(
                publication, ReadOnlyShadowAuthorityKeySetVerifier.MAXIMUM_PUBLICATION_BYTES);
        publication.put("publicationFingerprint", publicationFingerprint);
        return publication;
    }

    private static ObjectNode page(
            long after,
            String afterFingerprint,
            List<ObjectNode> publications,
            ObjectNode highWater,
            boolean hasMore) {
        ObjectNode page = JSON.createObjectNode();
        page.put("schemaVersion",
                CapabilityMirrorProtocol.READ_ONLY_SHADOW_AUTHORITY_KEY_SET_PAGE_V1);
        page.put("generatedAt", NOW.plusSeconds(1).toString());
        page.set("scope", scopeJson());
        page.put("publicationKind", "SAMPLING_GRANT");
        page.put("issuer", ISSUER);
        page.put("keySetId", KEY_SET_ID);
        page.put("afterGeneration", after);
        page.put("afterPublicationFingerprint", afterFingerprint);
        long through = publications.isEmpty()
                ? after : publications.getLast().at("/material/generation").asLong();
        page.put("throughGeneration", through);
        page.put("highWaterGeneration", highWater.at("/material/generation").asLong());
        page.put("highWaterPublicationFingerprint",
                highWater.path("publicationFingerprint").asText());
        page.set("highWaterPublication", highWater);
        page.put("hasMore", hasMore);
        ArrayNode values = page.putArray("publications");
        publications.forEach(values::add);
        return page;
    }

    private static ObjectNode key(String keyId, KeyPair pair, String state) {
        ObjectNode key = JSON.createObjectNode();
        key.put("keyId", keyId);
        key.put("algorithm", "Ed25519");
        key.put("encodedPublicKey",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        key.put("notBefore", NOW.minusSeconds(60).toString());
        key.put("notAfter", NOW.plusSeconds(7_200).toString());
        key.putNull("retiredAt");
        key.put("state", state);
        return key;
    }

    private static ObjectNode rootSignature(
            String authorityId,
            String keyId,
            KeyPair pair,
            String materialFingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
        ObjectNode signature = JSON.createObjectNode();
        signature.put("authorityId", authorityId);
        signature.put("keyId", keyId);
        signature.put("algorithm", "Ed25519");
        signature.put("signedAt", NOW.toString());
        signature.put("signature",
                Base64.getEncoder().encodeToString(signer.sign()));
        return signature;
    }

    private static ReadOnlyShadowAuthorityKeySetVerifier.RootVerificationKey root(
            String authorityId,
            String keyId,
            KeyPair pair,
            boolean allowed) {
        return new ReadOnlyShadowAuthorityKeySetVerifier.RootVerificationKey(
                authorityId, keyId, "Ed25519",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                NOW.minusSeconds(60), NOW.plusSeconds(7_200),
                allowed
                        ? ReadOnlyShadowAuthorityKeySetVerifier.RootVerificationKey.State.ACTIVE
                        : ReadOnlyShadowAuthorityKeySetVerifier.RootVerificationKey.State.REVOKED);
    }

    private static ReadOnlyShadowAuthorityBinding.Scope scope() {
        return new ReadOnlyShadowAuthorityBinding.Scope(
                "tenant-a", "org-a", "project-a", "staging", "ap-southeast-1");
    }

    private static ObjectNode scopeJson() {
        ObjectNode scope = JSON.createObjectNode();
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", "org-a");
        scope.put("projectId", "project-a");
        scope.put("environmentId", "staging");
        scope.put("region", "ap-southeast-1");
        return scope;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
