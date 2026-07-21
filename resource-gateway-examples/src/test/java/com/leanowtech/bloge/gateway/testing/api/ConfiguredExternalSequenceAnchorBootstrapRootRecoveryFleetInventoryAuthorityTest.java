package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.LaneResolver;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.AuthoritySignature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding;
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
import java.util.Map;
import java.util.Set;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String POLICY = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private KeyPair signerA;
    private KeyPair signerB;
    private Lane laneA;
    private Lane laneB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        signerA = generator.generateKeyPair();
        signerB = generator.generateKeyPair();
        laneA = lane("tenant", "roots-a", 'a');
        laneB = lane("tenant", "roots-b", 'b');
    }

    @Test
    void verifiesQuorumAndResolvesEverySignedDescriptorFromTheLocalCatalog() {
        var authority = authority(attestation(material(laneA.descriptor(), laneB.descriptor()),
                        signer("inventory-a", "key-a", signerA),
                        signer("inventory-b", "key-b", signerB)),
                2, List.of(key("inventory-a", "key-a", signerA),
                        key("inventory-b", "key-b", signerB)),
                catalog(laneA, laneB));

        assertThat(authority.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.generation()).isEqualTo(17L);
            assertThat(snapshot.lanes()).containsExactly(laneA, laneB);
            assertThat(snapshot.descriptors())
                    .containsExactly(laneA.descriptor(), laneB.descriptor());
        });
        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.status()).isEqualTo("VERIFIED");
            assertThat(observed.laneCount()).isEqualTo(2);
            assertThat(observed.validSignatureCount()).isEqualTo(2);
            assertThat(observed.requiredSignatureCount()).isEqualTo(2);
        });
        assertThat(authority.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.generation()).isEqualTo(17L);
            assertThat(descriptor.laneCount()).isEqualTo(2);
            assertThat(descriptor.properties())
                    .containsEntry("fleetTopologyBound", true)
                    .containsEntry("exactRuntimeBinding", true)
                    .containsEntry("runtimeExpiryFence", true)
                    .containsEntry("automaticRefresh", false)
                    .containsEntry("signedRevocation", false)
                    .containsEntry("durableGenerationFloor", false)
                    .doesNotContainKeys("fleetId", "laneKeys", "materialFingerprint",
                            "policyFingerprint", "publicKey", "privateKey");
        });
    }

    @Test
    void acceptsAQuorumSignedEmptyDrainWithoutConsultingTheRuntimeCatalog() {
        var authority = authority(attestation(material(List.of()),
                        signer("inventory-a", "key-a", signerA)),
                1, List.of(key("inventory-a", "key-a", signerA)), key -> {
                    throw new AssertionError("empty signed inventory must not resolve a lane");
                });

        assertThat(authority.snapshot().lanes()).isEmpty();
        assertThat(authority.observation().laneCount()).isZero();
    }

    @Test
    void rejectsDeploymentArtifactFleetTopologyAndPolicySubstitution() {
        var signed = attestation(material(laneA.descriptor()),
                signer("inventory-a", "key-a", signerA));
        List<AuthorityKey> keys = List.of(key("inventory-a", "key-a", signerA));

        assertThatThrownBy(() -> authority(signed, 1, keys, catalog(laneA),
                new VerifiedBinding("other-scope", "bootstrap-recovery", ARTIFACT, 4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
        assertThatThrownBy(() -> authority(signed, 1, keys, catalog(laneA),
                new VerifiedBinding("recovery-prod", "other-fleet", ARTIFACT, 4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
        assertThatThrownBy(() -> authority(signed, 1, keys, catalog(laneA),
                new VerifiedBinding("recovery-prod", "bootstrap-recovery",
                        "sha256:" + "c".repeat(64), 4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
        assertThatThrownBy(() -> authority(signed, 1, keys, catalog(laneA),
                new VerifiedBinding("recovery-prod", "bootstrap-recovery", ARTIFACT, 3)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "fleet-inventory.example",
                Set.of("sha256:" + "d".repeat(64)), 1, keys, signed,
                binding(), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("binding");
    }

    @Test
    void rejectsMaterialTamperingBadSignatureAndInsufficientQuorum() {
        Material original = material(laneA.descriptor());
        var valid = attestation(original, signer("inventory-a", "key-a", signerA));
        Material tamperedMaterial = new Material(Material.SCHEMA_VERSION,
                original.trustDomain(), original.inventoryId(), original.generation() + 1,
                original.deploymentScopeId(), original.fleetId(),
                original.artifactFingerprint(), original.partitionCount(),
                original.laneDescriptors(), original.policyFingerprint(), original.issuedAt(),
                original.notBefore(), original.expiresAt());
        var tampered = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                tamperedMaterial, valid.materialFingerprint(), valid.signatures());

        assertThatThrownBy(() -> authority(tampered, 1,
                List.of(key("inventory-a", "key-a", signerA)), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");

        var badSignature = attestation(original,
                signer("inventory-a", "key-a", signerB));
        assertThatThrownBy(() -> authority(badSignature, 1,
                List.of(key("inventory-a", "key-a", signerA)), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification");

        assertThatThrownBy(() -> authority(valid, 2,
                List.of(key("inventory-a", "key-a", signerA),
                        key("inventory-b", "key-b", signerB)), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("threshold");

        assertThatThrownBy(() -> authority(valid, 1,
                List.of(new AuthorityKey("inventory-a", "key-a", signerA.getPublic(),
                        Instant.MIN, Instant.MAX, true, true)), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("threshold");

        var badSignatureTime = attestation(original,
                new Signer("inventory-a", "key-a", signerA, NOW.minusSeconds(600)));
        assertThatThrownBy(() -> authority(badSignatureTime, 1,
                List.of(key("inventory-a", "key-a", signerA)), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature time");
    }

    @Test
    void rejectsNonCanonicalLanesRepeatedAuthoritiesAndNullEnvelopeMembers() {
        assertThatThrownBy(() -> material(List.of(laneB.descriptor(), laneA.descriptor())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("material");
        assertThatThrownBy(() -> material(List.of(laneA.descriptor(), laneA.descriptor())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("material");
        assertThatThrownBy(() -> material(java.util.Arrays.asList((LaneDescriptor) null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("material");

        Material material = material(laneA.descriptor());
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        AuthoritySignature first = signer("inventory-a", "key-a", signerA)
                .sign(fingerprint);
        AuthoritySignature second = signer("inventory-a", "key-b", signerB)
                .sign(fingerprint);
        assertThatThrownBy(() ->
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .SCHEMA_VERSION,
                        material, fingerprint, List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attestation");
        assertThatThrownBy(() ->
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .SCHEMA_VERSION,
                        material, fingerprint, java.util.Arrays.asList(first, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attestation");
    }

    @Test
    void rejectsMissingOrDescriptorDriftedLocalRuntimeLane() {
        var signed = attestation(material(laneA.descriptor()),
                signer("inventory-a", "key-a", signerA));
        List<AuthorityKey> keys = List.of(key("inventory-a", "key-a", signerA));

        assertThatThrownBy(() -> authority(signed, 1, keys, ignored -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime catalog");
        Lane drifted = lane("tenant", "roots-a", 'c');
        assertThatThrownBy(() -> authority(signed, 1, keys, catalog(drifted)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime catalog");
    }

    @Test
    void hardExpiryClosesObservationAndSnapshotWithoutRestart() {
        MutableClock clock = new MutableClock(NOW);
        var signed = attestation(material(laneA.descriptor()),
                signer("inventory-a", "key-a", signerA));
        var authority = new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, clock, "fleet-inventory.example", Set.of(POLICY), 1,
                List.of(key("inventory-a", "key-a", signerA)), signed,
                binding(), catalog(laneA));

        assertThat(authority.snapshot().generation()).isEqualTo(17L);
        clock.advance(Duration.ofHours(2));

        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("EXPIRED");
            assertThat(observed.generation()).isEqualTo(17L);
        });
        assertThatThrownBy(authority::snapshot).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void rejectsExpiredFutureAndOverlongAttestationsAtConstruction() {
        List<AuthorityKey> keys = List.of(key("inventory-a", "key-a", signerA));

        Material expired = material(List.of(laneA.descriptor()), NOW.minusSeconds(7_200),
                NOW.minusSeconds(7_200), NOW.minusSeconds(1));
        assertThatThrownBy(() -> authority(attestation(expired,
                        signer("inventory-a", "key-a", signerA)),
                1, keys, catalog(laneA))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");

        Material future = material(List.of(laneA.descriptor()), NOW,
                NOW.plusSeconds(60), NOW.plusSeconds(3_600));
        assertThatThrownBy(() -> authority(attestation(future,
                        signer("inventory-a", "key-a", signerA)),
                1, keys, catalog(laneA))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");

        Material overlong = material(List.of(laneA.descriptor()), NOW.minusSeconds(60),
                NOW.minusSeconds(60), NOW.plus(Duration.ofDays(31)));
        assertThatThrownBy(() -> authority(attestation(overlong,
                        signer("inventory-a", "key-a", signerA)),
                1, keys, catalog(laneA))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("freshness");
    }

    @Test
    void strictJsonAcceptsPublicMaterialAndRejectsPrivateUnknownDuplicateAndTrailingData()
            throws Exception {
        var signed = attestation(material(laneA.descriptor()),
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
        String signedJson = objectMapper.writeValueAsString(signed);

        var parsed = ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .fromJson(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                        "fleet-inventory.example", POLICY, 1, keysJson, signedJson,
                        binding(), catalog(laneA));
        assertThat(parsed.snapshot().lanes()).containsExactly(laneA);

        String privateKey = keysJson.replace("\"revoked\":false",
                "\"revoked\":false,\"privateKey\":\"forbidden\"");
        assertInvalidJson(privateKey, signedJson);
        assertInvalidJson(keysJson, signedJson.replaceFirst("\\{",
                "{\"credential\":\"forbidden\","));
        assertInvalidJson(keysJson, signedJson.replaceFirst("\"schemaVersion\":",
                "\"schemaVersion\":\"duplicate\",\"schemaVersion\":"));
        assertInvalidJson(keysJson, signedJson + " {}");
    }

    @Test
    void pinsCanonicalMaterialFingerprint() {
        assertThat(ProtocolFingerprint.of(objectMapper,
                material(laneA.descriptor(), laneB.descriptor())))
                .isEqualTo(
                        "sha256:573484565f855c406b3e229458498f5201f4ccbc69f7a9498e74f1506ab85a9a");
    }

    private void assertInvalidJson(String keysJson, String signedJson) {
        assertThatThrownBy(() ->
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .fromJson(objectMapper, Clock.fixed(NOW, ZoneOffset.UTC),
                                "fleet-inventory.example", POLICY, 1, keysJson, signedJson,
                                binding(), catalog(laneA)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust configuration");
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            authority(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation,
            int threshold,
            List<AuthorityKey> keys,
            LaneResolver resolver) {
        return authority(attestation, threshold, keys, resolver, binding());
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            authority(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation,
            int threshold,
            List<AuthorityKey> keys,
            LaneResolver resolver,
            VerifiedBinding binding) {
        return new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "fleet-inventory.example",
                Set.of(POLICY), threshold, keys, attestation, binding, resolver);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation attestation(
            Material material,
            Signer... signers) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<AuthoritySignature> signatures = java.util.Arrays.stream(signers)
                .map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(AuthoritySignature::authorityId)
                        .thenComparing(AuthoritySignature::keyId))
                .toList();
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private Material material(LaneDescriptor... descriptors) {
        return material(List.of(descriptors));
    }

    private Material material(List<LaneDescriptor> descriptors) {
        return material(descriptors, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3_600));
    }

    private Material material(
            List<LaneDescriptor> descriptors,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        return new Material(Material.SCHEMA_VERSION, "fleet-inventory.example",
                "inventory-2026-07-21-17", 17L, "recovery-prod",
                "bootstrap-recovery", ARTIFACT, 4, descriptors, POLICY,
                issuedAt, notBefore, expiresAt);
    }

    private static VerifiedBinding binding() {
        return new VerifiedBinding("recovery-prod", "bootstrap-recovery", ARTIFACT, 4);
    }

    private static LaneResolver catalog(Lane... lanes) {
        Map<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey, Lane> indexed =
                java.util.Arrays.stream(lanes).collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Lane::key, value -> value));
        return indexed::get;
    }

    private static AuthorityKey key(String authorityId, String keyId, KeyPair pair) {
        return new AuthorityKey(authorityId, keyId, pair.getPublic(),
                Instant.MIN, Instant.MAX, true, false);
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair, NOW.minusSeconds(30));
    }

    private record Signer(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            Instant signedAt) {

        private AuthoritySignature sign(String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new AuthoritySignature(authorityId, keyId, "Ed25519", signedAt,
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
