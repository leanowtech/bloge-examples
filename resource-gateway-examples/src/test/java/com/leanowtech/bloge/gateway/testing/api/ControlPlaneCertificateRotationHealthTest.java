package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneCertificateRotationHealthTest {

    @Test
    void disabledAndLocallyReadyStatesRemainDistinctFromProductionReadiness() {
        var disabled = health(descriptor(false, true, false, 0, 0, true));
        var ready = health(descriptor(true, true, true, 3, 3, true));

        assertThat(disabled.getStatus()).isEqualTo(Status.UP);
        assertThat(disabled.getDetails())
                .containsEntry("runtimeStatus", "DISABLED")
                .containsEntry("localReady", false)
                .containsEntry("durableGenerationFloorIntegrated", false);
        assertThat(ready.getStatus()).isEqualTo(Status.UP);
        assertThat(ready.getDetails())
                .containsEntry("runtimeStatus", "LOCAL_READY")
                .containsEntry("localReady", true)
                .containsEntry("durableGenerationFloorIntegrated", true)
                .containsEntry("replicaConvergenceProven", false)
                .containsEntry("productionReady", false);
    }

    @Test
    void trustRegistrationAndSynchronizationFailuresAreClosedAndBounded() {
        var trust = health(descriptor(true, false, false, 2, 2, true));
        var durability = health(descriptor(true, false, true, false, 2, 2, true));
        var partial = health(descriptor(true, false, true, 2, 1, true));
        var drift = health(descriptor(true, false, true, 2, 2, false));

        assertThat(trust.getStatus()).isEqualTo(Status.DOWN);
        assertThat(trust.getDetails()).containsEntry(
                "runtimeStatus", "TRUST_UNAVAILABLE");
        assertThat(durability.getStatus()).isEqualTo(Status.DOWN);
        assertThat(durability.getDetails()).containsEntry(
                "runtimeStatus", "DURABILITY_UNAVAILABLE");
        assertThat(partial.getStatus()).isEqualTo(Status.DOWN);
        assertThat(partial.getDetails()).containsEntry(
                "runtimeStatus", "INCOMPLETE_REGISTRATION");
        assertThat(drift.getStatus()).isEqualTo(Status.DOWN);
        assertThat(drift.getDetails()).containsEntry(
                "runtimeStatus", "STATE_OUT_OF_SYNC");
    }

    @Test
    void descriptorFailureNeverLeaksExceptionText() {
        var health = new ControlPlaneCertificateRotationHealth(() -> {
            throw new IllegalStateException("vault://certificate-secret");
        }).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "UNAVAILABLE")
                .containsKeys("enabled", "localReady", "trustAvailable",
                        "inventoriedTargetCount", "registeredTargetCount",
                        "synchronizedState", "durableGenerationFloorIntegrated",
                        "replicaConvergenceIntegrated", "replicaConvergenceAvailable",
                        "replicaConvergenceProven", "servingReady",
                        "convergenceStatus", "certificateStatusIntegrated",
                        "certificateStatusAvailable", "certificateStatusFresh",
                        "certificateStatus", "productionReady")
                .doesNotContainValue("vault://certificate-secret");
        assertThat(health.getDetails().toString())
                .doesNotContain("certificate-secret", "vault://");
    }

    @Test
    void convergenceLeaseAndServingFenceAreReportedWithoutInflatingProof() {
        var unavailable = health(convergedDescriptor(false, false, false,
                "CONVERGENCE_LEASE_UNAVAILABLE"));
        var fenced = health(convergedDescriptor(true, false, false,
                "REPLICA_NOT_ACTIVE"));
        var converged = health(convergedDescriptor(true, true, true, "CONVERGED"));

        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails()).containsEntry(
                "runtimeStatus", "CONVERGENCE_UNAVAILABLE");
        assertThat(fenced.getStatus()).isEqualTo(Status.DOWN);
        assertThat(fenced.getDetails()).containsEntry("runtimeStatus", "SERVING_FENCED");
        assertThat(converged.getStatus()).isEqualTo(Status.UP);
        assertThat(converged.getDetails())
                .containsEntry("replicaConvergenceIntegrated", true)
                .containsEntry("replicaConvergenceProven", true)
                .containsEntry("productionReady", false);
    }

    @Test
    void certificateStatusDistinguishesSourceOutageFromHardAdmissionExpiry() {
        var unavailable = health(statusDescriptor(false, false, false,
                "SOURCE_UNAVAILABLE"));
        var fenced = health(statusDescriptor(false, true, false, "CURRENT"));
        var cachedDuringOutage = health(statusDescriptor(true, false, true,
                "SOURCE_UNAVAILABLE"));

        assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unavailable.getDetails())
                .containsEntry("runtimeStatus", "CERTIFICATE_STATUS_UNAVAILABLE")
                .containsEntry("certificateStatusFresh", false);
        assertThat(fenced.getStatus()).isEqualTo(Status.DOWN);
        assertThat(fenced.getDetails())
                .containsEntry("runtimeStatus", "CERTIFICATE_STATUS_FENCED")
                .containsEntry("certificateStatusAvailable", true);
        assertThat(cachedDuringOutage.getStatus()).isEqualTo(Status.UP);
        assertThat(cachedDuringOutage.getDetails())
                .containsEntry("runtimeStatus", "LOCAL_READY")
                .containsEntry("certificateStatusAvailable", false)
                .containsEntry("certificateStatusFresh", true)
                .containsEntry("productionReady", false);
    }

    private static org.springframework.boot.actuate.health.Health health(
            ControlPlaneCertificateRotationRuntime.Descriptor descriptor) {
        return new ControlPlaneCertificateRotationHealth(() -> descriptor).health();
    }

    private static ControlPlaneCertificateRotationRuntime.Descriptor descriptor(
            boolean enabled,
            boolean ready,
            boolean trustAvailable,
            int inventoried,
            int registered,
            boolean synchronizedState) {
        return descriptor(enabled, ready, trustAvailable, true, inventoried, registered,
                synchronizedState);
    }

    private static ControlPlaneCertificateRotationRuntime.Descriptor descriptor(
            boolean enabled,
            boolean ready,
            boolean trustAvailable,
            boolean durableState,
            int inventoried,
            int registered,
            boolean synchronizedState) {
        return new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                enabled, ready, trustAvailable, durableState, inventoried, registered,
                synchronizedState);
    }

    private static ControlPlaneCertificateRotationRuntime.Descriptor convergedDescriptor(
            boolean available,
            boolean servingReady,
            boolean proven,
            String status) {
        return new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                true, servingReady, true, true, 2, 2, true,
                true, available, proven, servingReady, false, status);
    }

    private static ControlPlaneCertificateRotationRuntime.Descriptor statusDescriptor(
            boolean ready,
            boolean available,
            boolean fresh,
            String status) {
        return new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                true, ready, true, true, 2, 2, true,
                false, false, false, false,
                true, available, fresh, false, "DISABLED", status);
    }
}
