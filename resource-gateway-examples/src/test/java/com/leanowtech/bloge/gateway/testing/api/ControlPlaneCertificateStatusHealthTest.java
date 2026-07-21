package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneCertificateStatusHealthTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Test
    void strictFreshPipelineReportsFixedCardinalityReadyHealth() {
        var health = health(source(true), admission(true), trust(true)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("schemaVersion", ControlPlaneCertificateStatusHealth.SCHEMA_VERSION)
                .containsEntry("runtimeStatus", "READY")
                .containsEntry("monitorStatus", "CURRENT")
                .containsEntry("trustAvailable", true)
                .containsEntry("strictSourceAvailable", true)
                .containsEntry("durableFloorIntegrated", true)
                .containsEntry("admissionFresh", true)
                .containsEntry("productionReady", false)
                .hasSize(16);
    }

    @Test
    void staleAdmissionIsExplicitEvenWhenLastMonitorRefreshWasCurrent() {
        var health = health(source(true), admission(false), trust(true)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "ADMISSION_STALE")
                .containsEntry("monitorStatus", "CURRENT")
                .containsEntry("admissionFresh", false)
                .containsEntry("productionReady", false);
    }

    @Test
    void trustAndSourceSecurityFailuresRemainDistinctAndMaterialFree() {
        var trustFailure = health(source(true), admission(true), trust(false)).health();
        var sourceFailure = health(source(false), admission(true), trust(true)).health();

        assertThat(trustFailure.getDetails())
                .containsEntry("runtimeStatus", "TRUST_UNAVAILABLE");
        assertThat(sourceFailure.getDetails())
                .containsEntry("runtimeStatus", "SOURCE_SECURITY_UNAVAILABLE")
                .containsEntry("sourcePrivateTrust", false)
                .containsEntry("sourceSpkiPinned", false);
        assertThat(sourceFailure.getDetails().toString())
                .doesNotContain("password", "secretRef", "certificateFingerprint");
    }

    @Test
    void descriptorFailureReturnsTheSameFieldSetWithoutProviderDiagnostics() {
        ControlPlaneCertificateStatusSource source = mock(
                ControlPlaneCertificateStatusSource.class);
        when(source.descriptor()).thenThrow(
                new IllegalStateException("vault://status-client-password"));

        var health = health(source, admission(true), trust(true)).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("runtimeStatus", "UNAVAILABLE")
                .containsEntry("monitorStatus", "UNAVAILABLE")
                .containsKeys("sourcePrivateTrust", "sourceSpkiPinned", "sourceMutualTls",
                        "sourceCertificateIdentityBound", "targetCount", "goodTargetCount",
                        "revokedTargetCount", "unknownTargetCount", "productionReady")
                .hasSize(16);
        assertThat(health.getDetails().toString())
                .doesNotContain("vault://", "status-client-password");
    }

    private static ControlPlaneCertificateStatusHealth health(
            ControlPlaneCertificateStatusSource source,
            ControlPlaneCertificateStatusAdmission.Descriptor admission,
            ControlPlaneCertificateStatusTrustStore.Descriptor trust) {
        ControlPlaneCertificateStatusMonitor monitor = mock(
                ControlPlaneCertificateStatusMonitor.class);
        when(monitor.descriptor()).thenReturn(new ControlPlaneCertificateStatusMonitor.Descriptor(
                ControlPlaneCertificateStatusMonitor.Descriptor.SCHEMA_VERSION,
                ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT,
                true, true, admission.fresh(), 1, 0, NOW, NOW.plusSeconds(60)));
        ControlPlaneCertificateStatusAdmission admissionCache = mock(
                ControlPlaneCertificateStatusAdmission.class);
        when(admissionCache.descriptor()).thenReturn(admission);
        ControlPlaneCertificateStatusTrustStore trustStore = mock(
                ControlPlaneCertificateStatusTrustStore.class);
        when(trustStore.descriptor()).thenReturn(trust);
        return new ControlPlaneCertificateStatusHealth(
                monitor, source, trustStore, admissionCache);
    }

    private static ControlPlaneCertificateStatusSource source(boolean available) {
        ControlPlaneCertificateStatusSource source = mock(
                ControlPlaneCertificateStatusSource.class);
        when(source.descriptor()).thenReturn(available
                ? new ControlPlaneCertificateStatusSource.Descriptor(
                ControlPlaneCertificateStatusSource.Descriptor.SCHEMA_VERSION,
                true, true, true, true, true, true)
                : ControlPlaneCertificateStatusSource.Descriptor.unavailable());
        return source;
    }

    private static ControlPlaneCertificateStatusAdmission.Descriptor admission(boolean fresh) {
        return new ControlPlaneCertificateStatusAdmission.Descriptor(
                ControlPlaneCertificateStatusAdmission.Descriptor.SCHEMA_VERSION,
                true, fresh, 1, 1, 1, 0, 0, fresh ? 60 : 0,
                fresh ? "FRESH" : "STALE");
    }

    private static ControlPlaneCertificateStatusTrustStore.Descriptor trust(boolean available) {
        return available
                ? new ControlPlaneCertificateStatusTrustStore.Descriptor("", true,
                "enterprise-ca", 1, 1, 1, 1,
                Map.of("privateMaterialPresent", false))
                : ControlPlaneCertificateStatusTrustStore.unavailable().descriptor();
    }
}
