package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/** Shared deterministic, payload-free Domain Fidelity fixtures. */
final class DomainFidelityTestFixtures {
    static final Instant NOW =
            Instant.parse("2026-07-26T04:00:00Z");
    static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private DomainFidelityTestFixtures() {
    }

    static CapabilitySnapshot.Scope scope(String organizationId) {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                organizationId,
                "refunds",
                "staging",
                "sg");
    }

    static IntegrationRequestContext ownerIdentity(
            String organizationId) {
        return new IntegrationRequestContext(
                "tenant-a",
                organizationId,
                "refunds",
                "staging",
                "sg",
                "HUMAN",
                "owner-a",
                "",
                DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                "correlation-owner",
                Set.of(DomainFidelityPolicy.DEFAULT_OWNER_GROUP),
                "CONFIDENTIAL",
                "");
    }

    static IntegrationRequestContext projectorIdentity(
            String organizationId) {
        return new IntegrationRequestContext(
                "tenant-a",
                organizationId,
                "refunds",
                "staging",
                "sg",
                "SERVICE",
                "scenario-adapter",
                "",
                DomainFidelityPolicy.PROJECTION_PURPOSE,
                "correlation-projector",
                Set.of(
                        DomainFidelityPolicy
                                .DEFAULT_PROJECTOR_GROUP),
                "CONFIDENTIAL",
                "");
    }

    static IntegrationRequestContext readerIdentity(
            String organizationId) {
        return new IntegrationRequestContext(
                "tenant-a",
                organizationId,
                "refunds",
                "staging",
                "sg",
                "SERVICE",
                "aneke-governance",
                "",
                "GOVERNANCE_EVIDENCE_INGESTION",
                "correlation-reader",
                Set.of(),
                "CONFIDENTIAL",
                "");
    }

    static DomainFidelityPolicy policy() {
        return new DomainFidelityPolicy(
                1,
                Set.of(DomainFidelityPolicy.DEFAULT_OWNER_GROUP),
                Set.of(
                        DomainFidelityPolicy.DEFAULT_PROJECTOR_GROUP),
                Duration.ofHours(1),
                Duration.ofDays(730),
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                new DomainFidelityProfile.ProjectionPolicy(
                        1,
                        Duration.ofDays(30),
                        true,
                        DomainFidelityProfile.CONFIDENCE_METHOD));
    }

    static DomainFidelityInventoryRegistrationRequest
    registration(
            long revision,
            String predecessor,
            List<DomainFidelityInventory.CoverageUnit> units) {
        return new DomainFidelityInventoryRegistrationRequest(
                "",
                "refund-support",
                revision,
                predecessor,
                "refund-domain",
                ref(
                        "DOMAIN_FIDELITY_TAXONOMY",
                        "refund-taxonomy",
                        'f'),
                units,
                NOW,
                NOW.plus(Duration.ofDays(365)));
    }

    static DomainFidelityInventory inventory(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            long revision,
            List<DomainFidelityInventory.CoverageUnit> units) {
        Instant approvedAt =
                NOW.minus(Duration.ofDays(2));
        Instant expiresAt =
                NOW.plus(Duration.ofDays(365));
        return new DomainFidelityInventory(
                "",
                "refund-support",
                revision,
                "",
                scope,
                "refund-domain",
                ref(
                        "DOMAIN_FIDELITY_TAXONOMY",
                        "refund-taxonomy",
                        'f'),
                units,
                new ArtifactProvenance(
                        "",
                        ArtifactProvenance.SourceType.OWNER,
                        List.of(),
                        scope.tenantId(),
                        DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        "owner-a",
                        approvedAt,
                        expiresAt,
                        ""),
                CapabilitySnapshot.Lifecycle.ACTIVE,
                approvedAt.plusSeconds(1),
                expiresAt)
                .seal(mapper);
    }

    static List<DomainFidelityInventory.CoverageUnit>
    units() {
        return List.of(
                unit(
                        "refund-boundary",
                        'a',
                        ScenarioCase.CaseType.BOUNDARY),
                unit(
                        "refund-golden",
                        'b',
                        ScenarioCase.CaseType.GOLDEN));
    }

    static DomainFidelityInventory.CoverageUnit unit(
            String unitId,
            char material,
            ScenarioCase.CaseType type) {
        return new DomainFidelityInventory.CoverageUnit(
                unitId,
                ref("SCENARIO_CASE", unitId, material),
                ref("CAPABILITY", "refund", 'c'),
                type,
                List.of(
                        DomainFidelityProfile.Dimension.BEHAVIOR,
                        DomainFidelityProfile.Dimension.CONTRACT));
    }

    static List<DomainFidelityProfileProjector.Measurement>
    passingMeasurements(
            DomainFidelityInventory inventory) {
        return inventory.units().stream()
                .map(unit ->
                        measurement(
                                unit,
                                DomainFidelityProfile
                                        .MeasurementOutcome.PASS,
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .ASSERTIONS_PASSED))
                .toList();
    }

    static List<DomainFidelityProfileProjector.Measurement>
    failingMeasurements(
            DomainFidelityInventory inventory) {
        return inventory.units().stream()
                .map(unit ->
                        measurement(
                                unit,
                                DomainFidelityProfile
                                        .MeasurementOutcome.FAIL,
                                DomainFidelityProfile
                                        .MeasurementReason
                                        .ASSERTION_FAILED))
                .toList();
    }

    static DomainFidelityProfile signedProfile(
            ObjectMapper mapper,
            DomainFidelityProfileIntegrity integrity,
            DomainFidelityInventory inventory,
            List<DomainFidelityProfileProjector.Measurement>
                    measurements) {
        DomainFidelityProfile profile =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        measurements,
                        policy().projectionPolicy(),
                        NOW);
        return integrity.sign(profile);
    }

    static DomainFidelityProfileIntegrity integrity(
            ObjectMapper mapper) {
        return new DomainFidelityProfileIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(CLOCK),
                CLOCK);
    }

    static MirrorArtifactRef ref(
            String kind, String id, char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                "sha256:" + String.valueOf(safe).repeat(64));
    }

    private static DomainFidelityProfileProjector.Measurement
    measurement(
            DomainFidelityInventory.CoverageUnit unit,
            DomainFidelityProfile.MeasurementOutcome outcome,
            DomainFidelityProfile.MeasurementReason reason) {
        return new DomainFidelityProfileProjector.Measurement(
                unit.unitId(),
                unit.scenarioCaseRef(),
                ref(
                        "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                        "workbook-" + unit.unitId(),
                        unit.unitId().charAt(
                                unit.unitId().length() - 1)),
                NOW.minus(Duration.ofHours(1)),
                DomainFidelityProfile.SourceMode.RECORDED,
                true,
                true,
                unit.requiredDimensions().stream()
                        .map(dimension ->
                                new DomainFidelityProfile
                                        .DimensionResult(
                                        dimension,
                                        outcome,
                                        reason))
                        .toList());
    }
}
