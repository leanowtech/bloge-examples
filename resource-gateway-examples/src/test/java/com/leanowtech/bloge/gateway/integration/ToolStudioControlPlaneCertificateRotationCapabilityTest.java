package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventWatcher;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolStudioControlPlaneCertificateRotationCapabilityTest {

    @Test
    void absentRuntimeAdvertisesNoRotationOrProductionReadiness() {
        var features = service().capabilities().payload().features();

        assertThat(features)
                .containsEntry("signedControlPlaneCertificateRotation", false)
                .containsEntry("restartFreeControlPlaneCertificateRotation", false)
                .containsEntry("controlPlaneCertificateRotationLocalReady", false)
                .containsEntry("controlPlaneCertificateRotationDurableFloorIntegrated", false)
                .containsEntry(
                        "controlPlaneCertificateRotationReplicaConvergenceIntegrated", false)
                .containsEntry(
                        "controlPlaneCertificateRotationReplicaConvergenceAvailable", false)
                .containsEntry("controlPlaneCertificateRotationReplicaConvergenceProven", false)
                .containsEntry("controlPlaneCertificateRotationServingReady", false)
                .containsEntry("controlPlaneCertificateStatusIntegrated", false)
                .containsEntry("controlPlaneCertificateStatusAvailable", false)
                .containsEntry("controlPlaneCertificateStatusFresh", false)
                .containsEntry("controlPlaneCertificateRevocationAdmission", false)
                .containsEntry("controlPlaneCertificateRotationEventDeliveryIntegrated", false)
                .containsEntry("controlPlaneCertificateRotationEventDeliveryReady", false)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliveryDurableCursor", false)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliveryAuthenticatedSource", false)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliverySourceMutualTls", false)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliverySourceCertificateIdentityBound",
                        false)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
    }

    @Test
    void localReadinessNeverInflatesDurabilityConvergenceOrProductionClaims() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationRuntime runtime = mock(
                ControlPlaneCertificateRotationRuntime.class);
        when(runtime.descriptor()).thenReturn(descriptor(true, true, true, 12, 12, true));
        service.configureControlPlaneCertificateRotation(runtime);

        var features = service.capabilities().payload().features();

        assertThat(features)
                .containsEntry("signedControlPlaneCertificateRotation", true)
                .containsEntry("restartFreeControlPlaneCertificateRotation", true)
                .containsEntry("controlPlaneCertificateRotationLocalReady", true)
                .containsEntry("controlPlaneCertificateRotationDurableFloorIntegrated", true)
                .containsEntry(
                        "controlPlaneCertificateRotationReplicaConvergenceIntegrated", false)
                .containsEntry("controlPlaneCertificateRotationReplicaConvergenceProven", false)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
    }

    @Test
    void descriptorFailureBecomesClosedCapabilityWithoutLeakingDiagnostics() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationRuntime runtime = mock(
                ControlPlaneCertificateRotationRuntime.class);
        when(runtime.descriptor()).thenThrow(
                new IllegalStateException("vault://rotation-private-material"));
        service.configureControlPlaneCertificateRotation(runtime);

        var capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("signedControlPlaneCertificateRotation", true)
                .containsEntry("controlPlaneCertificateRotationLocalReady", false)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
        assertThat(capabilities.toString())
                .doesNotContain("rotation-private-material", "vault://");
    }

    @Test
    void integratedConvergenceAdvertisesProofWithoutInflatingEnterpriseReadiness() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationRuntime runtime = mock(
                ControlPlaneCertificateRotationRuntime.class);
        when(runtime.descriptor()).thenReturn(
                new ControlPlaneCertificateRotationRuntime.Descriptor(
                        ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, 12, 12, true,
                        true, true, true, true, false, "CONVERGED"));
        service.configureControlPlaneCertificateRotation(runtime);

        assertThat(service.capabilities().payload().features())
                .containsEntry(
                        "controlPlaneCertificateRotationReplicaConvergenceIntegrated", true)
                .containsEntry(
                        "controlPlaneCertificateRotationReplicaConvergenceAvailable", true)
                .containsEntry("controlPlaneCertificateRotationReplicaConvergenceProven", true)
                .containsEntry("controlPlaneCertificateRotationServingReady", true)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
    }

    @Test
    void freshStatusAdmissionIsAdvertisedSeparatelyFromSourceAvailability() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationRuntime runtime = mock(
                ControlPlaneCertificateRotationRuntime.class);
        when(runtime.descriptor()).thenReturn(
                new ControlPlaneCertificateRotationRuntime.Descriptor(
                        ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, 12, 12, true,
                        false, false, false, false,
                        true, false, true, false,
                        "DISABLED", "SOURCE_UNAVAILABLE"));
        service.configureControlPlaneCertificateRotation(runtime);

        assertThat(service.capabilities().payload().features())
                .containsEntry("controlPlaneCertificateStatusIntegrated", true)
                .containsEntry("controlPlaneCertificateStatusAvailable", false)
                .containsEntry("controlPlaneCertificateStatusFresh", true)
                .containsEntry("controlPlaneCertificateRevocationAdmission", true)
                .containsEntry("controlPlaneCertificateRotationLocalReady", true)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
    }

    @Test
    void authenticatedDurableEventDeliveryIsAdvertisedWithoutInflatingProductionReadiness() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationEventWatcher watcher = mock(
                ControlPlaneCertificateRotationEventWatcher.class);
        when(watcher.descriptor()).thenReturn(
                new ControlPlaneCertificateRotationEventWatcher.Descriptor(
                        ControlPlaneCertificateRotationEventWatcher.Descriptor.SCHEMA_VERSION,
                        true, true, true, true, true, true,
                        7, false, 2, 3, "IDLE", "NO_EVENTS"));
        service.configureControlPlaneCertificateRotationEventWatcher(watcher);

        assertThat(service.capabilities().payload().features())
                .containsEntry("controlPlaneCertificateRotationEventDeliveryIntegrated", true)
                .containsEntry("controlPlaneCertificateRotationEventDeliveryReady", true)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliveryDurableCursor", true)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliveryAuthenticatedSource", true)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliverySourceMutualTls", true)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliverySourceCertificateIdentityBound",
                        true)
                .containsEntry("controlPlaneCertificateRotationProductionReady", false);
    }

    @Test
    void eventWatcherDescriptorFailureClosesCapabilityWithoutLeakingDiagnostics() {
        ToolStudioIntegrationService service = service();
        ControlPlaneCertificateRotationEventWatcher watcher = mock(
                ControlPlaneCertificateRotationEventWatcher.class);
        when(watcher.descriptor()).thenThrow(
                new IllegalStateException("https://ca.internal/events?credential=secret"));
        service.configureControlPlaneCertificateRotationEventWatcher(watcher);

        var capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry("controlPlaneCertificateRotationEventDeliveryIntegrated", true)
                .containsEntry("controlPlaneCertificateRotationEventDeliveryReady", false)
                .containsEntry(
                        "controlPlaneCertificateRotationEventDeliveryAuthenticatedSource", false);
        assertThat(capabilities.toString())
                .doesNotContain("ca.internal", "credential=secret",
                        "https://ca.internal/events");
    }

    private static ToolStudioIntegrationService service() {
        return new ToolStudioIntegrationService(null, null, null, null);
    }

    private static ControlPlaneCertificateRotationRuntime.Descriptor descriptor(
            boolean enabled,
            boolean ready,
            boolean trustAvailable,
            int inventoried,
            int registered,
            boolean synchronizedState) {
        return new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                enabled, ready, trustAvailable, true, inventoried, registered,
                synchronizedState);
    }
}
