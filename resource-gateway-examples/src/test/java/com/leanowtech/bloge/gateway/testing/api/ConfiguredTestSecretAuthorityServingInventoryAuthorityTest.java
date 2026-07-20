package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredTestSecretAuthorityServingInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String AUTHORITY = "secret-authority.example";

    private ObjectMapper objectMapper;
    private KeyPair signerA;
    private KeyPair signerB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        signerA = generator.generateKeyPair();
        signerB = generator.generateKeyPair();
    }

    @Test
    void verifiesDistinctAuthorityQuorumAndBindsSecretAuthorityIdentity() {
        var authority = authority(inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerA),
                signer("inventory-b", "key-b", signerB)), 2,
                List.of(key("inventory-a", "key-a", signerA),
                        key("inventory-b", "key-b", signerB)));

        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.externallyAttested()).isTrue();
            assertThat(observed.revision()).isEqualTo(17);
            assertThat(observed.sourceGenerationFingerprint())
                    .isEqualTo(observed.materialFingerprint());
            assertThat(observed.expectedInstanceIds())
                    .containsExactly("replica-a", "replica-b");
            assertThat(observed.validSignatureCount()).isEqualTo(2);
        });
        assertThat(authority.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.expectedReplicaCount()).isEqualTo(2);
            assertThat(descriptor.properties())
                    .containsEntry("authorityIdentityBound", true)
                    .containsEntry("privateMaterialPresent", false)
                    .doesNotContainKeys("instanceIds", "materialFingerprint",
                            "policyFingerprint", "publicKey", "privateKey");
        });
    }

    @Test
    void rejectsAuthoritySubstitutionSelfShrunkBindingAndWrongPolicy() {
        TestSecretAuthorityServingInventory inventory = inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerA));

        assertThatThrownBy(() -> authority(inventory, 1,
                List.of(key("inventory-a", "key-a", signerA)),
                binding("other-secret-authority.example", "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
        assertThatThrownBy(() -> authority(inventory, 1,
                List.of(key("inventory-a", "key-a", signerA)),
                binding(AUTHORITY, "replica-c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
        assertThatThrownBy(() -> new ConfiguredTestSecretAuthorityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                Set.of("sha256:" + "c".repeat(64)), 1,
                List.of(key("inventory-a", "key-a", signerA)), inventory,
                binding(AUTHORITY, "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void rejectsMaterialTamperingBadSignatureAndInsufficientQuorum() {
        TestSecretAuthorityServingInventory valid = inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerA));
        TestSecretAuthorityServingInventory tampered =
                new TestSecretAuthorityServingInventory(
                        TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                        material("substituted.example"), valid.materialFingerprint(),
                        valid.signatures());
        assertThatThrownBy(() -> authority(tampered, 1,
                List.of(key("inventory-a", "key-a", signerA))))
                .isInstanceOf(IllegalArgumentException.class);

        TestSecretAuthorityServingInventory badSignature = inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerB));
        assertThatThrownBy(() -> authority(badSignature, 1,
                List.of(key("inventory-a", "key-a", signerA))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification");
        assertThatThrownBy(() -> authority(valid, 2, List.of(
                key("inventory-a", "key-a", signerA),
                key("inventory-b", "key-b", signerB))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void hardExpiryClosesObservationWithoutRestart() {
        MutableClock clock = new MutableClock(NOW);
        TestSecretAuthorityServingInventory inventory = inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerA));
        var authority = new ConfiguredTestSecretAuthorityServingInventoryAuthority(
                objectMapper, clock, "inventory.example", Set.of(POLICY), 1,
                List.of(key("inventory-a", "key-a", signerA)), inventory,
                binding(AUTHORITY, "replica-a"));

        assertThat(authority.observation().available()).isTrue();
        clock.advance(Duration.ofHours(2));
        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("EXPIRED");
            assertThat(observed.expectedInstanceIds())
                    .containsExactly("replica-a", "replica-b");
        });
    }

    @Test
    void strictJsonAcceptsPublicMaterialAndRejectsPrivateOrUnknownFields() throws Exception {
        TestSecretAuthorityServingInventory inventory = inventory(material(AUTHORITY),
                signer("inventory-a", "key-a", signerA));
        String keysJson = """
                [{
                  "authorityId":"inventory-a",
                  "keyId":"key-a",
                  "publicKeyBase64":"%s",
                  "enabled":true,
                  "revoked":false
                }]
                """.formatted(Base64.getEncoder().encodeToString(
                signerA.getPublic().getEncoded()));

        var parsed = ConfiguredTestSecretAuthorityServingInventoryAuthority.fromJson(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                POLICY, 1, keysJson, objectMapper.writeValueAsString(inventory),
                binding(AUTHORITY, "replica-a"));
        assertThat(parsed.observation().available()).isTrue();

        String privateKey = keysJson.replace("\"revoked\":false",
                "\"revoked\":false,\"privateKey\":\"forbidden\"");
        assertThatThrownBy(() ->
                ConfiguredTestSecretAuthorityServingInventoryAuthority.fromJson(
                        objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                        POLICY, 1, privateKey, objectMapper.writeValueAsString(inventory),
                        binding(AUTHORITY, "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
        String unknownEnvelope = objectMapper.writeValueAsString(inventory)
                .replaceFirst("\\{", "{\"credential\":\"forbidden\",");
        assertThatThrownBy(() ->
                ConfiguredTestSecretAuthorityServingInventoryAuthority.fromJson(
                        objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                        POLICY, 1, keysJson, unknownEnvelope,
                        binding(AUTHORITY, "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }

    @Test
    void protocolRejectsNonCanonicalMembersAndRepeatedSigningAuthorities() {
        assertThatThrownBy(() -> material(List.of("replica-b", "replica-a"), AUTHORITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");
        assertThatThrownBy(() -> material(List.of("replica-a", "replica-a"), AUTHORITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");

        TestSecretAuthorityServingInventory.Material material = material(AUTHORITY);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var first = signer("inventory-a", "key-a", signerA).sign(fingerprint);
        var second = signer("inventory-a", "key-b", signerB).sign(fingerprint);
        assertThatThrownBy(() -> new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                material, fingerprint, List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    private ConfiguredTestSecretAuthorityServingInventoryAuthority authority(
            TestSecretAuthorityServingInventory inventory,
            int threshold,
            List<ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey> keys) {
        return authority(inventory, threshold, keys, binding(AUTHORITY, "replica-a"));
    }

    private ConfiguredTestSecretAuthorityServingInventoryAuthority authority(
            TestSecretAuthorityServingInventory inventory,
            int threshold,
            List<ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey> keys,
            ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding binding) {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                Set.of(POLICY), threshold, keys, inventory, binding);
    }

    private static ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey key(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX,
                true, false);
    }

    private static ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding binding(
            String authorityId, String instanceId) {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding(
                "test-secret-scope", "deployment-a", ARTIFACT,
                TestSecretAuthorityResponse.SCHEMA_VERSION, authorityId, instanceId);
    }

    private TestSecretAuthorityServingInventory inventory(
            TestSecretAuthorityServingInventory.Material material,
            Signer... signers) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures =
                java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                        .sorted(Comparator.comparing(
                                TestSecretAuthorityServingInventory.AuthoritySignature
                                        ::authorityId))
                        .toList();
        return new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private static TestSecretAuthorityServingInventory.Material material(String authorityId) {
        return material(List.of("replica-a", "replica-b"), authorityId);
    }

    private static TestSecretAuthorityServingInventory.Material material(
            List<String> instances, String authorityId) {
        return new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-2026-07-20-17", 17,
                "test-secret-scope", "deployment-a", ARTIFACT,
                TestSecretAuthorityResponse.SCHEMA_VERSION, authorityId,
                instances, POLICY, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3600));
    }

    private static Signer signer(String authorityId, String keyId, KeyPair keyPair) {
        return new Signer(authorityId, keyId, keyPair, NOW.minusSeconds(30));
    }

    private record Signer(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            Instant signedAt) {

        private TestSecretAuthorityServingInventory.AuthoritySignature sign(
                String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSecretAuthorityServingInventory.AuthoritySignature(
                        authorityId, keyId, "Ed25519", signedAt,
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
