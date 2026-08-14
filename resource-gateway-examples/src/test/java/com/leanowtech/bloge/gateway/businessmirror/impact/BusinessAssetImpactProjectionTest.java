package com.leanowtech.bloge.gateway.businessmirror.impact;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAssetImpactProjectionTest {
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "customer-service", "refund", "test", "cn-north");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void derivesCanonicalTransitivePathsAndCountsAlternatives() {
        BusinessAssetRef resource = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.RESOURCE, "trip-api", '1');
        BusinessAssetRef operator = asset(BusinessAssetRef.Layer.L0_RESOURCE,
                BusinessAssetRef.Kind.OPERATOR, "query-trip", '2');
        BusinessAssetRef solution = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "refund-solution", '3');
        BusinessAssetRef workflow = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "refund-workflow", '4');
        BusinessAssetRef channel = asset(BusinessAssetRef.Layer.L3_APPLICATION,
                BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-console", '5');
        BusinessAssetLinkClosure closure = new BusinessAssetLinkClosure("", "closure-a", 1, "",
                SCOPE, "refund", List.of(resource, operator, solution, workflow, channel), List.of(
                link(resource, operator, BusinessAssetLink.Relation.IMPLEMENTS,
                        BusinessAssetLink.Risk.HIGH),
                link(resource, solution, BusinessAssetLink.Relation.USES,
                        BusinessAssetLink.Risk.MEDIUM),
                link(operator, solution, BusinessAssetLink.Relation.USES,
                        BusinessAssetLink.Risk.CRITICAL),
                link(solution, workflow, BusinessAssetLink.Relation.DELIVERED_BY,
                        BusinessAssetLink.Risk.HIGH),
                link(workflow, channel, BusinessAssetLink.Relation.EXPOSED_ON,
                        BusinessAssetLink.Risk.MEDIUM)), NOW);

        BusinessAssetImpactProjection.SourceImpact impact =
                BusinessAssetImpactProjection.compile(closure).stream()
                        .filter(value -> value.sourceRef().equals(resource)).findFirst().orElseThrow();

        assertThat(impact.paths()).extracting(path -> path.impactedRef().id())
                .containsExactly("query-trip", "refund-solution", "refund-workflow", "support-console");
        BusinessAssetImpactProjection.ImpactPath solutionImpact = impact.paths().stream()
                .filter(path -> path.impactedRef().equals(solution)).findFirst().orElseThrow();
        assertThat(solutionImpact.pathCount()).isEqualTo(2);
        assertThat(solutionImpact.depth()).isEqualTo(1);
        assertThat(solutionImpact.highestRisk()).isEqualTo(BusinessAssetLink.Risk.CRITICAL);
        assertThat(solutionImpact.representativePath()).extracting(BusinessAssetLink::relation)
                .containsExactly(BusinessAssetLink.Relation.USES);
    }

    private static BusinessAssetRef asset(
            BusinessAssetRef.Layer layer, BusinessAssetRef.Kind kind, String id, char fingerprint) {
        return new BusinessAssetRef(layer, kind, id, 1,
                "sha256:" + String.valueOf(fingerprint).repeat(64), "customer-registry", SCOPE);
    }

    private static BusinessAssetLink link(
            BusinessAssetRef source,
            BusinessAssetRef target,
            BusinessAssetLink.Relation relation,
            BusinessAssetLink.Risk risk) {
        return new BusinessAssetLink("", source, target, relation, "", risk, "refund-owner",
                new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                        SCOPE.tenantId(), "impact-test", null, null, null, null,
                        List.of(), "refund-owner", NOW, null, ""));
    }
}
