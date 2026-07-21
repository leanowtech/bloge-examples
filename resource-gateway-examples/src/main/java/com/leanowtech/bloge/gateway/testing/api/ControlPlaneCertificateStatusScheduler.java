package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Fixed-delay Spring trigger for the bounded certificate-status monitor. */
public final class ControlPlaneCertificateStatusScheduler {

    private final ControlPlaneCertificateStatusMonitor monitor;
    private final ControlPlaneCertificateStatusSloMonitor sloMonitor;

    /** @param monitor bounded durable-to-local status refresh pipeline */
    public ControlPlaneCertificateStatusScheduler(ControlPlaneCertificateStatusMonitor monitor) {
        this(monitor, null);
    }

    /**
     * Creates a refresh trigger that publishes SLO truth after each bounded source cycle.
     *
     * @param monitor durable-to-local status refresh pipeline
     * @param sloMonitor local SLO assessor, nullable only for legacy isolated construction
     */
    public ControlPlaneCertificateStatusScheduler(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusSloMonitor sloMonitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.sloMonitor = sloMonitor;
    }

    /** Advances at most the configured batch and leaves every failure as bounded monitor state. */
    @Scheduled(
            initialDelayString = "${gateway.testing.control-plane-certificate-status.initial-delay-millis:1000}",
            fixedDelayString = "${gateway.testing.control-plane-certificate-status.refresh-delay-millis:30000}")
    public void refresh() {
        monitor.refresh();
        if (sloMonitor != null) {
            sloMonitor.assess();
        }
    }
}
