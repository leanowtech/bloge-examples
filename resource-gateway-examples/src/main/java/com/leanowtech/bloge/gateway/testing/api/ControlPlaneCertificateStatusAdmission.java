package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Non-blocking request admission backed by one immutable durable certificate-status snapshot.
 *
 * <p>Refresh occurs off the request path. Each request performs only local exact-key lookup plus
 * wall-clock and monotonic deadline checks. The deadline is derived from the later of local time
 * and database observation time, so local clock rollback cannot extend a publication. Once either
 * clock observes expiry the publication is permanently closed and cannot be resurrected by a
 * later clock correction. A newer signed sequence is required to reopen admission.</p>
 */
public final class ControlPlaneCertificateStatusAdmission {

    private static final Duration MAXIMUM_CACHE_LIFETIME = Duration.ofHours(24);

    private final Clock clock;
    private final LongSupplier ticker;
    private final ControlPlaneCertificateStatusTelemetry telemetry;
    private final AtomicReference<CachedStatus> cached = new AtomicReference<>();

    /** Creates an admission cache using UTC wall time and the JVM monotonic ticker. */
    public ControlPlaneCertificateStatusAdmission() {
        this(Clock.systemUTC(), System::nanoTime,
                ControlPlaneCertificateStatusTelemetry.noop());
    }

    /**
     * Creates an admission cache with explicit clocks for deterministic verification.
     *
     * @param clock wall clock used to honor signed expiry
     * @param ticker monotonic nanosecond source used to defeat wall-clock rollback
     */
    public ControlPlaneCertificateStatusAdmission(Clock clock, LongSupplier ticker) {
        this(clock, ticker, ControlPlaneCertificateStatusTelemetry.noop());
    }

    /**
     * Creates an admission cache with explicit clocks and fixed-cardinality telemetry.
     *
     * @param clock wall clock used to honor signed expiry
     * @param ticker monotonic nanosecond source used to defeat wall-clock rollback
     * @param telemetry request-decision recorder without target or certificate tags
     */
    public ControlPlaneCertificateStatusAdmission(
            Clock clock,
            LongSupplier ticker,
            ControlPlaneCertificateStatusTelemetry telemetry) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * Atomically installs a newer complete durable snapshot.
     *
     * <p>An exact replay is a no-op and therefore cannot extend its monotonic lifetime. Rollback,
     * same-sequence fork, uninitialized state, already expired state, and excessive cache lifetime
     * fail closed without disturbing the current snapshot.</p>
     *
     * @param snapshot tamper-checked durable floor snapshot
     */
    public void refresh(ControlPlaneCertificateStatusFloor.Snapshot snapshot) {
        ControlPlaneCertificateStatusFloor.Snapshot required = Objects.requireNonNull(
                snapshot, "snapshot");
        if (!required.initialized()) {
            throw invalid("Certificate status admission requires an initialized floor");
        }
        while (true) {
            CachedStatus current = cached.get();
            if (current != null && required.sequence() < current.sequence()) {
                throw invalid("Certificate status admission rejects cursor rollback");
            }
            if (current != null && required.sequence() == current.sequence()) {
                if (!required.publicationFingerprint().equals(
                        current.publicationFingerprint())) {
                    throw invalid("Certificate status admission rejects a cursor fork");
                }
                return;
            }
            Instant now = clock.instant();
            Instant lifetimeStart = now.isAfter(required.observedAt())
                    ? now : required.observedAt();
            Duration remaining = Duration.between(lifetimeStart, required.expiresAt());
            if (remaining.isZero() || remaining.isNegative()
                    || remaining.compareTo(MAXIMUM_CACHE_LIFETIME) > 0) {
                throw invalid("Certificate status admission snapshot is stale");
            }
            long loadedTick = ticker.getAsLong();
            long expiresTick = saturatedAdd(loadedTick, remaining.toNanos());
            LinkedHashMap<String, ControlPlaneCertificateStatusPublication.TargetStatus> targets =
                    new LinkedHashMap<>();
            for (ControlPlaneCertificateStatusPublication.TargetStatus target
                    : required.targets()) {
                if (targets.putIfAbsent(target.targetId(), target) != null) {
                    throw invalid("Certificate status admission target inventory is invalid");
                }
            }
            CachedStatus candidate = new CachedStatus(required.sequence(),
                    required.publicationFingerprint(), required.expiresAt(), loadedTick,
                    expiresTick, Map.copyOf(targets), new AtomicBoolean());
            if (cached.compareAndSet(current, candidate)) {
                return;
            }
        }
    }

    /**
     * Returns a target-bound gate suitable for a rotating HTTP transport.
     *
     * @param targetId stable governed transport target
     * @return non-blocking exact generation/settings gate
     */
    public RotatingControlPlaneHttpTransport.CertificateStatusGate gate(String targetId) {
        String required = new ControlPlaneCertificateStatusFloor.ExpectedTarget(
                targetId).targetId();
        return (generation, settingsFingerprint) ->
                servingPermitted(required, generation, settingsFingerprint);
    }

