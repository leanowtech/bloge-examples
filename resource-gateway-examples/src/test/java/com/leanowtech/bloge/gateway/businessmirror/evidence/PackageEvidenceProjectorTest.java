package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageEvidenceProjectorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preservesFiveProofLayersSevenDimensionsAndExactLineageWithoutAnOverallScore()
            throws Exception {
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, inventory);
        DomainFidelityProfile profile = PackageEvidenceFixtures.profile(mapper, inventory,
                DomainFidelityProfile.MeasurementOutcome.PASS,
                PackageEvidenceFixtures.NOW);

        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.of(profile),
                PackageEvidenceFixtures.NOW.plus(Duration.ofHours(2)), mapper);

        assertThat(index.layers()).extracting(PackageEvidenceIndex.EvidenceLayer::layer)
                .containsExactly(PackageEvidenceIndex.EvidenceLayer.Layer.values());
        assertThat(index.layers()).allSatisfy(layer -> assertThat(layer.conclusions()).isNotEmpty());
        assertThat(index.layers().stream().flatMap(layer -> layer.conclusions().stream()))
                .allSatisfy(conclusion -> {
                    assertThat(conclusion.sourceLineage()).contains(conclusion.subject());
                    assertThat(conclusion.sourceLineage()).contains(index.packageSnapshotSource());
                });
        assertThat(index.fidelity().dimensions())
                .extracting(PackageEvidenceIndex.DimensionEvidence::dimension)
                .containsExactly(DomainFidelityProfile.Dimension.values());
        assertThat(index.fidelity().state()).isEqualTo(PackageEvidenceIndex.FidelityState.CURRENT);
        assertThat(index.fidelity().dimensions())
                .allMatch(value -> value.state() == PackageEvidenceIndex.DimensionState.MEASURED);
        assertThat(index.driftSignals()).isEmpty();
        assertThat(mapper.writeValueAsString(index)).doesNotContain("overallScore", "totalScore");
        index.verify(mapper);
    }

    @Test
    void keepsHigherLayerProofIndependentFromPassingLowLevelEvidence() {
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, inventory);

        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.empty(), PackageEvidenceFixtures.NOW, mapper);

        PackageEvidenceIndex.EvidenceLayer l0 = index.layers().getFirst();
        PackageEvidenceIndex.EvidenceLayer calibration = index.layers().getLast();
        assertThat(l0.conclusions()).anyMatch(value ->
                value.proofStrength() == PackageEvidenceIndex.ProofStrength.EXECUTION_EVIDENCE);
        assertThat(calibration.conclusions()).anyMatch(value ->
                value.evidenceKind() == PackageEvidenceIndex.EvidenceKind.FIDELITY_PROFILE
                        && value.state() == PackageEvidenceIndex.ConclusionState.MISSING);
        assertThat(index.fidelity().state()).isEqualTo(PackageEvidenceIndex.FidelityState.MISSING);
        assertThat(index.driftSignals()).extracting(PackageEvidenceIndex.DriftSignal::reason)
                .containsExactlyInAnyOrder(
                        PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_MISSING,
                        PackageEvidenceIndex.DriftReason.OUTCOME_UNCALIBRATED);
    }

    @Test
    void rejectsUnsignedProfileInsteadOfPromotingItToSignedProof() {
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, inventory);
        DomainFidelityProfile signed = PackageEvidenceFixtures.profile(mapper, inventory,
                DomainFidelityProfile.MeasurementOutcome.PASS, PackageEvidenceFixtures.NOW);
        DomainFidelityProfile unsigned = new DomainFidelityProfile(signed.schemaVersion(),
                signed.profileFingerprint(), signed.scope(), signed.domainId(),
                signed.inventoryRef(), signed.taxonomyRef(), signed.policy(), signed.measuredAt(),
                signed.validUntil(), signed.denominator(), signed.unitAssessments(),
                signed.dimensions(), signed.abstentionDebt(), signed.sourceComposition(),
                signed.assessment(), signed.limitations(), null);

        assertThatThrownBy(() -> PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.of(unsigned), PackageEvidenceFixtures.NOW, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed");
    }

    @Test
    void reportsInventoryDriftWithoutUsingTheMismatchedProfileAsPackageProof() {
        DomainFidelityInventory selected = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, selected);
        DomainFidelityInventory newer = PackageEvidenceFixtures.inventory(
                mapper, 'e', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        DomainFidelityProfile newerProfile = PackageEvidenceFixtures.profile(mapper, newer,
                DomainFidelityProfile.MeasurementOutcome.PASS, PackageEvidenceFixtures.NOW);

        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt,
                Optional.of(selected), Optional.of(newerProfile), PackageEvidenceFixtures.NOW, mapper);

        assertThat(index.fidelity().state())
                .isEqualTo(PackageEvidenceIndex.FidelityState.INVENTORY_DRIFT);
        assertThat(index.fidelity().profileSource()).isNull();
        assertThat(index.driftSignals()).extracting(PackageEvidenceIndex.DriftSignal::reason)
                .contains(PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_INVENTORY_DRIFT);
    }

    @Test
    void exposesStaleAndAbstainedEvidenceAsSeparateOwnerDebt() {
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, inventory);
        DomainFidelityProfile abstained = PackageEvidenceFixtures.profile(mapper, inventory,
                DomainFidelityProfile.MeasurementOutcome.ABSTAINED,
                PackageEvidenceFixtures.NOW.minus(Duration.ofDays(45)));

        PackageEvidenceIndex index = PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.of(abstained), PackageEvidenceFixtures.NOW, mapper);

        assertThat(index.fidelity().state()).isEqualTo(PackageEvidenceIndex.FidelityState.STALE);
        assertThat(index.driftSignals()).extracting(PackageEvidenceIndex.DriftSignal::reason)
                .contains(PackageEvidenceIndex.DriftReason.FIDELITY_PROFILE_STALE);
        assertThat(index.driftSignals()).allSatisfy(signal -> {
            assertThat(signal.owner()).isEqualTo("cancellation-owner");
            assertThat(signal.dueAt()).isAfter(signal.detectedAt());
            assertThat(signal.sourceLineage()).contains(index.packageSnapshotSource());
        });
    }

    @Test
    void isDeterministicAndDetectsCanonicalTampering() {
        DomainFidelityInventory inventory = PackageEvidenceFixtures.inventory(
                mapper, 'd', PackageEvidenceFixtures.NOW.plus(Duration.ofDays(90)));
        PackageCompilationReceipt receipt = PackageEvidenceFixtures.receiptWithInventory(
                mapper, inventory);
        PackageEvidenceIndex first = PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.empty(), PackageEvidenceFixtures.NOW, mapper);
        PackageEvidenceIndex second = PackageEvidenceProjector.project(receipt,
                Optional.of(inventory), Optional.empty(), PackageEvidenceFixtures.NOW, mapper);

        assertThat(second).isEqualTo(first);
        PackageEvidenceIndex tampered = new PackageEvidenceIndex(first.schemaVersion(),
                first.indexFingerprint(), first.scope(), first.packageId(),
                first.compilationRevision(), first.packageSnapshotSource(), first.readinessSource(),
                first.businessAssetClosureSource(), first.domainId(), "TRIP.OTHER",
                first.layers(), first.fidelity(), first.driftSignals(), first.projectedAt(),
                first.validUntil());
        assertThatThrownBy(() -> tampered.verify(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }
}
