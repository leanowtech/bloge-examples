package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackagePage;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageSaveReceipt;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredCapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalSaveReceipt;
import com.leanowtech.bloge.gateway.businessmirror.authoring.CapabilityProposalPage;
import com.leanowtech.bloge.gateway.businessmirror.compilation.FrozenPackageDependencies;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationAuthority;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLink;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjectionCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMirrorCapabilityTest {
    @Test
    void advertisesDurablePackageApiWithoutClaimingUnimplementedProposalRuntime() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.supportedObjects())
                .containsEntry("businessAssetLink", java.util.List.of(
                        BusinessAssetLink.SCHEMA_VERSION))
                .containsEntry("businessAssetLinkClosure", java.util.List.of(
                        BusinessAssetLinkClosure.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackageDraft", java.util.List.of(
                        DomainCapabilityPackageDraft.SCHEMA_VERSION))
                .containsEntry("storedDomainCapabilityPackageDraft", java.util.List.of(
                        StoredDomainCapabilityPackageDraft.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackageSaveReceipt", java.util.List.of(
                        DomainCapabilityPackageSaveReceipt.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackagePage", java.util.List.of(
                        DomainCapabilityPackagePage.SCHEMA_VERSION))
                .containsEntry("packageCompilationReceipt", java.util.List.of(
                        PackageCompilationReceipt.SCHEMA_VERSION))
                .containsEntry("legacyGraphPackageProjection", java.util.List.of(
                        LegacyGraphPackageProjection.SCHEMA_VERSION))
                .containsEntry("legacyGraphPackageProjectionCatalog", java.util.List.of(
                        LegacyGraphPackageProjectionCatalog.SCHEMA_VERSION))
                .containsEntry("domainCapabilityPackageSnapshot", java.util.List.of(
                        DomainCapabilityPackageSnapshot.SCHEMA_VERSION))
                .containsEntry("packageReadinessReport", java.util.List.of(
                        PackageReadinessReport.SCHEMA_VERSION))
                .containsEntry("capabilityProposalDraft", java.util.List.of(
                        CapabilityProposalDraft.SCHEMA_VERSION))
                .containsEntry("storedCapabilityProposalDraft", java.util.List.of(
                        StoredCapabilityProposalDraft.SCHEMA_VERSION))
                .containsEntry("capabilityProposalSaveReceipt", java.util.List.of(
                        CapabilityProposalSaveReceipt.SCHEMA_VERSION))
                .containsEntry("capabilityProposalPage", java.util.List.of(
                        CapabilityProposalPage.SCHEMA_VERSION))
                .containsEntry("capabilityProposalSnapshot", java.util.List.of(
                        CapabilityProposalSnapshot.SCHEMA_VERSION));
        assertThat(capabilities.features())
                .containsEntry("businessMirrorProtocol", true)
                .containsEntry("businessMirrorPackageApi", true)
                .containsEntry("businessMirrorPackageCompilerApi", true)
                .containsEntry("businessMirrorPackageCompilerAuthorityReady", false)
                .containsEntry("businessMirrorLegacyMigrationApi", true)
                .containsEntry("businessMirrorWorkspace", true)
                .containsEntry("businessMirrorProposalApi", true)
                .containsEntry("businessMirrorLegacyMigrationAuthorityReady", false)
                .containsEntry("businessMirrorProposalSimulation", false);
        assertThat(capabilities.endpoints())
                .contains(new IntegrationCapabilities.Endpoint(
                        "POST", "/api/business-mirror/packages/{packageId}/compile"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/business-mirror/packages/{packageId}/compilations/{compilationRevision}"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/business-mirror/legacy-graphs"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/business-mirror/legacy-graphs/{graphName}"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "POST", "/api/business-mirror/legacy-graphs/{graphName}/packages"));
        assertThat(capabilities.endpoints())
                .contains(new IntegrationCapabilities.Endpoint(
                        "POST", "/api/business-mirror/proposals"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "PUT", "/api/business-mirror/proposals/{proposalId}"))
                .contains(new IntegrationCapabilities.Endpoint(
                        "GET", "/api/business-mirror/proposals/{proposalId}/revisions/{revision}"));
        assertThat(capabilities.protocolVersion())
                .isEqualTo(ToolStudioResourceGatewayProtocol.VERSION);
    }

    @Test
    void runtimeProbeReflectsTheActuallyInstalledCompilationAuthority() {
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(null, null, null, null);
        service.configurePackageCompilationAuthority(new PackageCompilationAuthority() {
            @Override
            public FrozenPackageDependencies freeze(StoredDomainCapabilityPackageDraft source) {
                throw new UnsupportedOperationException("not used by the probe");
            }

            @Override
            public void assertUnchanged(FrozenPackageDependencies frozen) {
                throw new UnsupportedOperationException("not used by the probe");
            }
        });

        assertThat(service.capabilities().payload().features())
                .containsEntry("businessMirrorPackageCompilerAuthorityReady", true);
    }
}
