package com.leanowtech.bloge.gateway.integration;

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
