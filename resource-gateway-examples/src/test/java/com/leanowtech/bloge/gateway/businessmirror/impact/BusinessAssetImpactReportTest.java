package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessAssetImpactReportTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsDuplicatePackagesAndSourceCoordinatesInsteadOfSilentlyDeduplicating() {
        BusinessAssetImpactReport.PackageImpact item = packageImpact();
        BusinessAssetSelector selector = new BusinessAssetSelector(
                BusinessAssetRef.Kind.RESOURCE, "trip-api", "customer-registry");

        assertThatThrownBy(() -> new BusinessAssetImpactReport("",
                BusinessAssetImpactFixtures.SCOPE, selector,
                BusinessAssetImpactReport.Status.CURRENT, List.of(), false,
                List.of(item, item), "", Instant.now(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate coordinate");

        BusinessAssetImpactReport.SourceMatch match = item.matches().getFirst();
        assertThatThrownBy(() -> new BusinessAssetImpactReport.PackageImpact(
                item.scope(), item.packageId(), item.compilationRevision(),
                item.packageSnapshotRef(), item.businessAssetLinkClosureRef(),
                List.of(match, match), item.deepLink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate coordinate");
    }

    @Test
    void rejectsDisconnectedPathsAndRiskSummariesThatDoNotMatchTheirEvidence() {
        BusinessAssetImpactReport.SourceMatch match = packageImpact().matches().getFirst();
        BusinessAssetImpactReport.ImpactPath path = match.paths().getFirst();

        assertThatThrownBy(() -> new BusinessAssetImpactReport.ImpactPath(
                path.impactedRef(), path.depth(), path.pathCount(), BusinessAssetLink.Risk.LOW,
                path.representativePath(), path.deepLink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk summary drifted");

        BusinessAssetLink edge = path.representativePath().getFirst();
        BusinessAssetLink disconnected = BusinessAssetImpactFixtures.link(
                BusinessAssetImpactFixtures.asset(BusinessAssetRef.Layer.L0_RESOURCE,
                        BusinessAssetRef.Kind.OPERATOR, "another-operator", 'f'),
                path.impactedRef(), BusinessAssetLink.Relation.USES);
        assertThatThrownBy(() -> new BusinessAssetImpactReport.ImpactPath(
                path.impactedRef(), 2, 1, BusinessAssetLink.Risk.HIGH,
                List.of(edge, disconnected), path.deepLink()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disconnected");
    }

    @Test
    void rebuildReceiptRequiresExactCountsAndLastItemCursor() {
        Instant now = Instant.parse("2026-08-14T12:00:00Z");

        assertThatThrownBy(() -> new BusinessAssetImpactRebuildReport(
                "", 1, 1, List.of("package-a"), "", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts");
        assertThatThrownBy(() -> new BusinessAssetImpactRebuildReport(
                "", 1, 0, List.of("package-a"), "package-b", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
    }

    private BusinessAssetImpactReport.PackageImpact packageImpact() {
        PackageCompilationReceipt receipt = BusinessAssetImpactFixtures.receipt(mapper, 1, '1');
        BusinessAssetImpactProjection.SourceImpact source =
                BusinessAssetImpactProjection.compile(receipt.businessAssetLinkClosure()).stream()
                        .filter(value -> value.sourceRef().kind() == BusinessAssetRef.Kind.RESOURCE)
                        .findFirst().orElseThrow();
        List<BusinessAssetImpactReport.ImpactPath> paths = source.paths().stream()
                .map(value -> new BusinessAssetImpactReport.ImpactPath(value.impactedRef(),
                        value.depth(), value.pathCount(), value.highestRisk(),
                        value.representativePath(), BusinessMirrorDeepLinks.assetLink(
                        receipt.packageId(), receipt.compilationRevision(), value.impactedRef())))
                .toList();
        BusinessAssetImpactReport.SourceMatch match = new BusinessAssetImpactReport.SourceMatch(
                source.sourceRef(), paths, BusinessMirrorDeepLinks.assetLink(
                receipt.packageId(), receipt.compilationRevision(), source.sourceRef()));
        return new BusinessAssetImpactReport.PackageImpact(BusinessAssetImpactFixtures.SCOPE,
                receipt.packageId(), receipt.compilationRevision(),
                new MirrorArtifactRef("DOMAIN_CAPABILITY_PACKAGE", receipt.packageId(),
                        receipt.compilationRevision(), receipt.snapshot().fingerprint()),
                receipt.businessAssetLinkClosure().artifactRef(), List.of(match),
                BusinessMirrorDeepLinks.packageLink(
                        receipt.packageId(), receipt.compilationRevision()));
    }
}
