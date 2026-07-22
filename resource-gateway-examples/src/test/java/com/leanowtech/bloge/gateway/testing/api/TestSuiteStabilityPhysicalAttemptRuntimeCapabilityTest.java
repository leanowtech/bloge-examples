package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptRuntimeCapabilityTest {

    @Test
    void staticSignedInventoryIsTruthfullyClosedForIndustrialReadiness() {
        Fixture fixture = fixture(properties(false, false, false, false, false));

        var capability = fixture.project();

        assertThat(capability.ready()).isFalse();
        assertThat(capability.status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .DYNAMIC_INVENTORY_REQUIRED);
        assertThat(capability.providerInventory().externallyAttested()).isTrue();
        assertThat(capability.observationReconciliationReady()).isTrue();
        assertThat(capability.terminalProjectionReady()).isTrue();
    }

    @Test
    void dynamicInventoryNeedsAutomaticRefreshBeforeLaterGuarantees() {
        Fixture fixture = fixture(properties(true, false, false, false, false));

        assertThat(fixture.project().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .AUTOMATIC_REFRESH_REQUIRED);
    }

    @Test
    void capabilityOrdersRevocationWitnessAndDurableFloorRequirements() {
        assertThat(fixture(properties(true, true, false, false, false)).project().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .SIGNED_REVOCATION_REQUIRED);
        assertThat(fixture(properties(true, true, true, false, false)).project().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .WITNESS_REQUIRED);
        assertThat(fixture(properties(true, true, true, true, false)).project().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .DURABLE_FLOOR_REQUIRED);
    }

    @Test
    void mismatchedCohortGenerationFailsClosed() {
        Fixture fixture = fixture(properties(true, true, true, true, true));
        when(fixture.cohort().observation()).thenReturn(cohort("sha256:" + "9".repeat(64),
                2, 2, 1, true));

        assertThat(fixture.project().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .COHORT_UNAVAILABLE);
    }

    @Test
    void incompleteCohortDoesNotBecomeReady() {
        Fixture fixture = fixture(properties(true, true, true, true, true));
        when(fixture.cohort().observation()).thenReturn(cohort(FINGERPRINT,
                2, 1, 1, true));

        assertThat(fixture.project()).satisfies(capability -> {
            assertThat(capability.ready()).isFalse();
            assertThat(capability.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                            .COHORT_NOT_CONVERGED);
            assertThat(capability.readyReplicaCount()).isEqualTo(1);
        });
    }

    @Test
    void inventoryRefreshTearCannotProduceMixedGenerationReadiness() {
        Fixture fixture = fixture(properties(true, true, true, true, true));
        var next = observation(18, "sha256:" + "8".repeat(64), true);
        when(fixture.inventory().observation()).thenReturn(observation(), next);

        assertThat(fixture.project().status()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .INVENTORY_INCONSISTENT);
    }

    @Test
    void unhealthyRuntimeKeepsConvergedInventoryClosed() {
        Fixture fixture = fixture(properties(true, true, true, true, true));
        when(fixture.reconciliation().health()).thenReturn(Health.down().build());

        assertThat(fixture.project()).satisfies(capability -> {
            assertThat(capability.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                            .RUNTIME_UNAVAILABLE);
            assertThat(capability.cohortConverged()).isTrue();
            assertThat(capability.observationReconciliationReady()).isFalse();
        });
    }

    @Test
    void exactDynamicConvergedHealthyCompositionIsReady() {
        Fixture fixture = fixture(properties(true, true, true, true, true));

        assertThat(fixture.project()).satisfies(capability -> {
            assertThat(capability.ready()).isTrue();
            assertThat(capability.status()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus.READY);
            assertThat(capability.expectedReplicaCount()).isEqualTo(2);
            assertThat(capability.readyReplicaCount()).isEqualTo(2);
        });
        verify(fixture.reconciliation()).health();
        verify(fixture.terminal()).health();
    }

    @Test
    void closedFactoriesAreDistinctAndIdentityFree() {
        assertThat(TestSuiteStabilityPhysicalAttemptRuntimeCapability.disabled().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .DISABLED);
        assertThat(TestSuiteStabilityPhysicalAttemptRuntimeCapability.incomplete().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .INCOMPLETE);
        assertThat(TestSuiteStabilityPhysicalAttemptRuntimeCapability.ambiguous().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .AMBIGUOUS);
        assertThat(TestSuiteStabilityPhysicalAttemptRuntimeCapability.unavailable().status())
                .isEqualTo(TestSuiteStabilityPhysicalAttemptRuntimeCapability.CapabilityStatus
                        .UNAVAILABLE);
    }

    private static final String FINGERPRINT = "sha256:" + "1".repeat(64);
    private static final String POLICY = "sha256:" + "2".repeat(64);

    private static Fixture fixture(Map<String, Object> properties) {
        var inventory = mock(TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
        var cohort = mock(TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.class);
        var reconciliation = mock(
                TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth.class);
        var terminal = mock(TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth.class);
        when(inventory.observation()).thenReturn(observation());
        when(inventory.descriptor()).thenReturn(new
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Descriptor
                        .SCHEMA_VERSION,
                true, true, true, "VERIFIED", 17, 1, properties));
        when(cohort.observation()).thenReturn(cohort(FINGERPRINT, 2, 2, 1, true));
        when(reconciliation.health()).thenReturn(Health.up().build());
        when(terminal.health()).thenReturn(Health.up().build());
        return new Fixture(inventory, cohort, reconciliation, terminal);
    }

    private static Map<String, Object> properties(
            boolean dynamic,
            boolean refresh,
            boolean revocation,
            boolean witness,
            boolean floor) {
        return Map.of("sourceType", dynamic ? "DYNAMIC_SIGNED" : "STATIC_SIGNED",
                "privateMaterialPresent", false,
                "dynamicInventory", dynamic,
                "automaticRefresh", refresh,
                "signedRevocation", revocation,
                "witnessedPublications", witness,
                "durablePublicationFloor", floor);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation
            observation() {
        return observation(17, FINGERPRINT, true);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation
            observation(long revision, String fingerprint, boolean available) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Observation
                        .SCHEMA_VERSION,
                true, available, available ? "VERIFIED" : "EXPIRED", "DYNAMIC_SIGNED",
                revision, fingerprint, revision, fingerprint, POLICY, "cohort-a",
                List.of(binding()), Instant.parse("2026-07-23T00:00:00Z"), 2, 2);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation cohort(
            String fingerprint,
            int expected,
            int ready,
            int generations,
            boolean available) {
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation
                        .SCHEMA_VERSION,
                available, ready == expected && generations == 1 ? "CONVERGED" : "PENDING",
                17, fingerprint,
                expected, ready, generations, Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventory.Binding binding() {
        return new TestSuiteStabilityPhysicalAttemptProviderInventory.Binding(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Binding.SCHEMA_VERSION,
                "provider-a", "deployment-a", "sha256:" + "3".repeat(64), "key-a",
                List.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                1_000, 60_000);
    }

    private record Fixture(
            TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority inventory,
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate cohort,
            TestSuiteStabilityPhysicalAttemptObservationReconciliationHealth reconciliation,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionHealth terminal) {
        private TestSuiteStabilityPhysicalAttemptRuntimeCapability project() {
            return TestSuiteStabilityPhysicalAttemptRuntimeCapability.project(
                    inventory, cohort, reconciliation, terminal);
        }
    }
}
