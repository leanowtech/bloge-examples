package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchFinalizationHealthPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Strict deployment configuration for batch evidence-finalization health.
 */
@ConfigurationProperties(
        prefix = ScenarioRehearsalBatchFinalizationSloProperties.PREFIX,
        ignoreUnknownFields = false)
public record ScenarioRehearsalBatchFinalizationSloProperties(
        Long observationIntervalMillis,
        Long maximumEligibleBacklog,
        Long maximumOldestEligibleAgeSeconds,
        Long maximumActiveSigningAgeSeconds,
        Long maximumQuarantinedBacklog,
        Long criticalQuarantinedBacklog,
        Long maximumSignerUnavailableBacklog,
        Long maximumControlUnavailableBacklog
) {
    /** Configuration prefix shared by manifests and capability documentation. */
    public static final String PREFIX =
            "gateway.testing.mirror.scenario-batch.finalization-slo";

    /** Applies conservative defaults and validates the resulting policy. */
    public ScenarioRehearsalBatchFinalizationSloProperties {
        ScenarioRehearsalBatchFinalizationHealthPolicy defaults =
                ScenarioRehearsalBatchFinalizationHealthPolicy.defaults();
        observationIntervalMillis = value(
                observationIntervalMillis,
                defaults.observationInterval().toMillis());
        maximumEligibleBacklog = value(
                maximumEligibleBacklog,
                defaults.maximumEligibleBacklog());
        maximumOldestEligibleAgeSeconds = value(
                maximumOldestEligibleAgeSeconds,
                defaults.maximumOldestEligibleAge().toSeconds());
        maximumActiveSigningAgeSeconds = value(
                maximumActiveSigningAgeSeconds,
                defaults.maximumActiveSigningAge().toSeconds());
        maximumQuarantinedBacklog = value(
                maximumQuarantinedBacklog,
                defaults.maximumQuarantinedBacklog());
        criticalQuarantinedBacklog = value(
                criticalQuarantinedBacklog,
                defaults.criticalQuarantinedBacklog());
        maximumSignerUnavailableBacklog = value(
                maximumSignerUnavailableBacklog,
                defaults.maximumSignerUnavailableBacklog());
        maximumControlUnavailableBacklog = value(
                maximumControlUnavailableBacklog,
                defaults.maximumControlUnavailableBacklog());
        policy(
                observationIntervalMillis,
                maximumEligibleBacklog,
                maximumOldestEligibleAgeSeconds,
                maximumActiveSigningAgeSeconds,
                maximumQuarantinedBacklog,
                criticalQuarantinedBacklog,
                maximumSignerUnavailableBacklog,
                maximumControlUnavailableBacklog);
    }

    /** @return validated server-owned health policy */
    public ScenarioRehearsalBatchFinalizationHealthPolicy policy() {
        return policy(
                observationIntervalMillis,
                maximumEligibleBacklog,
                maximumOldestEligibleAgeSeconds,
                maximumActiveSigningAgeSeconds,
                maximumQuarantinedBacklog,
                criticalQuarantinedBacklog,
                maximumSignerUnavailableBacklog,
                maximumControlUnavailableBacklog);
    }

    private static long value(Long configured, long fallback) {
        return configured == null ? fallback : configured;
    }

    private static ScenarioRehearsalBatchFinalizationHealthPolicy policy(
            long observationIntervalMillis,
            long maximumEligibleBacklog,
            long maximumOldestEligibleAgeSeconds,
            long maximumActiveSigningAgeSeconds,
            long maximumQuarantinedBacklog,
            long criticalQuarantinedBacklog,
            long maximumSignerUnavailableBacklog,
            long maximumControlUnavailableBacklog) {
        return new ScenarioRehearsalBatchFinalizationHealthPolicy(
                Duration.ofMillis(observationIntervalMillis),
                maximumEligibleBacklog,
                Duration.ofSeconds(maximumOldestEligibleAgeSeconds),
                Duration.ofSeconds(maximumActiveSigningAgeSeconds),
                maximumQuarantinedBacklog,
                criticalQuarantinedBacklog,
                maximumSignerUnavailableBacklog,
                maximumControlUnavailableBacklog);
    }
}
