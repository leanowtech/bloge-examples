package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Descriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Observation;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest {

    @Test
    void readyProjectionPublishesOnlyAggregateDynamicAndRuntimeTruth() {
        var capability = project(verified(17L), descriptor(17L, true), readyWorker(),
                readyScheduler());

        assertThat(capability).satisfies(value -> {
            assertThat(value.configured()).isTrue();
            assertThat(value.ready()).isTrue();
            assertThat(value.status()).isEqualTo(Status.READY);
            assertThat(value.externallyAttested()).isTrue();
            assertThat(value.inventoryAvailable()).isTrue();
            assertThat(value.sourceType()).isEqualTo(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                            .SOURCE_TYPE);
            assertThat(value.inventoryGeneration()).isEqualTo(17L);
            assertThat(value.laneCount()).isEqualTo(2);
            assertThat(value.dynamicInventory()).isTrue();
            assertThat(value.automaticRefresh()).isTrue();
            assertThat(value.signedRevocation()).isTrue();
            assertThat(value.witnessedPublications()).isTrue();
            assertThat(value.durablePublicationFloor()).isTrue();
            assertThat(value.externallyAnchoredPublicationFloor()).isTrue();
            assertThat(value.byzantineQuorumAnchoredPublicationFloor()).isTrue();
            assertThat(value.managedTrustRootRefresh()).isTrue();
            assertThat(value.managedTrustRootAvailable()).isTrue();
            assertThat(value.managedTrustRootStatus()).isEqualTo("HEALTHY");
            assertThat(value.managedTrustRootSequence()).isOne();
            assertThat(value.atomicDualTrustRootPublication()).isTrue();
            assertThat(value.durableTrustRootFloor()).isTrue();
            assertThat(value.externallyAnchoredTrustRootFloor()).isTrue();
            assertThat(value.byzantineQuorumAnchoredTrustRootFloor()).isTrue();
            assertThat(value.externalInventoryNonEquivocation()).isTrue();
            assertThat(value.byzantineQuorumInventoryNonEquivocation()).isTrue();
            assertThat(value.pollCount()).isZero();
            assertThat(value.cycleCount()).isZero();
        });
    }

    @ParameterizedTest
    @MethodSource("runtimeFailures")
    void classifiesEveryRuntimeFailureWithoutLosingVerifiedInventory(
            RuntimeFailure failure) {
        var capability = project(verified(17L), descriptor(17L, true),
                failure.worker(), failure.scheduler());

        assertThat(capability.ready()).isFalse();
        assertThat(capability.status()).isEqualTo(failure.status());
        assertThat(capability.inventoryAvailable()).isTrue();
        assertThat(capability.inventoryGeneration()).isEqualTo(17L);
    }

    @Test
    void unavailableInventoryTakesPrecedenceOverOtherwiseHealthyRuntime() {
        var capability = project(unavailableObservation(), descriptor(17L, false),
                readyWorker(), readyScheduler());

        assertThat(capability.status()).isEqualTo(Status.INVENTORY_UNAVAILABLE);
        assertThat(capability.ready()).isFalse();
        assertThat(capability.inventoryAvailable()).isFalse();
        assertThat(capability.automaticRefresh()).isTrue();
        assertThat(capability.managedTrustRootAvailable()).isTrue();
        assertThat(capability.managedTrustRootStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void generationChangeAcrossProjectionFailsClosedAsInconsistent() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<Observation> observations = () -> reads.getAndIncrement() == 0
                ? verified(17L) : verified(18L);

        var capability = ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.project(
                observations, () -> descriptor(17L, true),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest::readyWorker,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest::readyScheduler);

        assertThat(capability.status()).isEqualTo(Status.INCONSISTENT);
        assertThat(capability.ready()).isFalse();
        assertThat(capability.inventoryGeneration()).isZero();
        assertThat(capability.sourceType()).isEmpty();
    }

    @Test
    void localProjectionFailureReturnsBoundedUnavailableTruth() {
        var capability = ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.project(
                () -> {
                    throw new IllegalStateException("contains endpoint and key material");
                }, () -> descriptor(17L, true),
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest::readyWorker,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapabilityTest::readyScheduler);

        assertThat(capability)
                .isEqualTo(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability
                        .unavailable());
        assertThat(capability.toString()).doesNotContain("endpoint", "key material");
    }

    @Test
    void closedCompositionFactoriesAreMutuallyExclusiveAndIdentityFree() {
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled())
                .extracting(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability::status)
                .isEqualTo(Status.DISABLED);
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.incomplete())
                .extracting(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability::status)
                .isEqualTo(Status.INCOMPLETE_COMPOSITION);
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.ambiguous())
                .extracting(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability::status)
                .isEqualTo(Status.AMBIGUOUS_COMPOSITION);
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.unattested())
                .extracting(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability::status)
                .isEqualTo(Status.UNATTESTED_INVENTORY);
        assertThat(ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.disabled()
                .sourceType()).isEmpty();
    }

    @Test
    void recordRejectsContradictoryReadinessAndAnchorClaims() {
        assertThatThrownBy(() -> capability(false, true, Status.READY,
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capability(true, true, Status.READY,
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capability(true, false, Status.UNAVAILABLE,
                true, false, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability project(
            Observation observation,
            Descriptor descriptor,
            RuntimeSnapshot worker,
            Snapshot scheduler) {
        return ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.project(
                () -> observation, () -> descriptor, () -> worker, () -> scheduler);
    }

    private static Observation verified(long generation) {
        return observation(true, "VERIFIED", generation);
    }

    private static Observation unavailableObservation() {
        return observation(false, "REFRESH_FAILED", 17L);
    }

    private static Observation observation(
            boolean available,
            String status,
            long generation) {
        return new Observation(Observation.SCHEMA_VERSION, available, status,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .SOURCE_TYPE,
                generation, 2, Instant.parse("2026-07-21T12:00:00Z"), 2, 2);
    }

    private static Descriptor descriptor(long generation, boolean available) {
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, available,
                available ? "VERIFIED" : "REFRESH_FAILED", generation, 2,
                Map.ofEntries(
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
                        Map.entry("trustRootSourceSystemTrustStore", true),
                        Map.entry("trustRootSourcePrivateTrustStore", false),
                        Map.entry("trustRootSourceServerSpkiPinned", false),
                        Map.entry("trustRootSourceMutualTls", false)));
    }

    private static RuntimeSnapshot readyWorker() {
        return worker(false, false, false);
    }

    private static RuntimeSnapshot worker(
            boolean closed,
            boolean failed,
            boolean laneFailures) {
        long cycles = failed || laneFailures ? 1L : 0L;
        long attempts = laneFailures ? 1L : 0L;
        return new RuntimeSnapshot(RuntimeSnapshot.SCHEMA_VERSION, closed, false, cycles,
                failed ? 1L : 0L, attempts, 0L, laneFailures ? 1L : 0L, failed,
                laneFailures, cycles == 0L ? 0L : 17L);
    }

    private static Snapshot readyScheduler() {
        return scheduler(false, false, false);
    }

    private static Snapshot scheduler(
            boolean closed,
            boolean overdue,
            boolean failed) {
        Instant started = failed ? Instant.parse("2026-07-21T10:00:00Z") : null;
        Instant completed = failed ? Instant.parse("2026-07-21T10:00:01Z") : null;
        return new Snapshot(Snapshot.SCHEMA_VERSION, closed, false, overdue,
                failed ? 1L : 0L, 0L, failed ? 1L : 0L, failed,
                0L, 0, 0L, 0L, false, started, completed, 1_000L, 10_000L);
    }

    private static Stream<RuntimeFailure> runtimeFailures() {
        return Stream.of(
                new RuntimeFailure(Status.RUNTIME_CLOSED,
                        worker(true, false, false), scheduler(true, false, false)),
                new RuntimeFailure(Status.SCHEDULER_STALLED,
                        readyWorker(), scheduler(false, true, false)),
                new RuntimeFailure(Status.SCHEDULER_FAILED,
                        readyWorker(), scheduler(false, false, true)),
                new RuntimeFailure(Status.CYCLE_FAILED,
                        worker(false, true, false), readyScheduler()),
                new RuntimeFailure(Status.LANE_FAILURES,
                        worker(false, false, true), readyScheduler()));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability(
            boolean configured,
            boolean ready,
            Status status,
            boolean inventoryAvailable,
            boolean externalAnchor,
            boolean byzantineAnchor) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.SCHEMA_VERSION,
                configured, ready, status, inventoryAvailable, inventoryAvailable,
                inventoryAvailable
                        ? DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .SOURCE_TYPE : "",
                inventoryAvailable ? 1L : 0L, 0, inventoryAvailable,
                inventoryAvailable, inventoryAvailable, inventoryAvailable,
                inventoryAvailable, externalAnchor, byzantineAnchor,
                false, false, 0L, 0L, 0L, 0L);
    }

    private record RuntimeFailure(
            Status status,
            RuntimeSnapshot worker,
            Snapshot scheduler) {
    }
}
