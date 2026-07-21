package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String DEPLOYMENT_ROOT_DOMAIN = "recovery-deployment-root.example";
    private static final String WITNESS_ROOT_DOMAIN = "recovery-witness-root.example";
    private static final String DEPLOYMENT_DOMAIN = "recovery-deployment.example";
    private static final String WITNESS_DOMAIN = "recovery-witness.example";

    private ObjectMapper objectMapper;
    private Clock clock;
    private KeyPair deploymentRootA;
    private KeyPair deploymentRootB;
    private KeyPair witnessRootA;
    private KeyPair witnessRootB;
    private KeyPair deploymentLeafA;
    private KeyPair deploymentLeafB;
    private KeyPair witnessLeafA;
    private KeyPair witnessLeafB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        deploymentRootA = keyPair();
        deploymentRootB = keyPair();
        witnessRootA = keyPair();
        witnessRootB = keyPair();
        deploymentLeafA = keyPair();
        deploymentLeafB = keyPair();
        witnessLeafA = keyPair();
        witnessLeafB = keyPair();
    }

    @Test
    void verifiesIndependentDualQuorumsAndPublishesOnlyAggregateStatus() {
        var floor = new InMemoryFloor();
        var authority = authority(publication(1, "", false, 2, 2), floor);

        assertThat(authority.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.status()).isEqualTo("VERIFIED");
            assertThat(snapshot.sequence()).isOne();
            assertThat(snapshot.deploymentSignatureThreshold()).isEqualTo(2);
            assertThat(snapshot.witnessSignatureThreshold()).isEqualTo(2);
            assertThat(snapshot.activeDeploymentAuthorityCount()).isEqualTo(2);
            assertThat(snapshot.activeWitnessAuthorityCount()).isEqualTo(2);
            assertThat(snapshot.durableFloor()).isTrue();
            assertThat(snapshot.toString()).doesNotContain(
                    "deployment-leaf", "witness-leaf", "publicKey", "sha256:");
        });
        assertThat(authority.verifiedKeySet()).satisfies(keys -> {
            assertThat(keys.deploymentTrustDomain()).isEqualTo(DEPLOYMENT_DOMAIN);
            assertThat(keys.witnessTrustDomain()).isEqualTo(WITNESS_DOMAIN);
            assertThat(keys.deploymentKeys()).hasSize(2);
            assertThat(keys.witnessKeys()).hasSize(2);
            assertThat(keys.generationFingerprint()).startsWith("sha256:");
            assertThatThrownBy(keys.deploymentKeys()::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        });
        assertThat(floor.current.sequence()).isOne();
    }

    @Test
    void signedEmergencyRevocationAdvancesFloorButClosesRuntimeThreshold() {
        var floor = new InMemoryFloor();
        var authority = authority(publication(1, "", true, 2, 2), floor);

        assertThat(authority.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.status()).isEqualTo("WITNESS_THRESHOLD_UNAVAILABLE");
            assertThat(snapshot.activeWitnessAuthorityCount()).isZero();
        });
        assertThatThrownBy(authority::verifiedKeySet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
        assertThat(floor.current.sequence()).isOne();
    }

    @Test
    void thresholdsRotateOnlyInsideTheSignedAtomicGeneration() {
        var authority = authority(publication(1, "", false, 1, 1), new InMemoryFloor());

        assertThat(authority.snapshot())
                .extracting(
                        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .Snapshot::deploymentSignatureThreshold,
                        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .Snapshot::witnessSignatureThreshold)
                .containsExactly(1, 1);
        assertThatThrownBy(() -> new
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .Snapshot(
                        ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .Snapshot.SCHEMA_VERSION,
                        true, "WITNESS_THRESHOLD_UNAVAILABLE", 1,
                        NOW.plusSeconds(3600), 1, 1, 1, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongRootSignatureOverlappingRootsAndBindingDriftFailClosed() {
        var valid = publication(1, "", false, 2, 2);
        var wrongWitnessSignatures = List.of(
                sign("witness-root-a", "witness-root-key-a", deploymentRootA,
                        valid.materialFingerprint()),
                sign("witness-root-b", "witness-root-key-b", witnessRootB,
                        valid.materialFingerprint()));
        var wrongSignature =
                new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
                        valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                        valid.deploymentRootSignatures(), wrongWitnessSignatures);
        assertThatThrownBy(() -> authority(wrongSignature, new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification failed");

        List<AuthorityKey> overlapping = List.of(
                rootKey("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                rootKey("witness-root-b", "witness-root-key-b", witnessRootB));
        assertThatThrownBy(() ->
                new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                        objectMapper, clock, binding(), Set.of(POLICY), 2,
                        deploymentRoots(), 2, overlapping, new InMemoryFloor(), valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");

        var wrongBinding =
                new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .ExpectedBinding("other-scope", "recovery-fleet",
                        "recovery-inventory-dual-roots",
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                .SCHEMA_VERSION,
                        DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN);
        assertThatThrownBy(() ->
                new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                        objectMapper, clock, wrongBinding, Set.of(POLICY), 2,
                        deploymentRoots(), 2, witnessRoots(), new InMemoryFloor(), valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void materialRejectsDomainOverlapBadPredecessorAndNonCanonicalKeys() {
        var valid = publication(1, "", false, 2, 2);
        var material = valid.material();
        assertThatThrownBy(() -> new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material(
                        material.schemaVersion(), material.trustRootSetId(), 2, "",
                        material.deploymentScopeId(), material.fleetId(),
                        material.protocolVersion(), material.deploymentRootTrustDomain(),
                        material.witnessRootTrustDomain(),
                        material.deploymentRootTrustDomain(), material.witnessTrustDomain(),
                        material.deploymentSignatureThreshold(),
                        material.witnessSignatureThreshold(), material.deploymentKeys(),
                        material.witnessKeys(), material.policyFingerprint(),
                        material.issuedAt(), material.notBefore(), material.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");

        List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .AuthorityKeyMaterial> reversed = new ArrayList<>(
                material.deploymentKeys());
        java.util.Collections.reverse(reversed);
        assertThatThrownBy(() -> material(1, "", false, 2, 2, reversed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key set");
    }

    @Test
    void strictJsonAndRotationPolicyAreEnforced() throws Exception {
        var valid = publication(1, "", false, 2, 2);
        String json = objectMapper.writeValueAsString(valid);
        String unknown = json.replaceFirst("\\{", "{\"unknown\":true,");
        assertThatThrownBy(() ->
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .fromJson(objectMapper, clock, binding(), Set.of(POLICY), 2,
                                deploymentRoots(), 2, witnessRoots(), new InMemoryFloor(),
                                unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration is invalid");
        assertThatThrownBy(() ->
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .fromJson(objectMapper, clock, binding(), Set.of(POLICY), 2,
                                deploymentRoots(), 2, witnessRoots(), new InMemoryFloor(),
                                json + "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authority(valid, new NonDurableFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable floor");
        assertThatThrownBy(() ->
                new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                        objectMapper, clock, binding(), Set.of("sha256:" + "b".repeat(64)),
                        2, deploymentRoots(), 2, witnessRoots(), new InMemoryFloor(), valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");
    }

    @Test
    void durableFloorSurvivesAuthorityReconstructionAndRejectsRollback() {
        var floor = new InMemoryFloor();
        var first = publication(1, "", false, 2, 2);
        authority(first, floor);
        var second = publication(2, first.materialFingerprint(), false, 2, 2);
        assertThat(authority(second, floor).snapshot().sequence()).isEqualTo(2);

        assertThatThrownBy(() -> authority(first, floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback");
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            authority(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    publication,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor) {
        return new
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                objectMapper, clock, binding(), Set.of(POLICY), 2, deploymentRoots(),
                2, witnessRoots(), floor, publication);
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            .ExpectedBinding binding() {
        return new
                ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .ExpectedBinding("tenant-a/staging", "recovery-fleet",
                "recovery-inventory-dual-roots",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            publication(
            long sequence,
            String previous,
            boolean revokeWitness,
            int deploymentThreshold,
            int witnessThreshold) {
        var material = material(sequence, previous, revokeWitness, deploymentThreshold,
                witnessThreshold, deploymentLeafMaterials());
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION,
                material, fingerprint,
                signatures(fingerprint,
                        signer("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                        signer("deployment-root-b", "deployment-root-key-b", deploymentRootB)),
                signatures(fingerprint,
                        signer("witness-root-a", "witness-root-key-a", witnessRootA),
                        signer("witness-root-b", "witness-root-key-b", witnessRootB)));
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.Material
            material(
            long sequence,
            String previous,
            boolean revokeWitness,
            int deploymentThreshold,
            int witnessThreshold,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    .AuthorityKeyMaterial> deploymentKeys) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material.SCHEMA_VERSION,
                "recovery-inventory-dual-roots", sequence, previous,
                "tenant-a/staging", "recovery-fleet",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN,
                DEPLOYMENT_DOMAIN, WITNESS_DOMAIN,
                deploymentThreshold, witnessThreshold, deploymentKeys,
                witnessLeafMaterials(revokeWitness), POLICY,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            .AuthorityKeyMaterial> deploymentLeafMaterials() {
        return List.of(keyMaterial("deployment-leaf-a", "deployment-key-a",
                        deploymentLeafA, false),
                keyMaterial("deployment-leaf-b", "deployment-key-b",
                        deploymentLeafB, false));
    }

    private List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            .AuthorityKeyMaterial> witnessLeafMaterials(boolean revoked) {
        return List.of(keyMaterial("witness-leaf-a", "witness-key-a", witnessLeafA, revoked),
                keyMaterial("witness-leaf-b", "witness-key-b", witnessLeafB, revoked));
    }

    private static
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    .AuthorityKeyMaterial keyMaterial(
            String authorityId,
            String keyId,
            KeyPair pair,
            boolean revoked) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .AuthorityKeyMaterial(
                authorityId, keyId,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                NOW.minusSeconds(3600), NOW.plusSeconds(7200), true, revoked);
    }

    private List<AuthorityKey> deploymentRoots() {
        return List.of(rootKey("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                rootKey("deployment-root-b", "deployment-root-key-b", deploymentRootB));
    }

    private List<AuthorityKey> witnessRoots() {
        return List.of(rootKey("witness-root-a", "witness-root-key-a", witnessRootA),
                rootKey("witness-root-b", "witness-root-key-b", witnessRootB));
    }

    private static AuthorityKey rootKey(String authorityId, String keyId, KeyPair pair) {
        return new AuthorityKey(authorityId, keyId, pair.getPublic(),
                NOW.minusSeconds(3600), NOW.plusSeconds(7200), true, false);
    }

    private static List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature> signatures(String fingerprint, Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .AuthoritySignature::authorityId))
                .toList();
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature sign(
            String authorityId,
            String keyId,
            KeyPair pair,
            String fingerprint) {
        return signer(authorityId, keyId, pair).sign(fingerprint);
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {
        private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature sign(String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .AuthoritySignature(authorityId, keyId, "Ed25519",
                        NOW.minusSeconds(20),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class InMemoryFloor implements
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {
        private Generation current;

        @Override
        public synchronized void accept(Generation generation) {
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException("floor must begin at sequence one");
                }
                current = generation;
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException("floor rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!generation.materialFingerprint().equals(current.materialFingerprint())) {
                    throw new IllegalArgumentException("floor fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1
                    || !generation.previousMaterialFingerprint().equals(
                    current.materialFingerprint())) {
                throw new IllegalArgumentException("floor discontinuity");
            }
            current = generation;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class NonDurableFloor implements
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {
        @Override
        public void accept(Generation generation) {
            throw new AssertionError("non-durable floor must fail before mutation");
        }

        @Override
        public boolean durable() {
            return false;
        }
    }
}
