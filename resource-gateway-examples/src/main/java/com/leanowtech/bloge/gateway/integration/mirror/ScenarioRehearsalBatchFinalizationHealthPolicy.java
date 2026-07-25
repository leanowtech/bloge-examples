package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.util.Objects;

/**
 * Server-owned thresholds for Scenario batch evidence-finalization health.
 *
 * @param observationInterval fixed monitor refresh interval
 * @param maximumEligibleBacklog largest immediately actionable backlog
 * @param maximumOldestEligibleAge oldest acceptable actionable intent age
 * @param maximumActiveSigningAge warning threshold for a live KMS claim
 * @param maximumQuarantinedBacklog quarantine count before degradation
 * @param criticalQuarantinedBacklog quarantine count that fails readiness
 * @param maximumSignerUnavailableBacklog current signer failures before readiness failure
 * @param maximumControlUnavailableBacklog current control failures before readiness failure
 */
public record ScenarioRehearsalBatchFinalizationHealthPolicy(
        Duration observationInterval,
        long maximumEligibleBacklog,
        Duration maximumOldestEligibleAge,
        Duration maximumActiveSigningAge,
        long maximumQuarantinedBacklog,
        long criticalQuarantinedBacklog,
        long maximumSignerUnavailableBacklog,
        long maximumControlUnavailableBacklog
) {
    /** Conservative defaults for a separately scaled single-lane KMS scheduler. */
    public static ScenarioRehearsalBatchFinalizationHealthPolicy defaults() {
        return new ScenarioRehearsalBatchFinalizationHealthPolicy(
                Duration.ofSeconds(30),
                100,
                Duration.ofMinutes(5),
                Duration.ofSeconds(90),
                0,
                100,
                10,
                10);
    }

    /** Rejects unbounded monitoring or contradictory warning and critical thresholds. */
    public ScenarioRehearsalBatchFinalizationHealthPolicy {
        observationInterval = bounded(
                observationInterval,
                "observationInterval",
                Duration.ofSeconds(1),
                Duration.ofMinutes(10));
        maximumOldestEligibleAge = bounded(
                maximumOldestEligibleAge,
                "maximumOldestEligibleAge",
                Duration.ofSeconds(1),
                Duration.ofDays(7));
        maximumActiveSigningAge = bounded(
                maximumActiveSigningAge,
                "maximumActiveSigningAge",
                Duration.ofSeconds(1),
                Duration.ofHours(1));
        if (maximumEligibleBacklog < 0
                || maximumQuarantinedBacklog < 0
                || criticalQuarantinedBacklog
                <= maximumQuarantinedBacklog
                || maximumSignerUnavailableBacklog < 0
                || maximumControlUnavailableBacklog < 0) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization health limits are invalid");
        }
    }

    private static Duration bounded(
            Duration value,
            String field,
            Duration minimum,
            Duration maximum) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.compareTo(minimum) < 0
                || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the supported health bound");
        }
        return exact;
    }
}
