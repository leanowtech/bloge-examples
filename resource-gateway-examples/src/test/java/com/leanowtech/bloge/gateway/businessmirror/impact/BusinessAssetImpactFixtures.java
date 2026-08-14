package com.leanowtech.bloge.gateway.businessmirror.impact;

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
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.List;

final class BusinessAssetImpactFixtures {
    static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

    private BusinessAssetImpactFixtures() {
    }

    static PackageCompilationReceipt receipt(ObjectMapper mapper, long revision, char material) {
        String sourceFingerprint = fingerprint(material);
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", material);
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "trip-query", (char) (material + 1));
        BusinessAssetRef solution = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "refund-solution", (char) (material + 2));
        BusinessAssetRef workflow = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "refund-workflow", (char) (material + 3));
        BusinessAssetRef channel = asset(BusinessAssetRef.Layer.L3_APPLICATION,
                BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-console", (char) (material + 4));
        List<BusinessAssetRef> assets = List.of(resource, operator, solution, workflow, channel);
        List<BusinessAssetLink> links = List.of(
                link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS),
                link(operator, solution, BusinessAssetLink.Relation.USES),
                link(solution, workflow, BusinessAssetLink.Relation.DELIVERED_BY),
                link(workflow, channel, BusinessAssetLink.Relation.EXPOSED_ON));
        BusinessAssetLinkClosure closure = new BusinessAssetLinkClosure("",
                "refund-package-links", revision, "", SCOPE, "refund-package",
                assets, links, NOW.plusSeconds(revision)).seal(mapper);
        PackageReadinessReport readiness = new PackageReadinessReport("",
                "refund-package-readiness", revision, "", SCOPE, "refund-package",
                revision, sourceFingerprint, PackageReadinessReport.Status.READY,
                List.of(), NOW.plusSeconds(revision)).seal(mapper);
        DomainCapabilityPackageDraft.BusinessDefinition definition =
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", ref("PROBLEM_TAXONOMY", "trip-problems", 'a'),
                        "TRIP.REFUND", "Resolve refund requests", "Correct refund decision",
                        DomainCapabilityPackageDraft.RiskClass.HIGH, "refund-owner", List.of());
        DomainCapabilityPackageSnapshot snapshot = new DomainCapabilityPackageSnapshot("",
                "refund-package", revision, "", SCOPE, revision, sourceFingerprint,
                definition, ref("CONTRACT", "refund-contract", 'b'),
                ref("CAPABILITY_CLOSURE", "refund-capabilities", 'c'),
                List.of(ref("MIRROR_PLAN", "refund-plan", 'd')), closure.artifactRef(),
                readiness.artifactRef(), List.of(ref("CAPABILITY", "trip-query", 'e')),
                List.of(), "business-mirror-compiler-v1",
                ref("PACKAGE_COMPILATION_POLICY", "default", 'f'), provenance(),
                NOW.plusSeconds(revision)).seal(mapper);
        return new PackageCompilationReceipt("", fingerprint((char) (material + 5)),
                "refund-package", revision, sourceFingerprint, revision, readiness, closure,
                snapshot, "authority-generation-" + revision, NOW.plusSeconds(revision));
    }

    static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char value) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(value),
                "customer-registry", SCOPE);
    }

    static BusinessAssetLink link(
            BusinessAssetRef source,
            BusinessAssetRef target,
            BusinessAssetLink.Relation relation) {
        return new BusinessAssetLink("", source, target, relation, "",
                BusinessAssetLink.Risk.HIGH, "refund-owner", provenance());
    }

    static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "business-asset-impact-test", null, null, null, null,
                List.of(), "refund-owner", NOW.minusSeconds(3600), NOW.plusSeconds(86_400), "");
    }

    static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    static String fingerprint(char value) {
        char exact = Character.toLowerCase(value);
        if (exact < 'a' || exact > 'f') {
            exact = (char) ('a' + Math.floorMod(exact, 6));
        }
        return "sha256:" + String.valueOf(exact).repeat(64);
    }
}
