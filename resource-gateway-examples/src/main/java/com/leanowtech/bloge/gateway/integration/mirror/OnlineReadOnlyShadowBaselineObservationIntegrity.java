package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-address and detached-signature boundary for online baseline observations.
 */
public final class OnlineReadOnlyShadowBaselineObservationIntegrity {
    /** Stable reason when the regional observation authority is unavailable. */
    public static final String AUTHORITY_UNAVAILABLE =
            "ONLINE_BASELINE_OBSERVATION_AUTHORITY_UNAVAILABLE";
    /** Stable reason when observation identity, content, time, or signature is invalid. */
    public static final String OBSERVATION_INVALID =
            "ONLINE_BASELINE_OBSERVATION_INVALID";
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(1);

    private final ObjectMapper mapper;
    private final OnlineReadOnlyShadowBaselineEvidenceAuthority
            authority;
    private final Clock clock;

    /**
     * Creates the online baseline integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param authority independently governed regional evidence authority
     * @param clock trusted verification clock
     */
    public OnlineReadOnlyShadowBaselineObservationIntegrity(
            ObjectMapper mapper,
            OnlineReadOnlyShadowBaselineEvidenceAuthority
                    authority,
            Clock clock) {
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
        this.authority = authority == null
                ? OnlineReadOnlyShadowBaselineEvidenceAuthority
                .unavailable()
                : authority;
        this.clock = Objects.requireNonNullElseGet(
                clock, Clock::systemUTC);
    }

    /**
     * Addresses, signs, and immediately verifies a sidecar-produced observation.
     *
     * <p>Resource Gateway consumers call {@link #requireVerified(OnlineReadOnlyShadowBaselineObservation)};
     * this method exists for the independently deployed producer and compatibility tests.</p>
     *
     * @param unsigned complete observation with empty fingerprint and unsigned seal
     * @return addressed and verified observation
     */
    public OnlineReadOnlyShadowBaselineObservation sign(
            OnlineReadOnlyShadowBaselineObservation unsigned) {
        OnlineReadOnlyShadowBaselineObservation exact =
                canonical(unsigned);
        if (!exact.observationFingerprint().isEmpty()
                || exact.observationSeal().signed()
                || !expectedObservationId(exact)
                .equals(exact.observationId())) {
            throw new IllegalArgumentException(
                    OBSERVATION_INVALID);
        }
        if (!authorityAvailable()) {
            throw new IllegalStateException(
                    AUTHORITY_UNAVAILABLE);
        }
        OnlineReadOnlyShadowBaselineObservation addressed =
                exact.withFingerprint(
                        exact.calculateFingerprint(mapper));
        VisualRunEvidenceSeal seal =
                authority.seal(
                        addressed
                                .observationMaterialFingerprint(
                                        mapper));
        OnlineReadOnlyShadowBaselineObservation signed =
                addressed.withSeal(seal);
        if (verify(signed) != Verification.VERIFIED) {
            throw new IllegalStateException(
                    OBSERVATION_INVALID);
        }
        return signed;
    }

    /**
     * Recomputes identity, content address, and detached signature.
     *
     * @param observation untrusted online baseline observation
     * @return bounded verification outcome
     */
    public Verification verify(
            OnlineReadOnlyShadowBaselineObservation
                    observation) {
        if (observation == null) {
            return Verification.INVALID;
        }
        OnlineReadOnlyShadowBaselineObservation exact;
        try {
            exact = canonical(observation);
            if (!expectedObservationId(exact)
                    .equals(exact.observationId())
                    || !exact.calculateFingerprint(mapper)
                    .equals(
                            exact.observationFingerprint())
                    || !exact.observationSeal().signed()
                    || !exact.observationMaterialFingerprint(
                            mapper).equals(
                            exact.observationSeal()
                                    .materialFingerprint())
                    || exact.observationSeal().signedAt()
                    .isBefore(exact.issuedAt())
                    || exact.observationSeal().signedAt()
                    .isAfter(
                            clock.instant().plus(
                                    MAXIMUM_CLOCK_SKEW))) {
                return Verification.INVALID;
            }
        } catch (RuntimeException invalid) {
            return Verification.INVALID;
        }
        if (!authorityAvailable()) {
            return Verification.UNAVAILABLE;
        }
        try {
            VisualEvidenceSigner.Verification verified =
                    authority.verify(
                            exact.observationSeal(),
                            exact.observationMaterialFingerprint(
                                    mapper));
            return verified.valid()
                    ? Verification.VERIFIED
                    : Verification.INVALID;
        } catch (RuntimeException unavailable) {
            return Verification.UNAVAILABLE;
        }
    }

    /**
     * Verifies and canonically detaches one observation before connector use.
     *
     * @param observation untrusted regional observation
     * @return canonical verified observation
     * @throws IllegalStateException when authority trust is unavailable
     * @throws IllegalArgumentException when content or signature is invalid
     */
    public OnlineReadOnlyShadowBaselineObservation
    requireVerified(
            OnlineReadOnlyShadowBaselineObservation
                    observation) {
        Verification result = verify(observation);
        if (result == Verification.UNAVAILABLE) {
            throw new IllegalStateException(
                    AUTHORITY_UNAVAILABLE);
        }
        if (result != Verification.VERIFIED) {
            throw new IllegalArgumentException(
                    OBSERVATION_INVALID);
        }
        return canonical(observation);
    }

    /**
     * Reports whether the regional observation trust authority is usable.
     *
     * @return true only when signatures can currently be verified
     */
    public boolean available() {
        return authorityAvailable();
    }

    private OnlineReadOnlyShadowBaselineObservation canonical(
            OnlineReadOnlyShadowBaselineObservation value) {
        Objects.requireNonNull(value, "observation");
        try {
            return mapper.readValue(
                    mapper.writeValueAsBytes(value),
                    OnlineReadOnlyShadowBaselineObservation
                            .class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException(
                    OBSERVATION_INVALID,
                    invalid);
        }
    }

    private String expectedObservationId(
            OnlineReadOnlyShadowBaselineObservation value) {
        return OnlineReadOnlyShadowBaselineObservation
                .deterministicObservationId(
                        mapper,
                        value.scope(),
                        value.executionId(),
                        value.commandFingerprint(),
                        value.baselineBindingRef());
    }

    private boolean authorityAvailable() {
        try {
            return authority.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Bounded verification outcome. */
    public enum Verification {
        /** Content address and detached signature are valid. */
        VERIFIED,
        /** Verification authority cannot currently decide. */
        UNAVAILABLE,
        /** Identity, content, time, or signature is invalid. */
        INVALID
    }
}
