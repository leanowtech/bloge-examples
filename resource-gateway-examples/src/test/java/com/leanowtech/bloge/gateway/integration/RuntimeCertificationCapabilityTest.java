package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeCertificationCapabilityTest {
    @Test
    void protocolSupportNeverPretendsThatDestructiveExecutionIsInstalled() {
        IntegrationCapabilities capabilities = IntegrationCapabilities.current();

        assertThat(capabilities.features())
                .containsEntry("mirrorRuntimeCertificationProtocol", true)
                .containsEntry("mirrorRuntimeCertificationPlanReady", false)
                .containsEntry("mirrorRuntimeCertificationDurableJournalReady", false)
                .containsEntry("mirrorRuntimeCertificationExecutionReady", false);
        assertThat(capabilities.supportedObjects())
                .containsKeys("runtimeCertificationManifest",
                        "runtimeCertificationExecutionAuthorization",
                        "runtimeCertificationReport");
    }

    @Test
    void runtimeMarkerRechecksEveryIndependentDependency() {
        AtomicBoolean adapter = new AtomicBoolean(true);
        AtomicBoolean journal = new AtomicBoolean(true);
        AtomicBoolean authorization = new AtomicBoolean(true);
        AtomicBoolean report = new AtomicBoolean(true);
        RuntimeCertificationRuntimeAvailability availability =
                new RuntimeCertificationRuntimeAvailability(true, true,
                        adapter::get, journal::get, authorization::get, report::get);

        assertThat(availability.planReady()).isTrue();
        assertThat(availability.journalReady()).isTrue();
        assertThat(availability.executionReady()).isTrue();
        authorization.set(false);
        assertThat(availability.executionReady()).isFalse();
        adapter.set(false);
        assertThat(availability.planReady()).isFalse();
        assertThat(availability.executionReady()).isFalse();
    }
}
