package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptRuntimeCapability;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolStudioPhysicalAttemptCapabilityTest {

    private static final String GENERATION = "sha256:" + "1".repeat(64);

    @Test
    void disabledProbePublishesProtocolObjectsAndClosedFeatures() {
        IntegrationCapabilities capabilities = service().capabilities().payload();

        assertThat(capabilities.supportedObjects())
                .containsEntry("physicalAttemptProviderInventory", List.of(
                        TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION))
                .containsEntry("physicalAttemptRuntimeCapability", List.of(
                        TestSuiteStabilityPhysicalAttemptRuntimeCapability.SCHEMA_VERSION));
        assertThat(capabilities.features())
                .containsEntry("physicalAttemptRuntimeConfigured", false)
                .containsEntry("physicalAttemptRuntimeReady", false)
                .containsEntry("physicalAttemptProviderInventoryDynamic", false)
                .containsEntry("physicalAttemptProviderInventoryExternalNonEquivocation", false)
                .containsEntry(
                        "physicalAttemptProviderInventoryByzantineNonEquivocation", false)
                .containsEntry("physicalAttemptProviderInventoryCohortConverged", false);
        assertThat(capabilities.testability().physicalAttemptRuntime().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .DISABLED);
    }

    @Test
    void partialAndAmbiguousCompositionsFailClosedBeforeSnapshotReads() {
        ToolStudioIntegrationService service = service();
        TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory = inventory(true);
        configure(service, List.of(inventory), List.of(), List.of(), List.of());

        assertThat(service.capabilities().payload().testability()
                .physicalAttemptRuntime().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .INCOMPLETE);

        configure(service, List.of(inventory, inventory), List.of(cohort()),
                List.of(reconciliation()), List.of(terminal()));
        assertThat(service.capabilities().payload().testability()
                .physicalAttemptRuntime().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .AMBIGUOUS);
        verify(inventory, never()).observation();
    }

    @Test
    void staticInventoryIsVisibleButCannotClaimIndustrialReadiness() {
        ToolStudioIntegrationService service = service();
        TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory = inventory(false);
        configure(service, List.of(inventory), List.of(cohort()),
                List.of(reconciliation()), List.of(terminal()));

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertThat(capabilities.testability().physicalAttemptRuntime()).satisfies(capability -> {
            assertThat(capability.configured()).isTrue();
            assertThat(capability.ready()).isFalse();
            assertThat(capability.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                            .DYNAMIC_INVENTORY_REQUIRED);
        });
        assertThat(capabilities.features())
                .containsEntry("physicalAttemptProviderInventoryExternallyAttested", true)
                .containsEntry("physicalAttemptProviderInventoryAvailable", true)
                .containsEntry("physicalAttemptProviderInventoryDynamic", false)
                .containsEntry("physicalAttemptRuntimeReady", false);
        verify(inventory, never()).resolve(anyString(), anyString());
    }

    @Test
    void exactDynamicCohortAndHealthyRuntimesProjectReadyInBothViews() {
        ToolStudioIntegrationService service = service();
        TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory = inventory(true);
        configure(service, List.of(inventory), List.of(cohort()),
                List.of(reconciliation()), List.of(terminal()));

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertThat(capabilities.testability().physicalAttemptRuntime()).satisfies(capability -> {
            assertThat(capability.ready()).isTrue();
            assertThat(capability.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus.READY);
            assertThat(capability.expectedReplicaCount()).isEqualTo(2);
            assertThat(capability.readyReplicaCount()).isEqualTo(2);
        });
        assertThat(capabilities.features())
                .containsEntry("physicalAttemptRuntimeConfigured", true)
                .containsEntry("physicalAttemptRuntimeReady", true)
                .containsEntry("physicalAttemptProviderInventorySignedRevocation", true)
                .containsEntry("physicalAttemptProviderInventoryWitnessedPublications", true)
                .containsEntry("physicalAttemptProviderInventoryDurablePublicationFloor", true)
                .containsEntry("physicalAttemptProviderInventoryExternalNonEquivocation", true)
                .containsEntry(
                        "physicalAttemptProviderInventoryByzantineNonEquivocation", true)
                .containsEntry("physicalAttemptProviderInventoryCohortConverged", true)
                .containsEntry("physicalAttemptObservationReconciliationReady", true)
                .containsEntry("physicalAttemptTerminalProjectionReady", true);
        verify(inventory, never()).resolve(anyString(), anyString());
    }

    @Test
    void publicProjectionContainsNoPrivateInventoryOrProviderIdentity() {
        ToolStudioIntegrationService service = service();
        configure(service, List.of(inventory(true)), List.of(cohort()),
                List.of(reconciliation()), List.of(terminal()));

        String projection = service.capabilities().payload().testability()
                .physicalAttemptRuntime().toString();

        assertThat(projection).doesNotContain(
                "provider-sensitive", "deployment-sensitive", GENERATION,
                "cohort-sensitive", "policy-sensitive", "key-sensitive");
    }

    private static ToolStudioIntegrationService service() {
        return new ToolStudioIntegrationService(null, null, null, null);
    }

    private static void configure(
            ToolStudioIntegrationService service,
            List<TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority> inventories,
            List<TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate> cohorts,
            List<TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth> reconciliation,
            List<TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth> terminal) {
        service.configurePhysicalAttemptCapabilitySources(provider(inventories),
                provider(cohorts), provider(reconciliation), provider(terminal));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(List<T> values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(ignored -> values.stream());
        return provider;
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory(
            boolean dynamic) {
        TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory =
                mock(TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
        when(inventory.observation()).thenReturn(new
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation
                        .SCHEMA_VERSION,
                true, true, "VERIFIED", dynamic ? "DYNAMIC_SIGNED" : "STATIC_SIGNED",
                17, GENERATION, 17, GENERATION, "sha256:" + "2".repeat(64),
                "cohort-sensitive", List.of(binding()),
                Instant.parse("2026-07-23T00:00:00Z"), 2, 2));
        when(inventory.descriptor()).thenReturn(new
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, true, "VERIFIED", 17, 1,
                Map.of("sourceType", dynamic ? "DYNAMIC_SIGNED" : "STATIC_SIGNED",
                        "privateMaterialPresent", false,
                        "dynamicInventory", dynamic,
                        "automaticRefresh", dynamic,
                        "signedRevocation", dynamic,
                        "witnessedPublications", dynamic,
                        "durablePublicationFloor", dynamic,
                        "externalNonEquivocation", dynamic,
                        "byzantineQuorumNonEquivocation", dynamic)));
        return inventory;
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort() {
        TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort =
                mock(TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.class);
        when(cohort.observation()).thenReturn(new
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation
                        .SCHEMA_VERSION,
                true, "CONVERGED", 17, GENERATION, 2, 2, 1,
                Instant.parse("2026-07-22T00:00:00Z")));
        return cohort;
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth
            reconciliation() {
        TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth health =
                mock(TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.class);
        when(health.health()).thenReturn(Health.up().build());
        return health;
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth terminal() {
        TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth health =
                mock(TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.class);
        when(health.health()).thenReturn(Health.up().build());
        return health;
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventory.Binding binding() {
        return new TestSuiteStabilityPhysicalAttemptProviderInventory.Binding(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Binding.SCHEMA_VERSION,
                "provider-sensitive", "deployment-sensitive",
                "sha256:" + "3".repeat(64), "key-sensitive",
                List.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                1_000, 60_000);
    }
}
