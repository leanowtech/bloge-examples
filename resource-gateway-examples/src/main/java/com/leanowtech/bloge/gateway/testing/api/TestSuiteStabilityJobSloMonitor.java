package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Database-clock backlog and lease readiness for the durable suite-stability job queue. */
public final class TestSuiteStabilityJobSloMonitor implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityJobSloMonitor.class);

    private final TestSuiteStabilityJobRepository repository;
    private final TestSuiteStabilityJobTelemetry telemetry;
    private final List<String> environments;
    private final Policy policy;
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * @param repository database-authoritative queue observation source
     * @param telemetry fixed-cardinality metric adapter
     * @param environments exact test and/or staging queues assessed by this deployment
     * @param policy queue depth, age, and stale-owner limits
     */
    public TestSuiteStabilityJobSloMonitor(
            TestSuiteStabilityJobRepository repository,
            TestSuiteStabilityJobTelemetry telemetry,
            Set<String> environments,
            Policy policy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.environments = environments(environments);
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Refreshes every enabled queue independently and fails aggregate readiness closed. */
    @Scheduled(fixedDelayString =
            "${gateway.testing.stability-jobs.slo.observation-interval-ms:30000}")
    public void refresh() {
        LinkedHashMap<String, EnvironmentAssessment> observations = new LinkedHashMap<>();
        State aggregate = State.HEALTHY;
        for (String environment : environments) {
            EnvironmentAssessment assessment;
            try {
                TestSuiteStabilityQueueSnapshot snapshot = repository.observe(environment);
                assessment = assess(snapshot);
                observeTelemetry(environment, snapshot, assessment);
            } catch (RuntimeException unavailable) {
                assessment = EnvironmentAssessment.storeUnavailable();
                observeStoreUnavailable(environment);
                log.warn("Suite-stability queue SLO observation failed for a bounded environment; "
                        + "health is fail-closed until a database snapshot succeeds");
            }
            observations.put(environment, assessment);
            aggregate = worst(aggregate, assessment.state());
        }
        latest.set(new Assessment(aggregate, Map.copyOf(observations)));
    }

    /** Returns aggregate readiness and payload-free per-environment facts. */
    @Override
    public Health health() {
        Assessment assessment = latest.get();
        if (assessment == null) {
            refresh();
            assessment = latest.get();
        }
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        assessment.environments().forEach((environment, value) ->
                details.put(environment, environmentDetails(value)));
        return Health.status(status(assessment.state()))
                .withDetail("state", assessment.state().name())
                .withDetail("environments", Map.copyOf(details))
                .build();
    }

    private EnvironmentAssessment assess(TestSuiteStabilityQueueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long queued = snapshot.totals().get(TestSuiteStabilityJobRecord.Status.QUEUED);
        Duration oldestAge = age(snapshot.oldestQueuedAt(), snapshot.observedAt());
        List<String> violations = new ArrayList<>();
        if (queued > policy.maximumQueuedJobs()) {
            violations.add(Violation.QUEUE_DEPTH_EXCEEDED.name());
        }
        if (oldestAge != null && oldestAge.compareTo(policy.maximumOldestQueuedAge()) > 0) {
            violations.add(Violation.QUEUE_BACKLOG_STALE.name());
        }
        if (snapshot.expiredLiveLeases() > policy.maximumExpiredLiveLeases()) {
            violations.add(Violation.EXPIRED_LIVE_LEASE_BACKLOG.name());
        }
        State state = violations.isEmpty() ? State.HEALTHY : State.SLO_VIOLATED;
        return new EnvironmentAssessment(
                state, List.copyOf(violations), snapshot, oldestAge);
    }

    private void observeTelemetry(
            String environment,
            TestSuiteStabilityQueueSnapshot snapshot,
            EnvironmentAssessment assessment) {
        try {
            telemetry.observe(environment, snapshot, assessment.state(),
                    assessment.oldestQueuedAge());
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability queue telemetry refresh failed");
        }
    }

    private void observeStoreUnavailable(String environment) {
        try {
            telemetry.observeStoreUnavailable(environment);
        } catch (RuntimeException telemetryUnavailable) {
            log.warn("Suite-stability queue store-unavailable telemetry failed");
        }
    }

    private static Map<String, Object> environmentDetails(EnvironmentAssessment assessment) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("state", assessment.state().name());
        details.put("violations", assessment.violations());
        if (assessment.snapshot() != null) {
            TestSuiteStabilityQueueSnapshot snapshot = assessment.snapshot();
            details.put("observedAt", snapshot.observedAt().toString());
            details.put("statusTotals", snapshot.totals());
            details.put("oldestQueuedAgeSeconds",
                    assessment.oldestQueuedAge() == null
                            ? -1 : assessment.oldestQueuedAge().toSeconds());
            details.put("expiredLiveLeases", snapshot.expiredLiveLeases());
            details.put("distinctQueuedTenants", snapshot.distinctQueuedTenants());
        }
        return Map.copyOf(details);
    }

    private static Duration age(Instant earlier, Instant observedAt) {
        if (earlier == null) {
            return null;
        }
        Duration age = Duration.between(earlier, observedAt);
        return age.isNegative() ? Duration.ZERO : age;
    }

    private static State worst(State left, State right) {
        if (left == State.STORE_UNAVAILABLE || right == State.STORE_UNAVAILABLE) {
            return State.STORE_UNAVAILABLE;
        }
        return left == State.SLO_VIOLATED || right == State.SLO_VIOLATED
                ? State.SLO_VIOLATED : State.HEALTHY;
    }

    private static Status status(State state) {
        return switch (state) {
            case HEALTHY -> Status.UP;
            case SLO_VIOLATED -> Status.OUT_OF_SERVICE;
            case STORE_UNAVAILABLE -> Status.DOWN;
        };
    }

    private static List<String> environments(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty() || !Set.of("test", "staging").containsAll(normalized)) {
            throw new IllegalArgumentException(
                    "Stability queue SLO environments must be test and/or staging");
        }
        return List.copyOf(normalized);
    }

    /** Closed readiness states suitable for Actuator and metric policy. */
    public enum State {
        /** Queue depth, age, and expired leases satisfy policy. */
        HEALTHY,
        /** At least one observed queue exceeds a configured SLO threshold. */
        SLO_VIOLATED,
        /** At least one queue could not produce a trustworthy database observation. */
        STORE_UNAVAILABLE
    }

    /** Stable queue SLO failures; business test outcomes are deliberately excluded. */
    public enum Violation {
        /** Queued work exceeds the deployment's readiness threshold. */
        QUEUE_DEPTH_EXCEEDED,
        /** The oldest queued job has waited longer than policy. */
        QUEUE_BACKLOG_STALE,
        /** Too many live-state rows await stale-owner reconciliation. */
        EXPIRED_LIVE_LEASE_BACKLOG,
        /** Queue storage could not produce a trustworthy aggregate observation. */
        QUEUE_STORE_UNAVAILABLE
    }

    /**
     * @param observationInterval configured monitor schedule interval
     * @param maximumQueuedJobs largest readiness-safe queued depth per environment
     * @param maximumOldestQueuedAge oldest readiness-safe queued job age
     * @param maximumExpiredLiveLeases largest tolerated stale-owner backlog
     */
    public record Policy(
            Duration observationInterval,
            long maximumQueuedJobs,
            Duration maximumOldestQueuedAge,
            long maximumExpiredLiveLeases) {
        /** Validates finite operational limits at application startup. */
        public Policy {
            observationInterval = bounded(observationInterval, "observationInterval",
                    Duration.ofSeconds(1), Duration.ofDays(30));
            maximumOldestQueuedAge = bounded(maximumOldestQueuedAge,
                    "maximumOldestQueuedAge", Duration.ofSeconds(1), Duration.ofDays(30));
            if (maximumQueuedJobs < 0 || maximumQueuedJobs > 100_000
                    || maximumExpiredLiveLeases < 0
                    || maximumExpiredLiveLeases > 100_000) {
                throw new IllegalArgumentException(
                        "Stability queue SLO counts must be between 0 and 100000");
            }
        }

        private static Duration bounded(
                Duration value, String name, Duration minimum, Duration maximum) {
            Duration result = Objects.requireNonNull(value, name);
            if (result.compareTo(minimum) < 0 || result.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(name + " is outside the bounded SLO policy");
            }
            return result;
        }
    }

    private record Assessment(
            State state,
            Map<String, EnvironmentAssessment> environments) {
    }

    private record EnvironmentAssessment(
            State state,
            List<String> violations,
            TestSuiteStabilityQueueSnapshot snapshot,
            Duration oldestQueuedAge) {
        private static EnvironmentAssessment storeUnavailable() {
            return new EnvironmentAssessment(State.STORE_UNAVAILABLE,
                    List.of(Violation.QUEUE_STORE_UNAVAILABLE.name()), null, null);
        }
    }
}
