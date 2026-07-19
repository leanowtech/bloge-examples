package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic bounded driver for external observation-archive inventory reconciliation.
 *
 * <p>Every tick visits the complete protocol-bounded authority set once in stable order. One
 * authority failure is counted and isolated so later authorities still advance. An in-process
 * overlap is rejected, while database leases and transactional stage fences remain authoritative
 * across replicas. Logs contain only aggregate stage counts and never authority or object identity.</p>
 */
public final class TestSuiteStabilityObservationExternalArchiveReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class);

    private final TestSuiteStabilityObservationExternalArchiveReconciliationService service;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<TickResult> latest =
            new AtomicReference<>(TickResult.notRun());

    /**
     * Creates the profile- and property-gated scheduler.
     *
     * @param service downstream-first bounded reconciliation pipeline
     */
    public TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(
            TestSuiteStabilityObservationExternalArchiveReconciliationService service) {
        this(service, Clock.systemUTC());
    }

    /**
     * Creates a scheduler with an explicit process clock for deterministic liveness tests.
     *
     * <p>The clock timestamps only process-local scheduler attempts. Durable cycle age and
     * cross-replica ownership continue to use database time in the persistence control plane.</p>
     *
     * @param service downstream-first bounded reconciliation pipeline
     * @param clock process-local UTC liveness clock
     */
    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(
            TestSuiteStabilityObservationExternalArchiveReconciliationService service,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Advances every configured authority by at most one stage.
     *
     * @return aggregate identity-free tick result retained for local diagnostics
     */
    @Scheduled(
            initialDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${gateway.testing.stability-observation-lifecycle.external-archive.reconciliation.interval-ms:300000}")
    public TickResult reconcile() {
        Instant attemptedAt = clock.instant();
        if (!running.compareAndSet(false, true)) {
            return latest.updateAndGet(previous -> TickResult.overlap(previous, attemptedAt));
        }
        long startedAt = System.nanoTime();
        try {
            var authorities = service.authorities();
            EnumMap<TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage,
                    Integer> stages = new EnumMap<>(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage.class);
            int succeeded = 0;
            int failed = 0;
            for (String authority : authorities) {
                try {
                    var attempt = service.advance(authority);
                    stages.merge(attempt.stage(), 1, Math::addExact);
                    succeeded++;
                } catch (RuntimeException unavailable) {
                    failed++;
                }
            }
            TickResult result = TickResult.completed(latest.get(), attemptedAt,
                    authorities.size(), succeeded, failed, stages, elapsed(startedAt));
            latest.set(result);
            if (failed > 0) {
                log.warn("External archive reconciliation degraded configured={}, succeeded={}, "
                                + "failed={}, stages={}",
                        result.configuredAuthorities(), result.succeededAuthorities(),
                        result.failedAuthorities(), result.stageCounts());
            } else {
                log.debug("External archive reconciliation completed configured={}, stages={}",
                        result.configuredAuthorities(), result.stageCounts());
            }
            return result;
        } catch (RuntimeException unavailable) {
            TickResult result = TickResult.failed(
                    latest.get(), attemptedAt, elapsed(startedAt));
            latest.set(result);
            log.warn("External archive reconciliation tick failed before authority processing");
            return result;
        } finally {
            running.set(false);
        }
    }

    /** @return latest process-local aggregate tick result without authority identities */
    public TickResult latest() {
        return latest.get();
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt));
    }

    /** Closed scheduler tick status. */
    public enum TickStatus {
        /** No scheduled or manual tick has run in this process. */
        NOT_RUN,
        /** One complete configured authority pass succeeded. */
        COMPLETED,
        /** The pass completed but one or more authorities failed. */
        DEGRADED,
        /** Membership discovery failed before bounded authority processing. */
        FAILED,
        /** Another invocation already owns this process-local scheduler. */
        LOCAL_OVERLAP
    }

    /**
     * Identity-free local scheduler observation.
     *
     * @param sequence process-local monotonic attempt sequence
     * @param status closed tick status
     * @param configuredAuthorities authorities discovered for this tick
     * @param succeededAuthorities authorities returning a closed stage result
     * @param failedAuthorities authority failures isolated during this tick
     * @param stageCounts fixed-enum stage outcome counts
     * @param elapsed monotonic process duration
     * @param attemptedAt process-clock time of this attempt, absent only before the first attempt
     * @param lastSuccessfulAt latest complete all-authority pass, or absent before one succeeds
     * @param consecutiveUnhealthyTicks degraded, failed, or overlapping attempts since success
     */
    public record TickResult(
            long sequence,
            TickStatus status,
            int configuredAuthorities,
            int succeededAuthorities,
            int failedAuthorities,
            Map<TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage, Integer>
                    stageCounts,
            Duration elapsed,
            Instant attemptedAt,
            Instant lastSuccessfulAt,
            long consecutiveUnhealthyTicks) {
        /** Validates aggregate cardinality and fixed-vocabulary observations. */
        public TickResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(stageCounts, "stageCounts");
            Objects.requireNonNull(elapsed, "elapsed");
            EnumMap<TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage,
                    Integer> copy = new EnumMap<>(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage.class);
            copy.putAll(stageCounts);
            stageCounts = Collections.unmodifiableMap(copy);
            int counted = stageCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (sequence < 0 || configuredAuthorities < 0 || succeededAuthorities < 0
                    || failedAuthorities < 0 || elapsed.isNegative()
                    || consecutiveUnhealthyTicks < 0
                    || configuredAuthorities
                    > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS
                    || succeededAuthorities + failedAuthorities != configuredAuthorities
                    || counted != succeededAuthorities
                    || stageCounts.values().stream().anyMatch(value -> value == null || value < 1)
                    || status == TickStatus.NOT_RUN
                    && (sequence != 0 || attemptedAt != null || lastSuccessfulAt != null
                    || consecutiveUnhealthyTicks != 0)
                    || status != TickStatus.NOT_RUN && attemptedAt == null
                    || lastSuccessfulAt != null && attemptedAt != null
                    && lastSuccessfulAt.isAfter(attemptedAt)
                    || status == TickStatus.COMPLETED && failedAuthorities != 0
                    || status == TickStatus.COMPLETED
                    && (lastSuccessfulAt == null
                    || !lastSuccessfulAt.equals(attemptedAt)
                    || consecutiveUnhealthyTicks != 0)
                    || status == TickStatus.DEGRADED && failedAuthorities == 0
                    || status != TickStatus.COMPLETED && status != TickStatus.NOT_RUN
                    && consecutiveUnhealthyTicks == 0
                    || (status == TickStatus.FAILED || status == TickStatus.LOCAL_OVERLAP
                    || status == TickStatus.NOT_RUN) && configuredAuthorities != 0) {
                throw new IllegalArgumentException(
                        "Invalid external reconciliation scheduler result");
            }
        }

        private static TickResult notRun() {
            return new TickResult(0, TickStatus.NOT_RUN, 0, 0, 0, Map.of(), Duration.ZERO,
                    null, null, 0);
        }

        private static TickResult overlap(TickResult previous, Instant attemptedAt) {
            return new TickResult(increment(previous.sequence()), TickStatus.LOCAL_OVERLAP,
                    0, 0, 0, Map.of(), Duration.ZERO, attemptedAt,
                    previous.lastSuccessfulAt(), increment(previous.consecutiveUnhealthyTicks()));
        }

        private static TickResult completed(
                TickResult previous,
                Instant attemptedAt,
                int configured,
                int succeeded,
                int failed,
                Map<TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage,
                        Integer> stages,
                Duration elapsed) {
            return new TickResult(increment(previous.sequence()),
                    failed == 0 ? TickStatus.COMPLETED : TickStatus.DEGRADED,
                    configured, succeeded, failed, stages, elapsed, attemptedAt,
                    failed == 0 ? attemptedAt : previous.lastSuccessfulAt(),
                    failed == 0 ? 0 : increment(previous.consecutiveUnhealthyTicks()));
        }

        private static TickResult failed(
                TickResult previous, Instant attemptedAt, Duration elapsed) {
            return new TickResult(increment(previous.sequence()), TickStatus.FAILED,
                    0, 0, 0, Map.of(), elapsed, attemptedAt, previous.lastSuccessfulAt(),
                    increment(previous.consecutiveUnhealthyTicks()));
        }

        private static long increment(long value) {
            try {
                return Math.incrementExact(value);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "External reconciliation scheduler counter overflow", overflow);
            }
        }
    }
}