    /**
     * Evaluates one exact target identity without database or network I/O.
     *
     * @param targetId stable governed target
     * @param generation active certificate generation
     * @param settingsFingerprint exact TLS settings identity
     * @return true only for a fresh explicit client-and-server GOOD decision
     */
    public boolean servingPermitted(
            String targetId, long generation, String settingsFingerprint) {
        CachedStatus status = cached.get();
        ControlPlaneCertificateStatusTelemetry.AdmissionDecision decision =
                decision(status, targetId, generation, settingsFingerprint);
        telemetry.recordAdmission(decision);
        return decision == ControlPlaneCertificateStatusTelemetry.AdmissionDecision.ALLOWED;
    }

    private ControlPlaneCertificateStatusTelemetry.AdmissionDecision decision(
            CachedStatus status,
            String targetId,
            long generation,
            String settingsFingerprint) {
        if (status == null) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.NO_PUBLICATION;
        }
        if (!fresh(status)) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.STALE;
        }
        ControlPlaneCertificateStatusPublication.TargetStatus target =
                status.targets().get(normalized(targetId));
        if (target == null) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.TARGET_MISSING;
        }
        if (target.generation() != generation) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.GENERATION_MISMATCH;
        }
        if (!target.settingsFingerprint().equals(normalized(settingsFingerprint))) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.SETTINGS_MISMATCH;
        }
        if (target.certificates().stream().anyMatch(evidence -> evidence.status()
                == ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED)) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.REVOKED;
        }
        if (!target.admitted()) {
            return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.UNKNOWN;
        }
        return ControlPlaneCertificateStatusTelemetry.AdmissionDecision.ALLOWED;
    }

    /** @return fixed-cardinality admission posture without target or certificate identity */
    public Descriptor descriptor() {
        CachedStatus status = cached.get();
        boolean fresh = fresh(status);
        if (status == null) {
            return new Descriptor(Descriptor.SCHEMA_VERSION, false, false, 0,
                    0, 0, 0, 0, 0, "NO_PUBLICATION");
        }
        long good = status.targets().values().stream().filter(
                ControlPlaneCertificateStatusPublication.TargetStatus::admitted).count();
        long revoked = status.targets().values().stream().filter(target ->
                target.certificates().stream().anyMatch(evidence -> evidence.status()
                        == ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED))
                .count();
        long unknown = status.targets().size() - good - revoked;
        long seconds = fresh ? Math.max(0,
                Duration.between(clock.instant(), status.expiresAt()).toSeconds()) : 0;
        return new Descriptor(Descriptor.SCHEMA_VERSION, true, fresh, status.sequence(),
                status.targets().size(), Math.toIntExact(good), Math.toIntExact(revoked),
                Math.toIntExact(unknown), Math.min(seconds, 86_400),
                fresh ? "FRESH" : "STALE");
    }

    private boolean fresh(CachedStatus status) {
        if (status == null || status.closed().get()) {
            return false;
        }
        long nowTick = ticker.getAsLong();
        Instant now = clock.instant();
        boolean current = now.isBefore(status.expiresAt())
                && nowTick >= status.loadedTick() && nowTick < status.expiresTick();
        if (!current) {
            status.closed().set(true);
        }
        return current;
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record CachedStatus(
            long sequence,
            String publicationFingerprint,
            Instant expiresAt,
            long loadedTick,
            long expiresTick,
            Map<String, ControlPlaneCertificateStatusPublication.TargetStatus> targets,
            AtomicBoolean closed) {
    }

    /**
     * Fixed-cardinality public status-admission descriptor.
     *
     * @param schemaVersion descriptor protocol version
     * @param loaded whether a durable publication is cached
     * @param fresh whether the cached publication currently admits evaluation
     * @param sequence cached durable cursor, or zero
     * @param targetCount complete target count
     * @param goodTargetCount targets with explicit client-and-server GOOD status
     * @param revokedTargetCount targets containing at least one REVOKED certificate
     * @param unknownTargetCount remaining non-admitted targets
     * @param secondsToExpiry bounded remaining wall-clock lifetime
     * @param reasonCode bounded machine-readable posture
     */
    public record Descriptor(
            String schemaVersion,
            boolean loaded,
            boolean fresh,
            long sequence,
            int targetCount,
            int goodTargetCount,
            int revokedTargetCount,
            int unknownTargetCount,
            long secondsToExpiry,
            String reasonCode) {

        /** Current admission descriptor protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusAdmissionDescriptor.v1";

        /** Rejects contradictory or unbounded health projection. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            reasonCode = normalized(reasonCode);
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 0
                    || targetCount < 0 || targetCount > 128
                    || goodTargetCount < 0 || revokedTargetCount < 0
                    || unknownTargetCount < 0
                    || goodTargetCount + revokedTargetCount + unknownTargetCount != targetCount
                    || secondsToExpiry < 0 || secondsToExpiry > 86_400
                    || !reasonCode.matches("[A-Z][A-Z0-9_.-]{0,127}")
                    || fresh && (!loaded || targetCount == 0 || sequence < 1)
                    || !loaded && (sequence != 0 || targetCount != 0
                    || secondsToExpiry != 0)) {
                throw invalid("Certificate status admission descriptor is invalid");
            }
        }
    }
}
