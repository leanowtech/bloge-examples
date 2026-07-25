package com.leanowtech.bloge.gateway.integration.mirror;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality gauges for batch evidence-finalization health.
 *
 * <p>Only closed states, closed failure classes, counts, and durations are registered. Region,
 * environment, tenant, project, job, provider, failure text, and evidence coordinates never
 * become metric labels.</p>
 */
public final class ScenarioRehearsalBatchFinalizationHealthTelemetry {
    private static final String PREFIX =
            "resource.gateway.mirror.scenario.batch.finalization.";

    private final Map<ScenarioRehearsalBatchRepository.FinalizationState,
            AtomicLong> stateCounts;
    private final Map<FailureClass, AtomicLong> failureCounts;
    private final Map<ScenarioRehearsalBatchFinalizationHealth.State,
            AtomicLong> healthStates;
    private final AtomicLong eligible;
    private final AtomicLong staleSigning;
    private final AtomicLong inconsistentRecords;
    private final AtomicLong policyMismatches;
    private final AtomicLong oldestEligibleAgeMillis;
    private final AtomicLong oldestQuarantinedAgeMillis;
    private final AtomicLong oldestActiveSigningAgeMillis;

    /**
     * Registers the complete closed metric vocabulary.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public ScenarioRehearsalBatchFinalizationHealthTelemetry(
            MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(
                registry, "registry");
        EnumMap<ScenarioRehearsalBatchRepository.FinalizationState,
                AtomicLong> states = new EnumMap<>(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.class);
        for (ScenarioRehearsalBatchRepository.FinalizationState state
                : ScenarioRehearsalBatchRepository
                .FinalizationState.values()) {
            states.put(
                    state,
                    gauge(
                            meters,
                            PREFIX + "states",
                            "state",
                            metric(state)));
        }
        stateCounts = Map.copyOf(states);
        EnumMap<FailureClass, AtomicLong> failures =
                new EnumMap<>(FailureClass.class);
        for (FailureClass failure : FailureClass.values()) {
            failures.put(
                    failure,
                    gauge(
                            meters,
                            PREFIX + "failures",
                            "reason",
                            metric(failure)));
        }
        failureCounts = Map.copyOf(failures);
        EnumMap<ScenarioRehearsalBatchFinalizationHealth.State,
                AtomicLong> health = new EnumMap<>(
                ScenarioRehearsalBatchFinalizationHealth.State.class);
        for (ScenarioRehearsalBatchFinalizationHealth.State state
                : ScenarioRehearsalBatchFinalizationHealth.State
                .values()) {
            health.put(
                    state,
                    gauge(
                            meters,
                            PREFIX + "health",
                            "state",
                            metric(state)));
        }
        healthStates = Map.copyOf(health);
        eligible = gauge(meters, PREFIX + "eligible");
        staleSigning = gauge(
                meters, PREFIX + "stale.signing");
        inconsistentRecords = gauge(
                meters, PREFIX + "inconsistent.records");
        policyMismatches = gauge(
                meters, PREFIX + "policy.mismatches");
        oldestEligibleAgeMillis = gauge(
                meters, PREFIX + "oldest.eligible.age.millis");
        oldestQuarantinedAgeMillis = gauge(
                meters, PREFIX + "oldest.quarantined.age.millis");
        oldestActiveSigningAgeMillis = gauge(
                meters,
                PREFIX + "oldest.active.signing.age.millis");
    }

    /** Replaces every gauge from one database-clock assessment. */
    public void observe(
            ScenarioRehearsalBatchFinalizationHealth.Assessment
                    assessment) {
        ScenarioRehearsalBatchFinalizationHealth.Assessment exact =
                Objects.requireNonNull(assessment, "assessment");
        ScenarioRehearsalBatchFinalizationHealth.Counts counts =
                exact.counts();
        stateCounts.get(
                ScenarioRehearsalBatchRepository.FinalizationState
                        .PENDING).set(counts.pending());
        stateCounts.get(
                ScenarioRehearsalBatchRepository.FinalizationState
                        .SIGNING).set(counts.signing());
        stateCounts.get(
                ScenarioRehearsalBatchRepository.FinalizationState
                        .RETRY_WAIT).set(counts.retryWait());
        stateCounts.get(
                ScenarioRehearsalBatchRepository.FinalizationState
                        .QUARANTINED).set(counts.quarantined());
        stateCounts.get(
                ScenarioRehearsalBatchRepository.FinalizationState
                        .FINALIZED).set(counts.finalized());
        failureCounts.get(FailureClass.SIGNER_UNAVAILABLE)
                .set(counts.signerUnavailable());
        failureCounts.get(FailureClass.SIGNATURE_INVALID)
                .set(counts.signatureInvalid());
        failureCounts.get(FailureClass.MATERIAL_INVALID)
                .set(counts.materialInvalid());
        failureCounts.get(FailureClass.CONTROL_UNAVAILABLE)
                .set(counts.controlUnavailable());
        eligible.set(counts.eligible());
        staleSigning.set(counts.staleSigning());
        inconsistentRecords.set(counts.inconsistentRecords());
        policyMismatches.set(counts.policyMismatches());
        oldestEligibleAgeMillis.set(
                exact.ages().oldestEligibleAgeMillis());
        oldestQuarantinedAgeMillis.set(
                exact.ages().oldestQuarantinedAgeMillis());
        oldestActiveSigningAgeMillis.set(
                exact.ages().oldestActiveSigningAgeMillis());
        healthStates.forEach((state, value) ->
                value.set(state == exact.state() ? 1 : 0));
    }

    private static AtomicLong gauge(
            MeterRegistry registry,
            String name) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .register(registry);
        return value;
    }

    private static AtomicLong gauge(
            MeterRegistry registry,
            String name,
            String tag,
            String tagValue) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tag(tag, tagValue)
                .register(registry);
        return value;
    }

    private static String metric(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Closed finalization failure classes safe for metric labels. */
    private enum FailureClass {
        SIGNER_UNAVAILABLE,
        SIGNATURE_INVALID,
        MATERIAL_INVALID,
        CONTROL_UNAVAILABLE
    }
}
