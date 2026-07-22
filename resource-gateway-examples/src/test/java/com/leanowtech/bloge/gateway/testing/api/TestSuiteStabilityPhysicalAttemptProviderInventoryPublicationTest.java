package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt.IsolationMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final String POLICY = fingerprint('b');
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void canonicalEnvelopeBindsInventoryLifecycleReplicaSetAndWitness() {
        var publication = publication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                List.of("replica-a", "replica-b"), "");

        assertThat(publication.fingerprintVerified(OBJECT_MAPPER)).isTrue();
        assertThat(publication.witness().fingerprintVerified(OBJECT_MAPPER)).isTrue();
        assertThat(publication.material().expectedReplicaIds())
                .containsExactly("replica-a", "replica-b");
        assertThat(publication.witness().material().publicationMaterialFingerprint())
                .isEqualTo(publication.materialFingerprint());
    }

    @Test
    void replicaSetMustBeCompleteOrderedUniqueAndNonEmpty() {
        for (List<String> invalid : List.of(
                List.<String>of(),
                List.of("replica-b", "replica-a"),
                List.of("replica-a", "replica-a"),
                List.of("bad replica"))) {
            assertThatThrownBy(() -> material(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                    invalid, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("material is invalid");
        }
    }

    @Test
    void activeAndRevokedReasonShapesAreMutuallyExclusive() {
        assertThatThrownBy(() -> material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                List.of("replica-a"), "WITHDRAWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.REVOKED,
                List.of("replica-a"), ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.REVOKED,
                List.of("replica-a"), "DEPLOYMENT_WITHDRAWN").state())
                .isEqualTo(
                        TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State
                                .REVOKED);
    }

    @Test
    void envelopeRejectsInventoryScopeAndWitnessCrossLinks() {
        var valid = publication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                List.of("replica-a"), "");
        var wrongScope = new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material(
                valid.material().schemaVersion(), valid.material().trustDomain(),
                valid.material().publicationId(), valid.material().sequence(), "wrong-scope",
                valid.material().cohortId(), valid.material().inventoryMaterialFingerprint(),
                valid.material().expectedReplicaIds(), valid.material().state(),
                valid.material().policyFingerprint(),
                valid.material().previousPublicationFingerprint(),
                valid.material().issuedAt(), valid.material().notBefore(),
                valid.material().expiresAt(), valid.material().reasonCode());
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                valid.schemaVersion(), valid.inventory(), wrongScope,
                ProtocolFingerprint.of(OBJECT_MAPPER, wrongScope), valid.signatures(),
                valid.witness()))
                .isInstanceOf(IllegalArgumentException.class);

        var wrongWitnessMaterial = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial(
                valid.witness().material().schemaVersion(), "witness.example", "checkpoint-2",
                2, valid.materialFingerprint(), fingerprint('c'), NOW, NOW,
                NOW.plusSeconds(600));
        var wrongWitness = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint(
                valid.witness().schemaVersion(), wrongWitnessMaterial,
                ProtocolFingerprint.of(OBJECT_MAPPER, wrongWitnessMaterial),
                valid.witness().signatures());
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                valid.schemaVersion(), valid.inventory(), valid.material(),
                valid.materialFingerprint(), valid.signatures(), wrongWitness))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signaturesMustBeCanonicalAndAuthorityDistinct() {
        var valid = publication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                List.of("replica-a"), "");
        var duplicateAuthority = signature("authority-a", "key-b");

        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                valid.schemaVersion(), valid.inventory(), valid.material(),
                valid.materialFingerprint(),
                List.of(valid.signatures().getFirst(), duplicateAuthority), valid.witness()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signatures are invalid");
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State state,
            List<String> replicas,
            String reason) {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory();
        var material = material(state, replicas, reason);
        String materialFingerprint = ProtocolFingerprint.of(OBJECT_MAPPER, material);
        var witnessMaterial = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial
                        .SCHEMA_VERSION,
                "witness.example", "checkpoint-1", 1, materialFingerprint, "",
                NOW, NOW, NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(OBJECT_MAPPER, witnessMaterial);
        var witness = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint
                        .SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                List.of(signature("witness-a", "witness-key-a")));
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.SCHEMA_VERSION,
                inventory, material, materialFingerprint,
                List.of(signature("authority-a", "key-a")), witness);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material material(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State state,
            List<String> replicas,
            String reason) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material
                        .SCHEMA_VERSION,
                "provider.inventory.example", "publication-1", 1,
                "physical-attempt-providers", "release-2026-07-22",
                inventory().materialFingerprint(), replicas, state, POLICY, "",
                NOW, NOW, NOW.plusSeconds(600), reason);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventory inventory() {
        var binding = new Binding(Binding.SCHEMA_VERSION, "provider-a", "deployment-1",
                fingerprint('a'), "observation-key-a", List.of(IsolationMode.PROCESS),
                5_000, 86_400_000);
        var material = new TestSuiteStabilityPhysicalAttemptProviderInventory.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Material.SCHEMA_VERSION,
                "provider.inventory.example", "provider-inventory-17", 17,
                "physical-attempt-providers", "release-2026-07-22",
                "bloge.physical-attempt-provider.v1", POLICY, List.of(binding),
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3_600));
        String fingerprint = ProtocolFingerprint.of(OBJECT_MAPPER, material);
        return new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material, fingerprint, List.of(signature("authority-a", "key-a")));
    }

    private static TestSuiteStabilityServingInventory.AuthoritySignature signature(
            String authorityId, String keyId) {
        return new TestSuiteStabilityServingInventory.AuthoritySignature(
                authorityId, keyId, "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
