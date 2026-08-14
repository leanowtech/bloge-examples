package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import org.junit.jupiter.api.Test;

import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.SCOPE;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.asset;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.fingerprint;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.provenance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessAssetProtocolTest {
    @Test
    void admitsTypedL0ToL3ReferencesAndSameScopeLinks() {
        BusinessAssetRef source = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "cancellation-fee-solution", '1');
        BusinessAssetRef target = asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "cancellation-fee-workflow", '2');

        BusinessAssetLink link = new BusinessAssetLink("", source, target,
                BusinessAssetLink.Relation.DELIVERED_BY, "fee-disputed",
                BusinessAssetLink.Risk.HIGH, "cancellation-service-owner", provenance(false));

        assertThat(link.schemaVersion()).isEqualTo(BusinessAssetLink.SCHEMA_VERSION);
        assertThat(link.sourceRef().scope()).isEqualTo(SCOPE);
        assertThat(link.condition()).isEqualTo("fee-disputed");
    }

    @Test
    void rejectsLayerKindDrift() {
        assertThatThrownBy(() -> new BusinessAssetRef(
                BusinessAssetRef.Layer.L3_APPLICATION, BusinessAssetRef.Kind.OPERATOR,
                "trip-query", 1, fingerprint('3'), "registry", SCOPE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void rejectsSelfLinks() {
        BusinessAssetRef source = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "cancellation-fee-solution", '4');

        assertThatThrownBy(() -> new BusinessAssetLink("", source, source,
                BusinessAssetLink.Relation.USES, "", BusinessAssetLink.Risk.MEDIUM,
                "owner", provenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self-link");
    }

    @Test
    void rejectsCrossScopeLinks() {
        BusinessAssetRef source = asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                BusinessAssetRef.Kind.SOLUTION, "cancellation-fee-solution", '5');
        BusinessAssetRef target = new BusinessAssetRef(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                BusinessAssetRef.Kind.WORKFLOW, "cancellation-fee-workflow", 1, fingerprint('6'),
                "registry", new CapabilitySnapshot.Scope(
                SCOPE.tenantId(), "payments", "refund", "test", "sg"));

        assertThatThrownBy(() -> new BusinessAssetLink("", source, target,
                BusinessAssetLink.Relation.DELIVERED_BY, "", BusinessAssetLink.Risk.HIGH,
                "owner", provenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enterprise scope");
    }
}
