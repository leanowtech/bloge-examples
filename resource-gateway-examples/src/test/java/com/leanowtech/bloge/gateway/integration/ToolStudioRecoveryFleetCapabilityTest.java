package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolStudioRecoveryFleetCapabilityTest {

    @Test
    void springAutowiredCandidateFreezeFindsTheSameAuthorityThroughBothInterfaces() {
        Fleet fleet = readyFleet();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ToolStudioIntegrationService.class,
                    ToolStudioRecoveryFleetCapabilityTest::service);
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class,
                    fleet::authority);
            context.registerBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class,
                    fleet::worker);
            context.registerBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class,
                    fleet::scheduler);
            context.refresh();

            var capability = context.getBean(ToolStudioIntegrationService.class)
                    .capabilities().payload().testability().recoveryFleet();

            assertThat(capability.status())
                    .isEqualTo(
                            ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                                    .READY);
        }
    }

    @Test
    void disabledProbeAdvertisesProtocolButNoRuntimeCapability() {
        ToolStudioIntegrationService service = service();

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertThat(capabilities.supportedObjects())
                .containsEntry("externalSequenceAnchorBootstrapRootRecoveryFleetCapability",
                        List.of(
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                                        .SCHEMA_VERSION_V1,
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                                        .SCHEMA_VERSION_V2,
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                                        .SCHEMA_VERSION_V3,
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                                .SCHEMA_VERSION));
        assertThat(capabilities.features())
                .containsEntry("bootstrapRootRecoveryFleetConfigured", false)
                .containsEntry("bootstrapRootRecoveryFleetReady", false)
                .containsEntry("bootstrapRootRecoveryFleetDynamicInventory", false);
        assertThat(capabilities.testability().recoveryFleet().status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .DISABLED);
    }

    @Test
    void readyDynamicFleetIsProjectedThroughStructuredAndBooleanCapabilityViews()
            throws Exception {
        ToolStudioIntegrationService service = service();
        Fleet fleet = readyFleet();
        configure(service, List.of(fleet.authority()), List.of(fleet.authority()),
                List.of(fleet.worker()), List.of(fleet.scheduler()));

        IntegrationCapabilities capabilities = service.capabilities().payload();
        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability recoveryFleet =
                capabilities.testability().recoveryFleet();

        assertThat(recoveryFleet.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .READY);
        assertThat(recoveryFleet.ready()).isTrue();
        assertThat(capabilities.features())
                .containsEntry("bootstrapRootRecoveryFleetConfigured", true)
                .containsEntry("bootstrapRootRecoveryFleetReady", true)
                .containsEntry("bootstrapRootRecoveryFleetExternallyAttested", true)
                .containsEntry("bootstrapRootRecoveryFleetDynamicInventory", true)
                .containsEntry("bootstrapRootRecoveryFleetSignedRevocation", true)
                .containsEntry("bootstrapRootRecoveryFleetWitnessedPublications", true)
                .containsEntry("bootstrapRootRecoveryFleetDurablePublicationFloor", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetExternallyAnchoredPublicationFloor", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetByzantineQuorumPublicationFloor", true)
                .containsEntry("bootstrapRootRecoveryFleetManagedTrustRoots", true)
                .containsEntry("bootstrapRootRecoveryFleetManagedTrustRootsReady", true)
                .containsEntry("bootstrapRootRecoveryFleetAtomicDualTrustRoots", true)
                .containsEntry("bootstrapRootRecoveryFleetDurableTrustRootFloor", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetExternallyAnchoredTrustRootFloor", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetByzantineQuorumTrustRootFloor", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetExternalInventoryNonEquivocation", true)
                .containsEntry(
                        "bootstrapRootRecoveryFleetByzantineInventoryNonEquivocation", true);
        assertThat(capabilities.features())
                .containsEntry("bootstrapRootRecoveryFleetInventorySourcePrivateTrust", false)
                .containsEntry("bootstrapRootRecoveryFleetInventorySourceSpkiPinned", false)
                .containsEntry("bootstrapRootRecoveryFleetInventorySourceMutualTls", false)
                .containsEntry(
                        "bootstrapRootRecoveryFleetInventorySourceCertificateIdentityBound",
                        false)
                .containsEntry("bootstrapRootRecoveryFleetTrustRootSourcePrivateTrust", false)
                .containsEntry("bootstrapRootRecoveryFleetTrustRootSourceSpkiPinned", false)
                .containsEntry("bootstrapRootRecoveryFleetTrustRootSourceMutualTls", false)
                .containsEntry(
                        "bootstrapRootRecoveryFleetTrustRootSourceCertificateIdentityBound",
                        false);
        JsonNode wire = new ObjectMapper().valueToTree(capabilities);
        assertThat(wire.at("/testability/recoveryFleet/status").asText())
                .isEqualTo("READY");
        assertThat(wire.at("/testability/recoveryFleet/inventoryGeneration").asLong())
                .isEqualTo(17L);
        assertThat(wire.toString()).doesNotContain("fleet-id", "publication-uri", "key-id");
    }

    @Test
    void everyProbeRecomputesLocalReadinessWithoutRemoteBeanDiscovery() {
        ToolStudioIntegrationService service = service();
        Fleet fleet = readyFleet();
        var unavailable = observation(false, "REFRESH_FAILED");
        when(fleet.authority().observation()).thenReturn(
                observation(true, "VERIFIED"), observation(true, "VERIFIED"),
                unavailable, unavailable);
        when(fleet.authority().descriptor()).thenReturn(
                descriptor(true, "VERIFIED"), descriptor(false, "REFRESH_FAILED"));
        configure(service, List.of(fleet.authority()), List.of(fleet.authority()),
                List.of(fleet.worker()), List.of(fleet.scheduler()));

        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability first =
                service.capabilities().payload().testability().recoveryFleet();
        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability second =
                service.capabilities().payload().testability().recoveryFleet();

        assertThat(first.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .READY);
        assertThat(second.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .INVENTORY_UNAVAILABLE);
        assertThat(second.ready()).isFalse();
    }

    @Test
    void duplicateCandidatesFailClosedBeforeReadingAnyRuntimeSnapshot() {
        ToolStudioIntegrationService service = service();
        Fleet first = readyFleet();
        Fleet second = readyFleet();
        configure(service, List.of(first.authority(), second.authority()),
                List.of(first.authority(), second.authority()), List.of(first.worker()),
                List.of(first.scheduler()));

        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability =
                service.capabilities().payload().testability().recoveryFleet();

        assertThat(capability.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .AMBIGUOUS_COMPOSITION);
        assertThat(capability.ready()).isFalse();
        verifyNoInteractions(first.authority(), second.authority(), first.worker(),
                first.scheduler());
    }

    @Test
    void localUnattestedAndPartialCompositionsRemainDistinct() {
        ToolStudioIntegrationService service = service();
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory local = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class);
        Fleet fleet = readyFleet();
        configure(service, List.of(local), List.of(), List.of(fleet.worker()),
                List.of(fleet.scheduler()));

        var unattested = service.capabilities().payload().testability().recoveryFleet();
        configure(service, List.of(local), List.of(), List.of(fleet.worker()), List.of());
        var incomplete = service.capabilities().payload().testability().recoveryFleet();

        assertThat(unattested.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .UNATTESTED_INVENTORY);
        assertThat(incomplete.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .INCOMPLETE_COMPOSITION);
        assertThat(unattested.ready()).isFalse();
        assertThat(incomplete.ready()).isFalse();
    }

    @Test
    void snapshotExceptionBecomesPayloadFreeUnavailableCapability() {
        ToolStudioIntegrationService service = service();
        Fleet fleet = readyFleet();
        when(fleet.authority().observation()).thenThrow(
                new IllegalStateException("https://secret.example/key-material"));
        configure(service, List.of(fleet.authority()), List.of(fleet.authority()),
                List.of(fleet.worker()), List.of(fleet.scheduler()));

        ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability =
                service.capabilities().payload().testability().recoveryFleet();

        assertThat(capability.status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status
                        .UNAVAILABLE);
        assertThat(capability.toString()).doesNotContain("secret.example", "key-material");
    }

    private static ToolStudioIntegrationService service() {
        return new ToolStudioIntegrationService(null, null, null, null);
    }

    private static void configure(
            ToolStudioIntegrationService service,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory> inventories,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority> authorities,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker> workers,
            List<ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler> schedulers) {
        service.configureRecoveryFleetCapabilitySources(provider(inventories),
                provider(authorities), provider(workers), provider(schedulers));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(List<T> values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(ignored -> values.stream());
        return provider;
    }

    private static Fleet readyFleet() {
        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.class);
        ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
        ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class);
        when(authority.observation()).thenReturn(observation(true, "VERIFIED"));
        when(authority.descriptor()).thenReturn(descriptor(true, "VERIFIED"));
        when(worker.runtimeSnapshot()).thenReturn(new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot
                        .SCHEMA_VERSION,
                false, false, 0L, 0L, 0L, 0L, 0L, false, false, 0L));
        when(scheduler.snapshot()).thenReturn(new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot
                        .SCHEMA_VERSION,
                false, false, false, 0L, 0L, 0L, false, 0L, 0, 0L, 0L,
                false, null, null, 1_000L, 10_000L));
        return new Fleet(authority, worker, scheduler);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
            observation(boolean available, String status) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation
                        .SCHEMA_VERSION,
                available, status,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .SOURCE_TYPE,
                17L, 2, Instant.parse("2026-07-21T12:00:00Z"), 2, 2);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
            descriptor(boolean available, String status) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, available, status, 17L, 2, Map.ofEntries(
                Map.entry("sourceType",
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .SOURCE_TYPE),
                Map.entry("protocolVersion",
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                .SCHEMA_VERSION),
                Map.entry("privateMaterialPresent", false),
                Map.entry("automaticRefresh", true),
                Map.entry("signedRevocation", true),
                Map.entry("witnessedPublications", true),
                Map.entry("durableGenerationFloor", true),
                Map.entry("externallyAnchoredPublicationFloor", true),
                Map.entry("byzantineQuorumAnchoredPublicationFloor", true),
                Map.entry("managedTrustRootRefresh", true),
                Map.entry("managedTrustRootAvailable", true),
                Map.entry("managedTrustRootStatus", "HEALTHY"),
                Map.entry("managedTrustRootSequence", 1L),
                Map.entry("atomicDualTrustRootPublication", true),
                Map.entry("durableTrustRootFloor", true),
                Map.entry("externallyAnchoredTrustRootFloor", true),
                Map.entry("byzantineQuorumAnchoredTrustRootFloor", true),
                Map.entry("externalInventoryNonEquivocation", true),
                Map.entry("byzantineQuorumInventoryNonEquivocation", true),
                Map.entry("inventorySourceSystemTrustStore", true),
                Map.entry("inventorySourcePrivateTrustStore", false),
                Map.entry("inventorySourceServerSpkiPinned", false),
                Map.entry("inventorySourceMutualTls", false),
                Map.entry("inventorySourceCertificateIdentityBound", false),
                Map.entry("trustRootSourceSystemTrustStore", true),
                Map.entry("trustRootSourcePrivateTrustStore", false),
                Map.entry("trustRootSourceServerSpkiPinned", false),
                Map.entry("trustRootSourceMutualTls", false),
                Map.entry("trustRootSourceCertificateIdentityBound", false)));
    }

    private record Fleet(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler) {
    }
}
