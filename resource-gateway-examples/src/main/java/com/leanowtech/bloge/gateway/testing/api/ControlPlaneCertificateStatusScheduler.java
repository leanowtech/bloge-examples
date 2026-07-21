package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Fixed-delay Spring trigger for the bounded certificate-status monitor. */
public final class ControlPlaneCertificateStatusScheduler {

    private final ControlPlaneCertificateStatusMonitor monitor;

    /** @param monitor bounded durable-to-local status refresh pipeline */
    public ControlPlaneCertificateStatusScheduler(ControlPlaneCertificateStatusMonitor monitor) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    /** Advances at most the configured batch and leaves every failure as bounded monitor state. */
    @Scheduled(
            initialDelayString = "${gateway.testing.control-plane-certificate-status.initial-delay-millis:1000}",
            fixedDelayString = "${gateway.testing.control-plane-certificate-status.refresh-delay-millis:30000}")
    public void refresh() {
        monitor.refresh();
    }
}
