package com.leanowtech.bloge.gateway.integration.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed deployment-partition SLO monitor for batch evidence finalization.
 *
 * <p>The monitor reads only the server-owned region and environment configured for this process.
 * Health details and metrics are aggregate and payload-free. The protected enterprise API uses a
 * separate exact-scope query so this cross-tenant deployment view can never escape through it.</p>
 */
public final class ScenarioRehearsalBatchFinalizationSloMonitor
        implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(
            ScenarioRehearsalBatchFinalizationSloMonitor.class);

    private final ScenarioRehearsalBatchRepository repository;
    private final ScenarioRehearsalBatchFinalizationHealthTelemetry
            telemetry;
    private final ScenarioRehearsalBatchFinalizationHealthPolicy
            policy;
    private final String region;
    private final String environmentId;
    private final AtomicReference<
            ScenarioRehearsalBatchFinalizationHealth.Assessment>
            latest = new AtomicReference<>();

    /**
     * Creates one monitor for the process-local finalization scheduler partition.
     *
     * @param repository database-clock aggregate authority
     * @param telemetry fixed-cardinality metric adapter
     * @param policy server-owned health thresholds
     * @param region scheduler regional partition
     * @param environmentId scheduler non-production environment partition
     */
    public ScenarioRehearsalBatchFinalizationSloMonitor(
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchFinalizationHealthTelemetry
                    telemetry,
            ScenarioRehearsalBatchFinalizationHealthPolicy policy,
            String region,
            String environmentId) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.telemetry = Objects.requireNonNull(
                telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.region = required(region, "region");
        this.environmentId = required(
                environmentId, "environmentId");
    }

    /** Refreshes health and metrics from one database-clock aggregate. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.mirror.scenario-batch.finalization-slo.observation-interval-millis:30000}")
    public void refresh() {
        ScenarioRehearsalBatchFinalizationHealth.Assessment
                assessment;
        try {
            assessment =
                    ScenarioRehearsalBatchFinalizationHealth
                            .assess(
                                    repository
                                            .finalizationPartitionHealth(
                                                    region,
                                                    environmentId),
                                    policy);
        } catch (RuntimeException unavailable) {
            assessment =
                    ScenarioRehearsalBatchFinalizationHealth
                            .unavailable();
            log.warn("Scenario batch finalization health observation failed; readiness is fail-closed");
        }
        latest.set(assessment);
        try {
            telemetry.observe(assessment);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Scenario batch finalization health telemetry refresh failed");
        }
    }

    /** Returns the latest assessment and observes immediately on first access. */
    public ScenarioRehearsalBatchFinalizationHealth.Assessment
    assessment() {
        ScenarioRehearsalBatchFinalizationHealth.Assessment
                current = latest.get();
        if (current == null) {
            refresh();
            current = latest.get();
        }
        return current;
    }

    /** Returns the payload-free Actuator view for readiness and alert routing. */
    @Override
    public Health health() {
        ScenarioRehearsalBatchFinalizationHealth.Assessment
                current = assessment();
        return Health.status(status(current.state()))
                .withDetail("state", current.state().name())
                .withDetail("violations", current.violations())
                .withDetail("counts", current.counts())
                .withDetail("ages", current.ages())
                .build();
    }

    private static Status status(
            ScenarioRehearsalBatchFinalizationHealth.State state) {
        return switch (state) {
            case HEALTHY, DEGRADED -> Status.UP;
            case CRITICAL -> Status.OUT_OF_SERVICE;
            case UNAVAILABLE -> Status.DOWN;
        };
    }

    private static String required(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
