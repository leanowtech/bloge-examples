package com.leanowtech.bloge.gateway.testing.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-cardinality operational telemetry for certificate-status refresh and admission.
 *
 * <p>Every tag value is a closed enum. Target ids, certificate and authority fingerprints,
 * endpoint URIs, credential references, reason details, and exception messages are deliberately
 * excluded. Internal cumulative counters also provide a registry-independent basis for local SLO
 * assessment.</p>
 */
public final class ControlPlaneCertificateStatusTelemetry {

    static final String PREFIX = "resource.gateway.control.plane.certificate.status.";

    /** Closed request-path decision vocabulary. */
    public enum AdmissionDecision {
        /** Exact target, generation, settings, freshness, and GOOD status matched. */
        ALLOWED,
        /** No durable status publication has been installed. */
        NO_PUBLICATION,
        /** The installed publication crossed a wall-clock or monotonic hard deadline. */
        STALE,
        /** The complete status inventory does not contain the requested target. */
        TARGET_MISSING,
        /** The active certificate generation differs from signed status. */
        GENERATION_MISMATCH,
        /** The active TLS-settings fingerprint differs from signed status. */
        SETTINGS_MISMATCH,
        /** At least one exact workload certificate is explicitly revoked. */
        REVOKED,
        /** At least one exact workload certificate lacks explicit GOOD evidence. */
        UNKNOWN
    }

    private final boolean enabled;
    private final Map<ControlPlaneCertificateStatusMonitor.RefreshStatus, Counter> refreshMeters;
    private final Map<AdmissionDecision, Counter> admissionMeters;
    private final Map<ControlPlaneCertificateStatusMonitor.RefreshStatus, AtomicLong>
            refreshStatusGauges;
    private final Map<ControlPlaneCertificateStatusSloMonitor.Violation, AtomicLong>
            violationGauges;
    private final AtomicLong refreshAttempts = new AtomicLong();
    private final AtomicLong refreshFailures = new AtomicLong();
    private final AtomicLong refreshSuccesses = new AtomicLong();
    private final AtomicLong admissionChecks = new AtomicLong();
    private final AtomicLong admissionDenials = new AtomicLong();
    private final AtomicLong consecutiveBatchLimitCycles = new AtomicLong();
    private final AtomicLong lastRefreshEpochMillis = unknown();
    private final AtomicLong lastRefreshSuccessEpochMillis = unknown();
    private final AtomicLong durableSequence = unknown();
    private final AtomicLong sourceAvailable = unknown();
    private final AtomicLong sourceHeadVerified = unknown();
    private final AtomicLong sourceHeadSequence = unknown();
    private final AtomicLong sourceHeadLag = unknown();
    private final AtomicLong sourceHeadSecondsToExpiry = unknown();
    private final AtomicLong admissionFresh = unknown();
    private final AtomicLong secondsToExpiry = unknown();
    private final AtomicLong targetCount = unknown();
    private final AtomicLong goodTargetCount = unknown();
    private final AtomicLong revokedTargetCount = unknown();
    private final AtomicLong unknownTargetCount = unknown();
    private final AtomicLong sloHealth = new AtomicLong(-2L);

    /**
     * Registers the complete bounded metric vocabulary.
     *
     * @param registry deployment-selected Micrometer registry
     */
    public ControlPlaneCertificateStatusTelemetry(MeterRegistry registry) {
        MeterRegistry meters = Objects.requireNonNull(registry, "registry");
        enabled = true;
        refreshMeters = counters(meters, PREFIX + "refresh.attempts", "result",
                ControlPlaneCertificateStatusMonitor.RefreshStatus.values());
        admissionMeters = counters(meters, PREFIX + "admission.checks", "decision",
                AdmissionDecision.values());
        refreshStatusGauges = enumGauges(meters, PREFIX + "refresh.status", "status",
                ControlPlaneCertificateStatusMonitor.RefreshStatus.values());
        violationGauges = enumGauges(meters, PREFIX + "slo.violation", "code",
                ControlPlaneCertificateStatusSloMonitor.Violation.values());
        gauge(meters, PREFIX + "sequence", durableSequence,
                "Latest durable certificate-status sequence, or -1 before observation");
        gauge(meters, PREFIX + "source.available", sourceAvailable,
                "Latest source availability: 1 available, 0 unavailable, -1 unknown");
        gauge(meters, PREFIX + "source.head.verified", sourceHeadVerified,
                "Fresh exact source-head proof: 1 verified, 0 unavailable, -1 unknown");
        gauge(meters, PREFIX + "source.head.sequence", sourceHeadSequence,
                "Highest durably verified certificate-status source sequence");
        gauge(meters, PREFIX + "source.head.lag", sourceHeadLag,
                "Exact publication backlog, or -1 without a fresh source-head proof");
        gauge(meters, PREFIX + "source.head.seconds.to.expiry",
                sourceHeadSecondsToExpiry,
                "Remaining exact source-head proof lifetime, or -1 before observation");
        gauge(meters, PREFIX + "admission.fresh", admissionFresh,
                "Latest request admission freshness: 1 fresh, 0 stale, -1 unknown");
        gauge(meters, PREFIX + "admission.seconds.to.expiry", secondsToExpiry,
                "Bounded remaining signed admission lifetime, or -1 before observation");
        taggedGauge(meters, PREFIX + "targets", "status", "total", targetCount);
        taggedGauge(meters, PREFIX + "targets", "status", "good", goodTargetCount);
        taggedGauge(meters, PREFIX + "targets", "status", "revoked", revokedTargetCount);
        taggedGauge(meters, PREFIX + "targets", "status", "unknown", unknownTargetCount);
        gauge(meters, PREFIX + "refresh.consecutive.batch.limit",
                consecutiveBatchLimitCycles,
                "Consecutive bounded refresh cycles ending at the batch limit");
        gauge(meters, PREFIX + "slo.health", sloHealth,
                "Certificate-status SLO health: 1 healthy, 0 initializing, "
                        + "-1 violated, -2 unavailable");
    }

