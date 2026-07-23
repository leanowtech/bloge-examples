package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationAuthority;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationToken;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorServingGenerationTrustProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Process-local admission fence backed by a shared serving-generation authority floor.
 *
 * <p>Every new run forces a current-floor read. Operator occurrences may reuse that verified floor
 * only inside the token's signed maximum-staleness window. Once the floor advances, an old
 * generation may finish an operator occurrence already admitted before the check, but no later
 * occurrence obtains an operator from the engine. Authority outage after cache expiry, rollback,
 * fork, invalid signature, and token expiry all fail closed before business operator execution.</p>
 */
public final class MirrorServingGenerationFence {
    private final MirrorServingGenerationToken expected;
    private final MirrorServingGenerationAuthority authority;
    private final MirrorServingGenerationTrustProvider trust;
    private final MirrorServingGenerationIntegrity integrity;
    private final Clock clock;
    private final MirrorServingGenerationTelemetry telemetry;

    private Instant floorVerifiedAt;

    /**
     * Creates a fence for one already-verified expected generation.
     *
     * @param expected exact generation bound into the compiled plan
     * @param authority shared current-floor authority
     * @param trust locally pinned authority keys
     * @param integrity independent token verifier
     * @param clock trusted local admission clock
     */
    public MirrorServingGenerationFence(
            MirrorServingGenerationToken expected,
            MirrorServingGenerationAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            MirrorServingGenerationIntegrity integrity,
            Clock clock) {
        this(expected, authority, trust, integrity, clock,
                MirrorServingGenerationTelemetry.noop());
    }

    /**
     * Creates a fence with fixed-cardinality floor-check telemetry.
     *
     * @param expected exact generation bound into the compiled plan
     * @param authority shared current-floor authority
     * @param trust locally pinned authority keys
     * @param integrity independent token verifier
     * @param clock trusted local admission clock
     * @param telemetry bounded floor-check metrics
     */
    public MirrorServingGenerationFence(
            MirrorServingGenerationToken expected,
            MirrorServingGenerationAuthority authority,
            MirrorServingGenerationTrustProvider trust,
            MirrorServingGenerationIntegrity integrity,
            Clock clock,
            MirrorServingGenerationTelemetry telemetry) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /**
     * Forces a current-floor check for a newly admitted run.
     *
     * @throws TestControlException when the generation is stale, rolled back, expired, invalid, or
     *                              cannot be checked
     */
    public synchronized void admitRun() {
        refresh(
                clock.instant(),
                MirrorServingGenerationTelemetry.Check.RUN);
    }

    /**
     * Admits one operator occurrence under the last verified floor or refreshes it.
     *
     * <p>The check must run after frozen invocation-site validation and before fixture binding,
     * resolver selection, or operator execution.</p>
     *
     * @throws TestControlException when a new occurrence is no longer authorized
     */
    public synchronized void admitOccurrence() {
        Instant now = clock.instant();
        MirrorServingGenerationTelemetry.Check check =
                MirrorServingGenerationTelemetry.Check.OCCURRENCE;
        requireNotExpired(now, check);
        if (floorVerifiedAt == null
                || !now.isBefore(floorVerifiedAt.plus(
                expected.material().maximumStaleness()))) {
            refresh(now, check);
        } else {
            telemetry.record(
                    check, MirrorServingGenerationTelemetry.Outcome.CACHED);
        }
    }

    /** @return exact payload-free token bound into this fence */
    public MirrorServingGenerationToken token() {
        return expected;
    }

    private void refresh(
            Instant now,
            MirrorServingGenerationTelemetry.Check check) {
        requireNotExpired(now, check);
        MirrorServingGenerationAuthority.Resolution floor;
        try {
            floor = authority.currentFloor(
                    new MirrorServingGenerationAuthority.FloorRequest(
                            expected.material().streamId(),
                            expected.material().scope(),
                            expected.material().authorizedPurpose()));
        } catch (RuntimeException failure) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE,
                    "MIRROR_SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation current floor is unavailable.");
        }
        if (floor == null
                || floor.outcome()
                == MirrorServingGenerationAuthority.Outcome.UNAVAILABLE) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE,
                    "MIRROR_SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation current floor is unavailable.");
        }
        if (floor.outcome()
                == MirrorServingGenerationAuthority.Outcome.REJECTED) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.REJECTED,
                    "MIRROR_SERVING_GENERATION_STALE",
                    "Serving generation is no longer the current authority floor.");
        }
        MirrorServingGenerationToken current = floor.token();
        try {
            integrity.verify(
                    current,
                    trust,
                    new MirrorServingGenerationIntegrity.Expectation(
                            expected.material().scope(),
                            expected.material().authorizedPurpose(),
                            "",
                            null),
                    now);
        } catch (MirrorServingGenerationIntegrity.TrustUnavailableException
                 unavailable) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.UNAVAILABLE,
                    "MIRROR_SERVING_GENERATION_AUTHORITY_UNAVAILABLE",
                    "Serving-generation trust policy is unavailable.");
        } catch (IllegalArgumentException invalid) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.INVALID,
                    "MIRROR_SERVING_GENERATION_TOKEN_INVALID",
                    "Serving-generation current floor failed local verification.");
        }
        compareCurrent(current, check);
        floorVerifiedAt = now;
        telemetry.record(
                check, MirrorServingGenerationTelemetry.Outcome.CURRENT);
    }

    private void compareCurrent(
            MirrorServingGenerationToken current,
            MirrorServingGenerationTelemetry.Check check) {
        if (!current.material().streamId().equals(
                expected.material().streamId())) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.INVALID,
                    "MIRROR_SERVING_GENERATION_TOKEN_INVALID",
                    "Serving-generation current floor changed stream identity.");
        }
        if (current.material().generation()
                < expected.material().generation()
                || current.material().revocationCursor()
                < expected.material().revocationCursor()) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.ROLLBACK,
                    "MIRROR_SERVING_GENERATION_ROLLBACK",
                    "Serving-generation authority floor moved backwards.");
        }
        if (current.material().generation()
                != expected.material().generation()
                || current.material().revocationCursor()
                != expected.material().revocationCursor()
                || !current.tokenFingerprint().equals(
                expected.tokenFingerprint())) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.STALE,
                    "MIRROR_SERVING_GENERATION_STALE",
                    "Serving generation is no longer the current authority floor.");
        }
    }

    private void requireNotExpired(
            Instant now,
            MirrorServingGenerationTelemetry.Check check) {
        if (!now.isBefore(expected.material().expiresAt())) {
            throw failure(
                    check,
                    MirrorServingGenerationTelemetry.Outcome.EXPIRED,
                    "MIRROR_SERVING_GENERATION_EXPIRED",
                    "Serving-generation token has expired.");
        }
    }

    private TestControlException failure(
            MirrorServingGenerationTelemetry.Check check,
            MirrorServingGenerationTelemetry.Outcome outcome,
            String code,
            String message) {
        telemetry.record(check, outcome);
        return new TestControlException(
                code, "MIRROR_SERVING_GENERATION", message);
    }
}
