package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceController;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceProjectionWorker;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceRepository;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceService;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationBindingController;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationBindingRepository;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationBindingService;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceController;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceRepository;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationConformanceService;
import com.leanowtech.bloge.gateway.businessmirror.implementation.CapabilityImplementationRuntimePort;
import com.leanowtech.bloge.gateway.integration.ToolStudioIntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Full Spring composition proof for the profile-gated Proposal simulation vertical slice. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.profiles.active=test",
                "gateway.testing.mirror.enabled=true",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.region=sg",
                "gateway.integration.identity.allowed-purposes=MIRROR_REHEARSAL,GOVERNANCE_EVIDENCE_INGESTION,CAPABILITY_IMPLEMENTATION,CAPABILITY_CONFORMANCE",
                "spring.datasource.url=jdbc:h2:mem:proposal-simulation-wiring;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:proposal-simulation-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=proposal-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=proposal-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=proposal-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=proposal-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=proposal-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        })
class CapabilityProposalSimulationSpringWiringTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private ToolStudioIntegrationService integration;

    @Test
    void assemblesProtectedServiceAndAdvertisesOnlyThen() {
        assertThat(context.getBean(CapabilityProposalSimulationController.class)).isNotNull();
        assertThat(context.getBean(CapabilityProposalSimulationService.class)).isNotNull();
        assertThat(context.getBean(CapabilityProposalSimulationRepository.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationBindingController.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationBindingService.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationBindingRepository.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationConformanceController.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationConformanceService.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationConformanceRepository.class)).isNotNull();
        assertThat(context.getBean(PackageEvidenceController.class)).isNotNull();
        assertThat(context.getBean(PackageEvidenceService.class)).isNotNull();
        assertThat(context.getBean(PackageEvidenceRepository.class)).isNotNull();
        assertThat(context.getBean(PackageEvidenceProjectionWorker.class)).isNotNull();
        assertThat(context.getBean(CapabilityImplementationRuntimePort.class).available()).isFalse();
        assertThat(integration.capabilities().payload().features())
                .containsEntry("businessMirrorProposalSimulation", true)
                .containsEntry("businessMirrorImplementationBindingApi", true)
                .containsEntry("businessMirrorImplementationConformanceApi", true)
                .containsEntry("businessMirrorImplementationRuntimeReady", false)
                .containsEntry("businessMirrorPackageEvidenceApi", true)
                .containsEntry("businessMirrorDomainEvidencePortfolioApi", true)
                .containsEntry("businessMirrorEvidenceOwnerTaskApi", true);
        assertThat(integration.capabilities().payload().endpoints())
                .contains(new com.leanowtech.bloge.gateway.integration.IntegrationCapabilities.Endpoint(
                        "POST",
                        "/api/business-mirror/proposals/{proposalId}/revisions/{revision}/simulations"),
                        new com.leanowtech.bloge.gateway.integration.IntegrationCapabilities.Endpoint(
                                "GET",
                                "/api/business-mirror/proposals/{proposalId}/revisions/{revision}/simulations/{simulationId}"),
                        new com.leanowtech.bloge.gateway.integration.IntegrationCapabilities.Endpoint(
                                "POST",
                                "/api/business-mirror/proposals/{proposalId}/revisions/{revision}/implementation-conformances"),
                        new com.leanowtech.bloge.gateway.integration.IntegrationCapabilities.Endpoint(
                                "GET",
                                "/api/business-mirror/implementation-bindings/{bindingId}/revisions/{revision}/conformance"));
    }
}
