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
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationBindingRequest;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationBindingService;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceReport;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceRequest;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceService;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationRuntimePort;
import com.leanowtech.bloge.gateway.businessmirror.implementation.StoredCapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.businessmirror.implementation.StoredCapabilityImplementationConformance;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection;
import com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjectionCatalog;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationEvidence;
import com.leanowtech.bloge.gateway.businessmirror.simulation.CapabilityProposalSimulationRequest;
import com.leanowtech.bloge.gateway.businessmirror.simulation.StoredCapabilityProposalSimulation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                        CapabilityProposalSnapshot.SCHEMA_VERSION))
                .containsEntry("capabilityProposalSimulationRequest", java.util.List.of(
                        CapabilityProposalSimulationRequest.SCHEMA_VERSION))
                .containsEntry("capabilityProposalSimulationEvidence", java.util.List.of(
                        CapabilityProposalSimulationEvidence.SCHEMA_VERSION))
                .containsEntry("storedCapabilityProposalSimulation", java.util.List.of(
                        StoredCapabilityProposalSimulation.SCHEMA_VERSION))
                .containsEntry("capabilityImplementationBindingRequest", java.util.List.of(
                        CapabilityImplementationBindingRequest.SCHEMA_VERSION))
                .containsEntry("capabilityImplementationBinding", java.util.List.of(
                        CapabilityImplementationBinding.SCHEMA_VERSION))
                .containsEntry("storedCapabilityImplementationBinding", java.util.List.of(
                        StoredCapabilityImplementationBinding.SCHEMA_VERSION))
                .containsEntry("capabilityImplementationConformanceRequest", java.util.List.of(
                        CapabilityImplementationConformanceRequest.SCHEMA_VERSION))
                .containsEntry("capabilityImplementationTestEvidence", java.util.List.of(
                        CapabilityImplementationConformanceReport.ImplementationEvidence
                                .SCHEMA_VERSION))
                .containsEntry("capabilityImplementationConformanceReport", java.util.List.of(
                        CapabilityImplementationConformanceReport.SCHEMA_VERSION))
                .containsEntry("storedCapabilityImplementationConformance", java.util.List.of(
                        StoredCapabilityImplementationConformance.SCHEMA_VERSION));
        assertThat(capabilities.features())
                .containsEntry("businessMirrorProtocol", true)
                .containsEntry("businessMirrorPackageApi", true)
                .containsEntry("businessMirrorPackageCompilerApi", true)
                .containsEntry("businessMirrorPackageCompilerAuthorityReady", false)
                .containsEntry("businessMirrorLegacyMigrationApi", true)
                .containsEntry("businessMirrorWorkspace", true)
                .containsEntry("businessMirrorProposalApi", true)
                .containsEntry("businessMirrorLegacyMigrationAuthorityReady", false)
                .containsEntry("businessMirrorProposalSimulation", false)
                .containsEntry("businessMirrorImplementationBindingApi", false)
                .containsEntry("businessMirrorImplementationRuntimeReady", false)
                .containsEntry("businessMirrorImplementationConformanceApi", false);
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

    @Test
    void runtimeProbeSeparatesBindingApiFromImplementationAdapterReadiness() {
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(
                null, null, null, null);
        service.configureBusinessMirrorImplementation(
                mock(CapabilityImplementationBindingService.class),
                mock(CapabilityImplementationConformanceService.class),
                CapabilityImplementationRuntimePort.unavailable());

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("businessMirrorImplementationBindingApi", true)
                .containsEntry("businessMirrorImplementationRuntimeReady", false)
                .containsEntry("businessMirrorImplementationConformanceApi", true);
        assertThat(capabilities.endpoints()).contains(
                new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/business-mirror/proposals/{proposalId}/revisions/{revision}/implementation-bindings"),
                new IntegrationCapabilities.Endpoint(
                        "GET", "/api/business-mirror/implementation-bindings/{bindingId}"),
                new IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/business-mirror/proposals/{proposalId}/revisions/{revision}/implementation-conformances"),
                new IntegrationCapabilities.Endpoint(
                        "GET",
                        "/api/business-mirror/implementation-bindings/{bindingId}/revisions/{revision}/conformance"));
    }
}