    private ControlPlaneCertificateStatusTelemetry() {
        enabled = false;
        refreshMeters = Map.of();
        admissionMeters = Map.of();
        refreshStatusGauges = Map.of();
        violationGauges = Map.of();
    }

    /** @return allocation-light telemetry for legacy constructors and isolated tests */
    public static ControlPlaneCertificateStatusTelemetry noop() {
        return new ControlPlaneCertificateStatusTelemetry();
    }

    /**
     * Records one terminal bounded refresh result and replaces operational gauges.
     *
     * @param monitor latest immutable monitor descriptor
     * @param admission latest immutable request-admission descriptor
     */
    public void recordRefresh(
            ControlPlaneCertificateStatusMonitor.Descriptor monitor,
            ControlPlaneCertificateStatusAdmission.Descriptor admission) {
        if (!enabled) {
            return;
        }
        ControlPlaneCertificateStatusMonitor.Descriptor observed = Objects.requireNonNull(
                monitor, "monitor");
        ControlPlaneCertificateStatusAdmission.Descriptor cache = Objects.requireNonNull(
                admission, "admission");
        increment(refreshAttempts);
        refreshMeters.get(observed.status()).increment();
        refreshStatusGauges.forEach((status, gauge) ->
                gauge.set(status == observed.status() ? 1L : 0L));
        lastRefreshEpochMillis.accumulateAndGet(
                observed.observedAt().toEpochMilli(), Math::max);
        if (successful(observed.status())) {
            increment(refreshSuccesses);
            lastRefreshSuccessEpochMillis.accumulateAndGet(
                    observed.observedAt().toEpochMilli(), Math::max);
        } else {
            increment(refreshFailures);
        }
        if (observed.status() == ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT) {
            increment(consecutiveBatchLimitCycles);
        } else {
            consecutiveBatchLimitCycles.set(0L);
        }
        durableSequence.set(observed.sequence());
        sourceAvailable.set(observed.sourceAvailable() ? 1L : 0L);
        sourceHeadVerified.set(observed.sourceHeadVerified() ? 1L : 0L);
        sourceHeadSequence.set(observed.sourceHeadSequence());
        sourceHeadLag.set(observed.sourceHeadLag());
        sourceHeadSecondsToExpiry.set(observed.sourceHeadExpiresAt() == null ? -1L
                : Math.max(0L, Duration.between(observed.observedAt(),
                observed.sourceHeadExpiresAt()).toSeconds()));
        observeAdmission(cache);
    }

    /** Records one request-path decision using only a closed fixed-cardinality result. */
    public void recordAdmission(AdmissionDecision decision) {
        if (!enabled) {
            return;
        }
        AdmissionDecision observed = Objects.requireNonNull(decision, "decision");
        increment(admissionChecks);
        if (observed != AdmissionDecision.ALLOWED) {
            increment(admissionDenials);
        }
        admissionMeters.get(observed).increment();
    }

    /** Replaces the SLO and violation gauges from one immutable assessment. */
    public void observe(ControlPlaneCertificateStatusSloMonitor.Assessment assessment) {
        if (!enabled) {
            return;
        }
        ControlPlaneCertificateStatusSloMonitor.Assessment observed = Objects.requireNonNull(
                assessment, "assessment");
        sloHealth.set(switch (observed.state()) {
            case HEALTHY -> 1L;
            case INITIALIZING -> 0L;
            case SLO_VIOLATED -> -1L;
            case OBSERVATION_UNAVAILABLE -> -2L;
        });
        violationGauges.forEach((violation, gauge) ->
                gauge.set(observed.violations().contains(violation) ? 1L : 0L));
    }

