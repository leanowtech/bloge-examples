package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredTestSuiteStabilityServingInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String SCOPE = "stability-fleet";
    private static final String COHORT = "release-2026-07-19";

    private ObjectMapper objectMapper;
    private KeyPair authorityA;
    private KeyPair authorityB;
    private KeyPair authorityC;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authorityA = generator.generateKeyPair();
        authorityB = generator.generateKeyPair();
        authorityC = generator.generateKeyPair();
    }

    @Test
    void verifiesDistinctAuthorityQuorumAndPublishesOnlyAggregateFacts() throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA),
                signer("authority-b", "key-b", authorityB));

        var authority = authority(inventory, 2, List.of(
                key("authority-a", "key-a", authorityA),
                key("authority-b", "key-b", authorityB)));

        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.externallyAttested()).isTrue();
            assertThat(observed.revision()).isEqualTo(17);
            assertThat(observed.expectedInstanceIds())
                    .containsExactly("replica-a", "replica-b");
            assertThat(observed.validSignatureCount()).isEqualTo(2);
        });
        assertThat(authority.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.expectedReplicaCount()).isEqualTo(2);
            assertThat(descriptor.properties())
                    .containsEntry("privateMaterialPresent", false)
                    .doesNotContainKeys("instanceIds", "materialFingerprint",
                            "policyFingerprint", "publicKey", "privateKey");
        });
    }

    @Test
    void rejectsSelfShrunkBindingWrongPolicyAndMissingLocalInstance() throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA));

        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                Set.of(POLICY), 1, List.of(key("authority-a", "key-a", authorityA)),
                inventory, binding("replica-c")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                Set.of("sha256:" + "c".repeat(64)), 1,
                List.of(key("authority-a", "key-a", authorityA)),
                inventory, binding("replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
        assertThatThrownBy(() -> new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "other.example",
                Set.of(POLICY), 1, List.of(key("authority-a", "key-a", authorityA)),
                inventory, binding("replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void rejectsTamperingTrustedBadSignatureAndInsufficientQuorum() throws Exception {
        TestSuiteStabilityServingInventory valid = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        TestSuiteStabilityServingInventory tampered = new TestSuiteStabilityServingInventory(
                TestSuiteStabilityServingInventory.SCHEMA_VERSION, material(18),
                valid.materialFingerprint(), valid.signatures());

        assertThatThrownBy(() -> authority(tampered, 1,
                List.of(key("authority-a", "key-a", authorityA))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");

        TestSuiteStabilityServingInventory badSignature = inventory(material(17),
                signer("authority-a", "key-a", authorityB));
        assertThatThrownBy(() -> authority(badSignature, 1,
                List.of(key("authority-a", "key-a", authorityA))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification");

        assertThatThrownBy(() -> authority(valid, 2, List.of(
                key("authority-a", "key-a", authorityA),
                key("authority-b", "key-b", authorityB))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void hardExpiryClosesObservationWithoutRestart() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        TestSuiteStabilityServingInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        var authority = new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, clock, "inventory.example", Set.of(POLICY), 1,
                List.of(key("authority-a", "key-a", authorityA)),
                inventory, binding("replica-a"));

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
    void rejectsFutureExpiredAndExcessiveInventoryLifetimes() throws Exception {
        assertInvalidTime(material(17, NOW, NOW.plusSeconds(1), NOW.plusSeconds(60)));
        assertInvalidTime(material(17, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW));
        assertInvalidTime(material(17, NOW.minusSeconds(1), NOW.minusSeconds(1),
                NOW.plus(Duration.ofDays(30)).plusSeconds(1)));
    }

    @Test
    void strictJsonParserAcceptsPublicMaterialAndRejectsUnknownFields() throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        String keysJson = """
                [{
                  "authorityId":"authority-a",
                  "keyId":"key-a",
                  "publicKeyBase64":"%s",
                  "enabled":true,
                  "revoked":false
                }]
                """.formatted(Base64.getEncoder().encodeToString(
                authorityA.getPublic().getEncoded()));

        var parsed = ConfiguredTestSuiteStabilityServingInventoryAuthority.fromJson(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                POLICY, 1, keysJson, objectMapper.writeValueAsString(inventory),
                binding("replica-a"));
        assertThat(parsed.observation().available()).isTrue();

        String unknownKeyField = keysJson.replace("\"revoked\":false",
                "\"revoked\":false,\"privateKey\":\"forbidden\"");
        assertThatThrownBy(() ->
                ConfiguredTestSuiteStabilityServingInventoryAuthority.fromJson(
                        objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                        "inventory.example", POLICY, 1, unknownKeyField,
                        objectMapper.writeValueAsString(inventory), binding("replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");

        String unknownEnvelopeField = objectMapper.writeValueAsString(inventory)
                .replaceFirst("\\{", "{\"credential\":\"forbidden\",");
        assertThatThrownBy(() ->
                ConfiguredTestSuiteStabilityServingInventoryAuthority.fromJson(
                        objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                        "inventory.example", POLICY, 1, keysJson,
                        unknownEnvelopeField, binding("replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }

    @Test
    void protocolRejectsNonCanonicalInstancesAndRepeatedAuthorities() throws Exception {
        assertThatThrownBy(() -> material(List.of("replica-b", "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");
        assertThatThrownBy(() -> material(List.of("replica-a", "replica-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");

        TestSuiteStabilityServingInventory.Material material = material(17);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var first = signer("authority-a", "key-a", authorityA).sign(fingerprint);
        var second = signer("authority-a", "key-b", authorityB).sign(fingerprint);
        assertThatThrownBy(() -> new TestSuiteStabilityServingInventory(
                TestSuiteStabilityServingInventory.SCHEMA_VERSION,
                material, fingerprint, List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    private void assertInvalidTime(TestSuiteStabilityServingInventory.Material material)
            throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(material,
                signer("authority-a", "key-a", authorityA));
        assertThatThrownBy(() -> authority(inventory, 1,
                List.of(key("authority-a", "key-a", authorityA))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");
    }

    private ConfiguredTestSuiteStabilityServingInventoryAuthority authority(
            TestSuiteStabilityServingInventory inventory,
            int threshold,
            List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "inventory.example",
                Set.of(POLICY), threshold, keys, inventory, binding("replica-a"));
    }

    private static ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX,
                true, false);
    }

    private ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding(
            String localInstance) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding(
                SCOPE, COHORT, ARTIFACT, ToolStudioResourceGatewayProtocol.VERSION,
                localInstance);
    }

    private TestSuiteStabilityServingInventory inventory(
            TestSuiteStabilityServingInventory.Material material,
            Signer... signers) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                java.util.Arrays.stream(signers)
                        .map(signer -> signer.sign(fingerprint))
                        .sorted(java.util.Comparator.comparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::authorityId))
                        .toList();
        return new TestSuiteStabilityServingInventory(
                TestSuiteStabilityServingInventory.SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private static TestSuiteStabilityServingInventory.Material material(long revision) {
        return material(revision, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3600));
    }

    private static TestSuiteStabilityServingInventory.Material material(
            long revision, Instant issuedAt, Instant notBefore, Instant expiresAt) {
        return material(List.of("replica-a", "replica-b"), revision,
                issuedAt, notBefore, expiresAt);
    }

    private static TestSuiteStabilityServingInventory.Material material(List<String> instances) {
        return material(instances, 17, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3600));
    }

    private static TestSuiteStabilityServingInventory.Material material(
            List<String> instances,
            long revision,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        return new TestSuiteStabilityServingInventory.Material(
                TestSuiteStabilityServingInventory.Material.SCHEMA_VERSION,
                "inventory.example", "inventory-2026-07-19-17", revision,
                SCOPE, COHORT, ARTIFACT, ToolStudioResourceGatewayProtocol.VERSION,
                instances, POLICY, issuedAt, notBefore, expiresAt);
    }

    private static Signer signer(
            String authorityId, String keyId, KeyPair keyPair) {
        return new Signer(authorityId, keyId, keyPair, NOW.minusSeconds(30));
    }

    private record Signer(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            Instant signedAt) {

        private TestSuiteStabilityServingInventory.AuthoritySignature sign(
                String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSuiteStabilityServingInventory.AuthoritySignature(
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
