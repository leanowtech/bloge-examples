package com.leanowtech.bloge.gateway.businessmirror.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfileIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfileProjector;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Deterministic fixtures for package evidence-index tests. */
public final class PackageEvidenceFixtures {
    public static final Instant NOW = Instant.parse("2026-08-14T17:00:00Z");
    public static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "mobility", "customer-service", "staging", "sg");

    private PackageEvidenceFixtures() {
    }

    public static PackageCompilationReceipt receipt(ObjectMapper mapper) {
        String sourceFingerprint = fingerprint('a');
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", 'a');
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "query-trip", 'b');
        BusinessAssetRef solution = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "cancellation-solution", 'c');
        BusinessAssetRef workflow = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "cancellation-workflow", 'd');
        BusinessAssetRef channel = asset(BusinessAssetRef.Layer.L3_APPLICATION,
                BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-chat", 'e');
        List<BusinessAssetRef> assets = List.of(resource, operator, solution, workflow, channel);
        List<BusinessAssetLink> links = List.of(
                link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS),
                link(operator, solution, BusinessAssetLink.Relation.USES),
                link(solution, workflow, BusinessAssetLink.Relation.DELIVERED_BY),
                link(workflow, channel, BusinessAssetLink.Relation.EXPOSED_ON));
        BusinessAssetLinkClosure closure = new BusinessAssetLinkClosure("",
                "cancellation-links", 7, "", SCOPE, "cancellation-package",
                assets, links, NOW).seal(mapper);
        PackageReadinessReport readiness = new PackageReadinessReport("",
                "cancellation-readiness", 7, "", SCOPE, "cancellation-package",
                3, sourceFingerprint, PackageReadinessReport.Status.READY,
                List.of(), NOW).seal(mapper);
        DomainCapabilityPackageDraft.BusinessDefinition definition =
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", ref("PROBLEM_TAXONOMY", "trip-problems", 'a'),
                        "TRIP.CANCELLATION", "Resolve cancellation disputes",
                        "Correct explanation and remedy",
                        DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of("risk-owner"));
        List<MirrorArtifactRef> manifest = List.of(
                ref("CAPABILITY", "query-trip", 'b'),
                ref("CONTRACT", "cancellation-contract", 'c'),
                ref("DOMAIN_FIDELITY_INVENTORY", "cancellation-fidelity", 'd'),
                ref("GRAPH_SNAPSHOT", "cancellation-graph", 'e'),
                ref("OUTCOME_DEFINITION", "resolved-without-repeat", 'f'),
                ref("SCENARIO_INVENTORY", "cancellation-scenarios", 'a'),
                ref("SCENARIO_PACK", "cancellation-regression", 'b'),
                ref("SOLUTION", "cancellation-solution", 'c'),
                ref("WORKFLOW", "cancellation-workflow", 'd'),
                ref("CHANNEL_APPLICATION", "support-chat", 'e'));
        DomainCapabilityPackageSnapshot snapshot = new DomainCapabilityPackageSnapshot("",
                "cancellation-package", 7, "", SCOPE, 3, sourceFingerprint,
                definition, ref("CONTRACT", "cancellation-contract", 'c'),
                ref("CAPABILITY_CLOSURE", "cancellation-capabilities", 'b'),
                List.of(ref("MIRROR_PLAN", "cancellation-plan", 'c')),
                closure.artifactRef(), readiness.artifactRef(), manifest,
                List.of(ref("SCENARIO_RUN_EVIDENCE", "cancellation-golden-run", 'e')),
                "business-mirror-package-compiler-v1",
                ref("PACKAGE_COMPILATION_POLICY", "default", 'f'), provenance(), NOW)
                .seal(mapper);
        return new PackageCompilationReceipt("", fingerprint('f'), "cancellation-package",
                3, sourceFingerprint, 7, readiness, closure, snapshot,
                "authority-generation-7", NOW);
    }

    static DomainFidelityInventory inventory(ObjectMapper mapper, char material, Instant expiresAt) {
        List<DomainFidelityProfile.Dimension> dimensions =
                List.of(DomainFidelityProfile.Dimension.values());
        DomainFidelityInventory.CoverageUnit unit = new DomainFidelityInventory.CoverageUnit(
                "cancellation-golden", ref("SCENARIO_CASE", "cancellation-golden", 'b'),
                ref("CAPABILITY", "query-trip", 'b'), ScenarioCase.CaseType.GOLDEN,
                dimensions);
        long revision = material == 'e' ? 2 : 1;
        return new DomainFidelityInventory("", "cancellation-fidelity", revision, "", SCOPE,
                "ride-cancellation", ref("DOMAIN_FIDELITY_TAXONOMY", "service-fidelity", 'c'),
                List.of(unit), provenance(expiresAt), CapabilitySnapshot.Lifecycle.ACTIVE,
                NOW.minus(Duration.ofDays(300)), expiresAt).seal(mapper);
    }

    static PackageCompilationReceipt receiptWithInventory(
            ObjectMapper mapper, DomainFidelityInventory inventory) {
        PackageCompilationReceipt original = receipt(mapper);
        DomainCapabilityPackageSnapshot source = original.snapshot();
        List<MirrorArtifactRef> manifest = source.dependencyManifest().stream()
                .map(value -> DomainFidelityInventory.ARTIFACT_KIND.equals(value.kind())
                        ? inventory.artifactRef() : value)
                .sorted(java.util.Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision))
                .toList();
        DomainCapabilityPackageSnapshot snapshot = new DomainCapabilityPackageSnapshot(
                source.schemaVersion(), source.packageId(), source.revision(), "", source.scope(),
                source.sourceDraftRevision(), source.sourceDraftFingerprint(),
                source.businessDefinition(), source.packageContractRef(),
                source.capabilityClosureRef(), source.mirrorPlanRefs(),
                source.businessAssetLinkClosureRef(), source.readinessReportRef(), manifest,
                source.evidenceRefs(), source.compilerVersion(), source.policyGenerationRef(),
                source.provenance(), source.createdAt()).seal(mapper);
        return new PackageCompilationReceipt(original.schemaVersion(),
                original.requestFingerprint(), original.packageId(),
                original.sourceDraftRevision(), original.sourceDraftFingerprint(),
                original.compilationRevision(), original.readiness(),
                original.businessAssetLinkClosure(), snapshot,
                original.authorityGeneration(), original.completedAt());
    }

    static DomainFidelityProfile profile(
            ObjectMapper mapper,
            DomainFidelityInventory inventory,
            DomainFidelityProfile.MeasurementOutcome outcome,
            Instant measuredAt) {
        DomainFidelityInventory.CoverageUnit unit = inventory.units().getFirst();
        DomainFidelityProfile.MeasurementReason reason = switch (outcome) {
            case PASS -> DomainFidelityProfile.MeasurementReason.ASSERTIONS_PASSED;
            case FAIL -> DomainFidelityProfile.MeasurementReason.ASSERTION_FAILED;
            case ABSTAINED -> DomainFidelityProfile.MeasurementReason.OUTCOME_PENDING;
            case STALE -> DomainFidelityProfile.MeasurementReason.EVIDENCE_STALE;
            case MISSING -> DomainFidelityProfile.MeasurementReason.NO_ELIGIBLE_EVIDENCE;
        };
        DomainFidelityProfileProjector.Measurement measurement =
                new DomainFidelityProfileProjector.Measurement(
                        unit.unitId(), unit.scenarioCaseRef(),
                        ref("AUTHORITATIVE_OUTCOME_OBSERVATION", "cancellation-outcome", 'f'),
                        measuredAt.minus(Duration.ofHours(1)),
                        DomainFidelityProfile.SourceMode.AUTHORITATIVE, true, true,
                        unit.requiredDimensions().stream()
                                .map(dimension -> new DomainFidelityProfile.DimensionResult(
                                        dimension, outcome, reason)).toList());
        DomainFidelityProfile unsigned = DomainFidelityProfileProjector.project(mapper, inventory,
                List.of(measurement), new DomainFidelityProfile.ProjectionPolicy(
                        1, Duration.ofDays(30), true,
                        DomainFidelityProfile.CONFIDENCE_METHOD), measuredAt);
        Clock clock = Clock.fixed(measuredAt, ZoneOffset.UTC);
        return new DomainFidelityProfileIntegrity(
                mapper, InMemoryVisualEvidenceSigner.usingClock(clock)).sign(unsigned);
    }

    private static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char material) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(material),
                "customer-registry", SCOPE);
    }

    private static BusinessAssetLink link(
            BusinessAssetRef source,
            BusinessAssetRef target,
            BusinessAssetLink.Relation relation) {
        return new BusinessAssetLink("", source, target, relation, "",
                BusinessAssetLink.Risk.HIGH, "cancellation-owner", provenance());
    }

    private static ArtifactProvenance provenance() {
        return provenance(NOW.plus(Duration.ofDays(180)));
    }

    private static ArtifactProvenance provenance(Instant expiresAt) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "BUSINESS_MIRROR_AUTHORING", null, null, null, null,
                List.of(), "cancellation-owner", NOW.minus(Duration.ofDays(365)), expiresAt, "");
    }

    private static MirrorArtifactRef ref(String kind, String id, char material) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(material));
    }

    private static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }
}
