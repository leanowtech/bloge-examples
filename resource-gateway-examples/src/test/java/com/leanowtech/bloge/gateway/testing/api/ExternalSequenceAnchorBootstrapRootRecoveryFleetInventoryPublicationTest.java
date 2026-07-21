package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.AuthoritySignature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessMaterial;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T01:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void activePublicationAndIndependentWitnessAreCanonicallyCrossLinked() {
        var publication = publication(1, State.ACTIVE, null, null);

        assertThat(publication.fingerprintVerified(MAPPER)).isTrue();
        assertThat(publication.witness().fingerprintVerified(MAPPER)).isTrue();
        assertThat(publication.material().deploymentScopeId()).isEqualTo("tenant-a/staging");
        assertThat(publication.material().fleetId()).isEqualTo("recovery-fleet-a");
        assertThat(publication.material().reasonCode()).isEmpty();
        assertThat(publication.materialFingerprint()).matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void revokedPublicationRequiresStableReasonAndSuccessorPredecessors() {
        var first = publication(1, State.ACTIVE, null, null);
        var revoked = publication(2, State.REVOKED, first, first);

        assertThat(revoked.material().reasonCode()).isEqualTo("OPERATOR_WITHDRAWAL");
        assertThat(revoked.material().previousPublicationFingerprint())
                .isEqualTo(first.materialFingerprint());
        assertThat(revoked.witness().material().previousWitnessFingerprint())
                .isEqualTo(first.witness().materialFingerprint());

        assertThatThrownBy(() -> material(2, State.REVOKED,
                first.materialFingerprint(), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material(2, State.ACTIVE,
                first.materialFingerprint(), "NOT_ALLOWED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationRejectsScopeFleetSequenceAndFingerprintCrossLinks() {
        var valid = publication(1, State.ACTIVE, null, null);
        var wrongScopeWitness = witness(valid.material(), "tenant-b/staging",
                "recovery-fleet-a", "", valid.materialFingerprint());

        assertThatThrownBy(() -> new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                valid.inventory(), valid.material(), valid.materialFingerprint(),
                valid.signatures(), wrongScopeWitness))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                valid.inventory(), valid.material(), fingerprint('f'),
                valid.signatures(), valid.witness()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signaturesMustBeCanonicalDistinctAndBounded() {
        var valid = publication(1, State.ACTIVE, null, null);
        AuthoritySignature a = signature("authority-a", "key-a");
        AuthoritySignature b = signature("authority-b", "key-b");

        assertThatThrownBy(() -> envelope(valid, List.of(b, a)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> envelope(valid, List.of(a,
                signature("authority-a", "key-c"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> envelope(valid, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalFingerprintDetectsMaterialAndWitnessTampering() {
        var valid = publication(1, State.ACTIVE, null, null);
        var tamperedMaterial = material(1, State.ACTIVE, "", "");
        tamperedMaterial = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                .Material(tamperedMaterial.schemaVersion(), tamperedMaterial.trustDomain(),
                "publication-tampered", tamperedMaterial.deploymentScopeId(),
                tamperedMaterial.fleetId(), tamperedMaterial.sequence(),
                tamperedMaterial.inventoryMaterialFingerprint(), tamperedMaterial.state(),
                tamperedMaterial.policyFingerprint(),
                tamperedMaterial.previousPublicationFingerprint(),
                tamperedMaterial.issuedAt(), tamperedMaterial.notBefore(),
                tamperedMaterial.expiresAt(), tamperedMaterial.reasonCode());
        var tampered = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                valid.inventory(), tamperedMaterial, valid.materialFingerprint(),
                valid.signatures(), valid.witness());

        assertThat(tampered.fingerprintVerified(MAPPER)).isFalse();
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            publication(
            long sequence,
            State state,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication predecessor,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                    witnessPredecessor) {
        var material = material(sequence, state,
                predecessor == null ? "" : predecessor.materialFingerprint(),
                state == State.REVOKED ? "OPERATOR_WITHDRAWAL" : "");
        String materialFingerprint = ProtocolFingerprint.of(MAPPER, material);
        var witness = witness(material, material.deploymentScopeId(), material.fleetId(),
                witnessPredecessor == null ? ""
                        : witnessPredecessor.witness().materialFingerprint(),
                materialFingerprint);
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                inventory(), material, materialFingerprint,
                List.of(signature("authority-a", "key-a")), witness);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            .Material material(
            long sequence,
            State state,
            String previous,
            String reason) {
        var inventory = inventory();
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material
                        .SCHEMA_VERSION,
                "recovery-publication.example", "publication-" + sequence,
                "tenant-a/staging", "recovery-fleet-a", sequence,
                inventory.materialFingerprint(), state, fingerprint('b'), previous,
                NOW.minusSeconds(60), NOW.minusSeconds(30), NOW.plusSeconds(3_600), reason);
    }

    private static WitnessCheckpoint witness(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material
                    publication,
            String deploymentScopeId,
            String fleetId,
            String previous,
            String publicationFingerprint) {
        var material = new WitnessMaterial(WitnessMaterial.SCHEMA_VERSION,
                "recovery-witness.example", "checkpoint-" + publication.sequence(),
                deploymentScopeId, fleetId, publication.sequence(), publicationFingerprint,
                previous, NOW.minusSeconds(50), NOW.minusSeconds(20),
                NOW.plusSeconds(3_900));
        return new WitnessCheckpoint(WitnessCheckpoint.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(MAPPER, material),
                List.of(signature("witness-a", "witness-key-a")));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            inventory() {
        var material = new Material(Material.SCHEMA_VERSION,
                "recovery-inventory.example", "inventory-1", 7L,
                "tenant-a/staging", "recovery-fleet-a", fingerprint('a'), 8,
                List.of(), fingerprint('b'), NOW.minusSeconds(120),
                NOW.minusSeconds(90), NOW.plusSeconds(7_200));
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                material, ProtocolFingerprint.of(MAPPER, material),
                List.of(signature("authority-a", "key-a")));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            envelope(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication template,
            List<AuthoritySignature> signatures) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                template.schemaVersion(), template.inventory(), template.material(),
                template.materialFingerprint(), signatures, template.witness());
    }

    private static AuthoritySignature signature(String authorityId, String keyId) {
        return new AuthoritySignature(authorityId, keyId, "Ed25519", NOW.minusSeconds(30),
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
