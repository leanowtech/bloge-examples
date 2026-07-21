package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fail-closed local SLO assessment for certificate-status refresh and request admission.
 *
 * <p>Instantaneous request admission and operational SLO are intentionally distinct. A verified
 * cache may continue serving during a short source outage, while this monitor reports the outage
 * and stale refresh progress for alerting. Assessments use only fixed-cardinality descriptors and
 * cumulative telemetry; they never inspect certificate, target, authority, endpoint, or payload
 * material.</p>
 */
public final class ControlPlaneCertificateStatusSloMonitor implements HealthIndicator {

    private final ControlPlaneCertificateStatusMonitor monitor;
    private final ControlPlaneCertificateStatusAdmission admission;
    private final ControlPlaneCertificateStatusTelemetry telemetry;
    private final Clock clock;
    private final Instant startedAt;
    private final Policy policy;
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates one fixed-policy local assessor.
     *
     * @param monitor bounded source and durable-floor refresh pipeline
     * @param admission local hard-expiry request cache
     * @param telemetry cumulative fixed-cardinality observations
     * @param clock local SLO observation clock
     * @param policy alert and maturity thresholds
     */
    public ControlPlaneCertificateStatusSloMonitor(
            ControlPlaneCertificateStatusMonitor monitor,
            ControlPlaneCertificateStatusAdmission admission,
            ControlPlaneCertificateStatusTelemetry telemetry,
            Clock clock,
            Policy policy) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
        startedAt = clock.instant();
        latest.set(Assessment.initializing(startedAt, policy.descriptor()));
    }

    /** Evaluates current descriptors and cumulative observations, then publishes telemetry. */
    public Assessment assess() {
        Assessment assessment;
        try {
            assessment = assess(monitor.descriptor(), admission.descriptor(),
                    telemetry.snapshot(), clock.instant());
        } catch (RuntimeException unavailable) {
            assessment = Assessment.unavailable(clock.instant(), policy.descriptor());
        }
        latest.set(assessment);
        telemetry.observe(assessment);
        return assessment;
    }

    /** @return most recently published assessment without triggering external work */
    public Assessment descriptor() {
        return latest.get();
    }

    /** Returns current SLO health and a fixed versioned detail set. */
    @Override
    public Health health() {
        Assessment assessment = assess();
        Health.Builder builder = switch (assessment.state()) {
            case HEALTHY -> Health.up();
            case INITIALIZING -> Health.unknown();
            case SLO_VIOLATED -> Health.outOfService();
            case OBSERVATION_UNAVAILABLE -> Health.down();
        };
        return builder
                .withDetail("schemaVersion", assessment.schemaVersion())
                .withDetail("state", assessment.state().name())
                .withDetail("violations", assessment.violations().stream()
                        .map(Enum::name).toList())
                .withDetail("monitorStatus", assessment.monitorStatus())
                .withDetail("sourceAvailable", assessment.sourceAvailable())
                .withDetail("admissionFresh", assessment.admissionFresh())
                .withDetail("secondsToExpiry", assessment.secondsToExpiry())
                .withDetail("refreshFailureBasisPoints",
                        assessment.refreshFailureBasisPoints())
                .withDetail("admissionDenialBasisPoints",
                        assessment.admissionDenialBasisPoints())
                .withDetail("consecutiveBatchLimitCycles",
                        assessment.consecutiveBatchLimitCycles())
                .withDetail("lastRefreshSuccessAgeSeconds",
                        assessment.lastRefreshSuccessAgeSeconds())
                .withDetail("productionReady", false)
                .build();
    }

    private Assessment assess(
            ControlPlaneCertificateStatusMonitor.Descriptor watcher,
            ControlPlaneCertificateStatusAdmission.Descriptor cache,
            ControlPlaneCertificateStatusTelemetry.Snapshot counters,
            Instant observedAt) {
        List<Violation> violations = new ArrayList<>();
        Duration startupAge = nonNegativeAge(startedAt, observedAt);
        boolean initializing = !cache.loaded()
                && startupAge.compareTo(policy.startupGrace()) <= 0;

        if (!initializing && !cache.loaded()) {
            violations.add(Violation.NO_PUBLICATION);
        }
        if (cache.loaded() && !cache.fresh()) {
            violations.add(Violation.ADMISSION_STALE);
        }
        if (cache.fresh()
                && cache.secondsToExpiry() < policy.minimumExpiryHeadroom().toSeconds()) {
            violations.add(Violation.EXPIRY_HEADROOM_LOW);
        }
        if (!initializing && !watcher.sourceAvailable()) {
            violations.add(Violation.SOURCE_UNAVAILABLE);
        }

        long successAge = ageSeconds(counters.lastSuccessfulRefreshAt(), observedAt);
        if (!initializing && counters.lastSuccessfulRefreshAt() == null) {
            violations.add(Violation.REFRESH_NEVER_SUCCEEDED);
        } else if (successAge > policy.maximumRefreshSuccessAge().toSeconds()) {
            violations.add(Violation.REFRESH_SUCCESS_STALE);
        }

        int refreshFailureRate = basisPoints(
                counters.refreshFailures(), counters.refreshAttempts());
        if (counters.refreshAttempts() >= policy.minimumRefreshSamples()
                && refreshFailureRate > policy.maximumRefreshFailureBasisPoints()) {
            violations.add(Violation.REFRESH_FAILURE_RATE_EXCEEDED);
        }
        int admissionDenialRate = basisPoints(
                counters.admissionDenials(), counters.admissionChecks());
        if (counters.admissionChecks() >= policy.minimumAdmissionSamples()
                && admissionDenialRate > policy.maximumAdmissionDenialBasisPoints()) {
            violations.add(Violation.ADMISSION_DENIAL_RATE_EXCEEDED);
        }
        if (counters.consecutiveBatchLimitCycles()
                > policy.maximumConsecutiveBatchLimitCycles()) {
            violations.add(Violation.CATCH_UP_BACKLOG);
        }

        List<Violation> canonical = violations.stream().distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        State state = !canonical.isEmpty() ? State.SLO_VIOLATED
                : initializing ? State.INITIALIZING : State.HEALTHY;
        return new Assessment(Assessment.SCHEMA_VERSION, state, canonical, observedAt,
                watcher.status().name(), watcher.sourceAvailable(), cache.fresh(),
                watcher.sequence(), cache.secondsToExpiry(), counters.refreshAttempts(),
                counters.refreshFailures(), refreshFailureRate, counters.admissionChecks(),
                counters.admissionDenials(), admissionDenialRate,
                counters.consecutiveBatchLimitCycles(), successAge, policy.descriptor());
    }

    private static Duration nonNegativeAge(Instant earlier, Instant later) {
        Duration duration = Duration.between(earlier, later);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static long ageSeconds(Instant earlier, Instant later) {
        return earlier == null ? -1L : nonNegativeAge(earlier, later).toSeconds();
    }

    private static int basisPoints(long failures, long attempts) {
        if (attempts == 0L) {
            return 0;
        }
        return BigInteger.valueOf(failures).multiply(BigInteger.valueOf(10_000L))
                .divide(BigInteger.valueOf(attempts)).intValueExact();
    }

    /** Stable SLO states suitable for Actuator and alert routing. */
    public enum State {
        /** Freshness and mature reliability ratios satisfy policy. */
        HEALTHY,
        /** Startup grace remains open before the first durable publication. */
        INITIALIZING,
        /** At least one current or mature reliability threshold is violated. */
        SLO_VIOLATED,
        /** Required descriptors or telemetry could not be observed. */
        OBSERVATION_UNAVAILABLE
    }

    /** Closed machine-readable SLO failure vocabulary. */
    public enum Violation {
        /** Startup grace elapsed without a durable publication. */
        NO_PUBLICATION,
        /** A publication is loaded but no longer fresh. */
        ADMISSION_STALE,
        /** Remaining hard-expiry lifetime is below policy. */
        EXPIRY_HEADROOM_LOW,
        /** The latest source interaction is unavailable or failed before source success. */
        SOURCE_UNAVAILABLE,
        /** No successful refresh occurred before startup grace elapsed. */
        REFRESH_NEVER_SUCCEEDED,
        /** Time since the last successful refresh exceeds policy. */
        REFRESH_SUCCESS_STALE,
        /** Mature refresh failure ratio exceeds policy. */
        REFRESH_FAILURE_RATE_EXCEEDED,
        /** Mature request admission denial ratio exceeds policy. */
        ADMISSION_DENIAL_RATE_EXCEEDED,
        /** Repeated batch-limit cycles indicate the source cannot be caught up in policy. */
        CATCH_UP_BACKLOG,
        /** Descriptor or telemetry observation failed. */
        OBSERVATION_UNAVAILABLE
    }

    /**
     * Bounded SLO policy.
     *
     * @param startupGraceSeconds grace before first-publication failures alert
     * @param maximumRefreshSuccessAgeSeconds maximum age of the last successful source cycle
     * @param minimumExpiryHeadroomSeconds minimum remaining signed admission lifetime
     * @param minimumRefreshSamples samples required before refresh ratio enforcement
     * @param maximumRefreshFailureBasisPoints accepted mature refresh failure ratio
     * @param minimumAdmissionSamples samples required before admission ratio enforcement
     * @param maximumAdmissionDenialBasisPoints accepted mature admission denial ratio
     * @param maximumConsecutiveBatchLimitCycles accepted possible-backlog streak
     */
    public record Policy(
            long startupGraceSeconds,
            long maximumRefreshSuccessAgeSeconds,
            long minimumExpiryHeadroomSeconds,
            int minimumRefreshSamples,
            int maximumRefreshFailureBasisPoints,
            int minimumAdmissionSamples,
            int maximumAdmissionDenialBasisPoints,
            int maximumConsecutiveBatchLimitCycles) {

        /** Validates finite alert thresholds. */
        public Policy {
            validatePolicy(startupGraceSeconds, maximumRefreshSuccessAgeSeconds,
                    minimumExpiryHeadroomSeconds, minimumRefreshSamples,
                    maximumRefreshFailureBasisPoints, minimumAdmissionSamples,
                    maximumAdmissionDenialBasisPoints,
                    maximumConsecutiveBatchLimitCycles);
        }

        /** @return startup grace duration */
        public Duration startupGrace() {
            return Duration.ofSeconds(startupGraceSeconds);
        }

        /** @return maximum accepted refresh success age */
        public Duration maximumRefreshSuccessAge() {
            return Duration.ofSeconds(maximumRefreshSuccessAgeSeconds);
        }

        /** @return minimum accepted admission expiry headroom */
        public Duration minimumExpiryHeadroom() {
            return Duration.ofSeconds(minimumExpiryHeadroomSeconds);
        }

        /** @return fixed public policy projection */
        public PolicyDescriptor descriptor() {
            return new PolicyDescriptor(startupGraceSeconds,
                    maximumRefreshSuccessAgeSeconds, minimumExpiryHeadroomSeconds,
                    minimumRefreshSamples, maximumRefreshFailureBasisPoints,
                    minimumAdmissionSamples, maximumAdmissionDenialBasisPoints,
                    maximumConsecutiveBatchLimitCycles);
        }
    }

    /** Fixed public policy projection embedded into each assessment. */
    public record PolicyDescriptor(
            long startupGraceSeconds,
            long maximumRefreshSuccessAgeSeconds,
            long minimumExpiryHeadroomSeconds,
            int minimumRefreshSamples,
            int maximumRefreshFailureBasisPoints,
            int minimumAdmissionSamples,
            int maximumAdmissionDenialBasisPoints,
            int maximumConsecutiveBatchLimitCycles) {

        /** Rejects descriptors that could not have been emitted by a valid policy. */
        public PolicyDescriptor {
            validatePolicy(startupGraceSeconds, maximumRefreshSuccessAgeSeconds,
                    minimumExpiryHeadroomSeconds, minimumRefreshSamples,
                    maximumRefreshFailureBasisPoints, minimumAdmissionSamples,
                    maximumAdmissionDenialBasisPoints,
                    maximumConsecutiveBatchLimitCycles);
        }
    }

    /**
     * Versioned fixed-cardinality SLO assessment.
     *
     * @param schemaVersion assessment protocol version
     * @param state aggregate SLO state
     * @param violations canonical closed violation list
     * @param observedAt local observation time
     * @param monitorStatus closed monitor status or UNAVAILABLE
     * @param sourceAvailable latest source interaction posture
     * @param admissionFresh current local hard-expiry posture
     * @param sequence latest durable sequence
     * @param secondsToExpiry remaining signed admission lifetime
     * @param refreshAttempts cumulative refresh attempts
     * @param refreshFailures cumulative refresh failures
     * @param refreshFailureBasisPoints mature refresh failure ratio
     * @param admissionChecks cumulative request admission checks
     * @param admissionDenials cumulative denied admission checks
     * @param admissionDenialBasisPoints mature admission denial ratio
     * @param consecutiveBatchLimitCycles current possible-backlog streak
     * @param lastRefreshSuccessAgeSeconds last success age, or -1 when unknown
     * @param policy policy used for this assessment
     */
    public record Assessment(
            String schemaVersion,
            State state,
            List<Violation> violations,
            Instant observedAt,
            String monitorStatus,
            boolean sourceAvailable,
            boolean admissionFresh,
            long sequence,
            long secondsToExpiry,
            long refreshAttempts,
            long refreshFailures,
            int refreshFailureBasisPoints,
            long admissionChecks,
            long admissionDenials,
            int admissionDenialBasisPoints,
            long consecutiveBatchLimitCycles,
            long lastRefreshSuccessAgeSeconds,
            PolicyDescriptor policy) {

        /** Current SLO assessment protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusSloAssessment.v1";

        /** Rejects contradictory or unbounded SLO projections. */
        public Assessment {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            state = Objects.requireNonNull(state, "state");
            List<Violation> supplied = Objects.requireNonNull(violations, "violations");
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            monitorStatus = monitorStatus == null ? "" : monitorStatus.trim();
            policy = Objects.requireNonNull(policy, "policy");
            if (!SCHEMA_VERSION.equals(schemaVersion) || supplied.stream().anyMatch(Objects::isNull)
                    || !supplied.equals(supplied.stream().distinct()
                    .sorted(Comparator.comparingInt(Enum::ordinal)).toList())
                    || !monitorStatus.matches("[A-Z][A-Z0-9_]{0,127}")
                    || sequence < 0 || secondsToExpiry < 0 || secondsToExpiry > 86_400
                    || refreshAttempts < 0 || refreshFailures < 0
                    || refreshFailures > refreshAttempts
                    || refreshFailureBasisPoints < 0 || refreshFailureBasisPoints > 10_000
                    || admissionChecks < 0 || admissionDenials < 0
                    || admissionDenials > admissionChecks
                    || admissionDenialBasisPoints < 0 || admissionDenialBasisPoints > 10_000
                    || consecutiveBatchLimitCycles < 0
                    || lastRefreshSuccessAgeSeconds < -1
                    || refreshFailureBasisPoints
                    != basisPoints(refreshFailures, refreshAttempts)
                    || admissionDenialBasisPoints
                    != basisPoints(admissionDenials, admissionChecks)
                    || !validMonitorStatus(monitorStatus)
                    || state == State.HEALTHY && !supplied.isEmpty()
                    || state == State.INITIALIZING && !supplied.isEmpty()
                    || state == State.SLO_VIOLATED && supplied.isEmpty()
                    || state == State.OBSERVATION_UNAVAILABLE
                    && !supplied.equals(List.of(Violation.OBSERVATION_UNAVAILABLE))) {
                throw new IllegalArgumentException(
                        "Certificate status SLO assessment is invalid");
            }
            violations = List.copyOf(supplied);
        }

        private static Assessment initializing(
                Instant observedAt, PolicyDescriptor policy) {
            return new Assessment(SCHEMA_VERSION, State.INITIALIZING, List.of(), observedAt,
                    "UNAVAILABLE", false, false, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, -1, policy);
        }

        private static Assessment unavailable(
                Instant observedAt, PolicyDescriptor policy) {
            return new Assessment(SCHEMA_VERSION, State.OBSERVATION_UNAVAILABLE,
                    List.of(Violation.OBSERVATION_UNAVAILABLE), observedAt, "UNAVAILABLE",
                    false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, policy);
        }
    }

    private static void validatePolicy(
            long startupGraceSeconds,
            long maximumRefreshSuccessAgeSeconds,
            long minimumExpiryHeadroomSeconds,
            int minimumRefreshSamples,
            int maximumRefreshFailureBasisPoints,
            int minimumAdmissionSamples,
            int maximumAdmissionDenialBasisPoints,
            int maximumConsecutiveBatchLimitCycles) {
        if (startupGraceSeconds < 0 || startupGraceSeconds > 3_600
                || maximumRefreshSuccessAgeSeconds < 1
                || maximumRefreshSuccessAgeSeconds > 86_400
                || minimumExpiryHeadroomSeconds < 0
                || minimumExpiryHeadroomSeconds > 86_400
                || minimumRefreshSamples < 1 || minimumRefreshSamples > 1_000_000
                || maximumRefreshFailureBasisPoints < 0
                || maximumRefreshFailureBasisPoints > 10_000
                || minimumAdmissionSamples < 1 || minimumAdmissionSamples > 1_000_000
                || maximumAdmissionDenialBasisPoints < 0
                || maximumAdmissionDenialBasisPoints > 10_000
                || maximumConsecutiveBatchLimitCycles < 0
                || maximumConsecutiveBatchLimitCycles > 100) {
            throw new IllegalArgumentException("Certificate status SLO policy is invalid");
        }
    }

    private static boolean validMonitorStatus(String value) {
        if ("UNAVAILABLE".equals(value)) {
            return true;
        }
        try {
            ControlPlaneCertificateStatusMonitor.RefreshStatus.valueOf(value);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