    /** @return immutable cumulative basis for local SLO assessment */
    public Snapshot snapshot() {
        return new Snapshot(refreshAttempts.get(), refreshFailures.get(),
                refreshSuccesses.get(), admissionChecks.get(), admissionDenials.get(),
                consecutiveBatchLimitCycles.get(), instant(lastRefreshEpochMillis.get()),
                instant(lastRefreshSuccessEpochMillis.get()));
    }

    private void observeAdmission(ControlPlaneCertificateStatusAdmission.Descriptor admission) {
        admissionFresh.set(admission.fresh() ? 1L : 0L);
        secondsToExpiry.set(admission.secondsToExpiry());
        targetCount.set(admission.targetCount());
        goodTargetCount.set(admission.goodTargetCount());
        revokedTargetCount.set(admission.revokedTargetCount());
        unknownTargetCount.set(admission.unknownTargetCount());
    }

    private static boolean successful(
            ControlPlaneCertificateStatusMonitor.RefreshStatus status) {
        return status == ControlPlaneCertificateStatusMonitor.RefreshStatus.CURRENT
                || status == ControlPlaneCertificateStatusMonitor.RefreshStatus.APPLIED
                || status == ControlPlaneCertificateStatusMonitor.RefreshStatus.BATCH_LIMIT;
    }

    private static <E extends Enum<E>> Map<E, Counter> counters(
            MeterRegistry registry, String name, String tagName, E[] values) {
        Map<E, Counter> counters = new EnumMap<>(values[0].getDeclaringClass());
        for (E value : values) {
            counters.put(value, Counter.builder(name)
                    .tag(tagName, value.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        return Map.copyOf(counters);
    }

    private static <E extends Enum<E>> Map<E, AtomicLong> enumGauges(
            MeterRegistry registry, String name, String tagName, E[] values) {
        Map<E, AtomicLong> gauges = new EnumMap<>(values[0].getDeclaringClass());
        for (E value : values) {
            AtomicLong gauge = new AtomicLong();
            gauges.put(value, gauge);
            taggedGauge(registry, name, tagName,
                    value.name().toLowerCase(Locale.ROOT), gauge);
        }
        return Map.copyOf(gauges);
    }

    private static void taggedGauge(
            MeterRegistry registry,
            String name,
            String tagName,
            String tagValue,
            AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tag(tagName, tagValue).register(registry);
    }

    private static void gauge(
            MeterRegistry registry, String name, AtomicLong value, String description) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description).register(registry);
    }

    private static AtomicLong unknown() {
        return new AtomicLong(-1L);
    }

    private static Instant instant(long epochMillis) {
        return epochMillis < 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static void increment(AtomicLong value) {
        value.getAndUpdate(current -> current == Long.MAX_VALUE ? current : current + 1L);
    }

    /**
     * Registry-independent cumulative observation basis.
     *
     * @param refreshAttempts total completed refresh cycles
     * @param refreshFailures refresh cycles not ending CURRENT, APPLIED, or BATCH_LIMIT
     * @param refreshSuccesses source-successful refresh cycles
     * @param admissionChecks total request-path status checks
     * @param admissionDenials request-path checks not ending ALLOWED
     * @param consecutiveBatchLimitCycles diagnostic batch-capacity streak, not exact backlog
     * @param lastRefreshAt latest refresh completion, nullable before the first cycle
     * @param lastSuccessfulRefreshAt latest source-successful cycle, nullable before success
     */
    public record Snapshot(
            long refreshAttempts,
            long refreshFailures,
            long refreshSuccesses,
            long admissionChecks,
            long admissionDenials,
            long consecutiveBatchLimitCycles,
            Instant lastRefreshAt,
            Instant lastSuccessfulRefreshAt) {

        /** Rejects contradictory cumulative telemetry. */
        public Snapshot {
            if (refreshAttempts < 0 || refreshFailures < 0 || refreshSuccesses < 0
                    || refreshFailures > refreshAttempts || refreshSuccesses > refreshAttempts
                    || refreshFailures + refreshSuccesses != refreshAttempts
                    || admissionChecks < 0 || admissionDenials < 0
                    || admissionDenials > admissionChecks || consecutiveBatchLimitCycles < 0
                    || lastSuccessfulRefreshAt != null && lastRefreshAt == null
                    || lastSuccessfulRefreshAt != null
                    && lastSuccessfulRefreshAt.isAfter(lastRefreshAt)) {
                throw new IllegalArgumentException(
                        "Certificate status telemetry snapshot is invalid");
            }
        }
    }
}
