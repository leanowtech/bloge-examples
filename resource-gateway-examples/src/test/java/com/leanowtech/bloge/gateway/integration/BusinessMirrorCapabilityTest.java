package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMirrorCapabilityTest {
    @Test
    void advertisesProtocolsWithoutClaimingUnimplementedRuntimeApis() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects())
                .containsEntry("businessAssetLink", java.util.List.of(
                        BusinessAssetLink.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackageDraft", java.util.List.of(
                        DomainCapabilityPackageDraft.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackageSnapshot", java.util.List.of(
                        DomainCapabilityPackageSnapshot.SCHEMA_VERSION))
                .containsEntry("packageReadinessReport", java.util.List.of(
                        PackageReadinessReport.SCHEMA_VERSION))
                .containsEntry("capabilityProposalDraft", java.util.List.of(
                        CapabilityProposalDraft.SCHEMA_VERSION))
                .containsEntry("capabilityProposalSnapshot", java.util.List.of(
                        CapabilityProposalSnapshot.SCHEMA_VERSION));
        assertThat(capabilities.features())
                .containsEntry("businessMirrorProtocol", true)
                .containsEntry("businessMirrorPackageApi", false)
                .containsEntry("businessMirrorProposalSimulation", false);
        assertThat(capabilities.protocolVersion())
                .isEqualTo(ToolStudioResourceGatewayProtocol.VERSION);
    }
}
