package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ControlPlaneCertificateStatusSchedulerTest {

    @Test
    void refreshesDurableStateBeforePublishingServiceLevelTruth() {
        ControlPlaneCertificateStatusMonitor monitor = mock(
                ControlPlaneCertificateStatusMonitor.class);
        ControlPlaneCertificateStatusSloMonitor slo = mock(
                ControlPlaneCertificateStatusSloMonitor.class);
        var scheduler = new ControlPlaneCertificateStatusScheduler(monitor, slo);

        scheduler.refresh();

        InOrder ordered = inOrder(monitor, slo);
        ordered.verify(monitor).refresh();
        ordered.verify(slo).assess();
        ordered.verifyNoMoreInteractions();
    }

    @Test
    void legacyIsolatedConstructionDoesNotRequireServiceLevelTelemetry() {
        var scheduler = new ControlPlaneCertificateStatusScheduler(
                mock(ControlPlaneCertificateStatusMonitor.class));

        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
    }
}
