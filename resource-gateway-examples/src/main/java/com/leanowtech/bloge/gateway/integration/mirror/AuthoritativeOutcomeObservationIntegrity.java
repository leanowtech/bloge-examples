package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain-separated signing and independent verification boundary for authoritative outcome
 * observations.
 *
 * <p>The boundary first recomputes protocol semantics and content addressing. Signing verifies the
 * external outcome-authority closure before creating a Resource Gateway seal. Reading verifies the
 * cheap local seal and signed attestation time before invoking the external authority, preventing
 * invalid input from amplifying customer-ledger traffic. Both trust boundaries must pass.
 * Authority or key outage, rejected source lineage, invalid signatures, and impossible attestation
 * times all fail closed.</p>
 */
public final class AuthoritativeOutcomeObservationIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final AuthoritativeOutcomeAuthorityVerifier
            authorityVerifier;
    private final Clock clock;

    /**
     * Creates a production integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed Resource Gateway outcome-observation signer
     * @param authorityVerifier independent business outcome authority verifier
     */
    public AuthoritativeOutcomeObservationIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeAuthorityVerifier
                    authorityVerifier) {
        this(
                mapper,
                signer,
                authorityVerifier,
                Clock.systemUTC());
    }

    /** Deterministic constructor for trust outage, signature, and lifecycle tests. */
    AuthoritativeOutcomeObservationIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeAuthorityVerifier
                    authorityVerifier,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.authorityVerifier = Objects.requireNonNull(
                authorityVerifier, "authorityVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Content-addresses and signs one independently verified observation.
     *
     * @param observation structurally valid unsigned observation
     * @return exact observation carrying a verified detached seal
     */
    public AuthoritativeOutcomeObservation sign(
            AuthoritativeOutcomeObservation observation) {
        AuthoritativeOutcomeObservation source =
                Objects.requireNonNull(
                        observation, "observation");
        AuthoritativeOutcomeObservation timed =
                source.observationFingerprint().isBlank()
                        && !source.observationSeal().signed()
                        ? source.withAttestedAt(clock.instant())
                        : source;
        AuthoritativeOutcomeObservation addressed =
                timed.observationFingerprint().isBlank()
                        ? timed.withFingerprint(
                        timed.calculateFingerprint(mapper))
                        : timed;
        addressed.verify(mapper);
        verifyAuthority(addressed);
        if (addressed.observationSeal().signed()) {
            return verify(addressed);
        }
        VisualRunEvidenceSeal seal = signer.seal(
                addressed.attestationMaterialFingerprint(mapper),
                "authoritative-outcome-observation:"
                        + addressed.observationFingerprint()
                        .substring("sha256:".length()));
        return verify(
                addressed.withObservationSeal(seal));
    }

    /**
     * Independently verifies a decoded or persisted signed observation.
     *
     * @param observation untrusted signed observation
     * @return canonical verified observation
     */
    public AuthoritativeOutcomeObservation verify(
            AuthoritativeOutcomeObservation observation) {
        AuthoritativeOutcomeObservation exact =
                Objects.requireNonNull(
                        observation, "observation");
        exact.verify(mapper);
        VisualRunEvidenceSeal seal =
                exact.observationSeal();
        if (!seal.signed()) {
            throw new Violation(Reason.UNSIGNED);
        }
        VisualEvidenceSigner.Verification verification =
                signer.verify(
                        seal,
                        exact.attestationMaterialFingerprint(
                                mapper));
        if (!verification.valid()) {
            throw new Violation(
                    "UNAVAILABLE".equals(
                            verification.status())
                            || "KEY_UNAVAILABLE".equals(
                            verification.status())
                            ? Reason.KEY_UNAVAILABLE
                            : Reason.SIGNATURE_INVALID);
        }
        Instant now = clock.instant();
        if (exact.attestedAt().isAfter(
                now.plus(MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isBefore(
                exact.attestedAt().minus(
                        MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isAfter(
                exact.attestedAt().plus(
                        MAXIMUM_CLOCK_SKEW))) {
            throw new Violation(
                    Reason.SIGNING_TIME_INVALID);
        }
        verifyAuthority(exact);
        return exact;
    }

    /** @return whether both the external authority chain and RG seal authority are usable */
    public boolean available() {
        try {
            return authorityVerifier.available()
                    && signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void verifyAuthority(
            AuthoritativeOutcomeObservation observation) {
        boolean available;
        try {
            available = authorityVerifier.available();
        } catch (RuntimeException unavailableFailure) {
            available = false;
        }
        if (!available) {
            throw new Violation(
                    Reason.AUTHORITY_UNAVAILABLE);
        }
        try {
            authorityVerifier.verify(observation);
        } catch (Violation violation) {
            throw violation;
        } catch (RuntimeException rejected) {
            throw new Violation(
                    Reason.AUTHORITY_REJECTED);
        }
    }

    /** Closed payload-free integrity rejection vocabulary. */
    public enum Reason {
        AUTHORITY_UNAVAILABLE,
        AUTHORITY_REJECTED,
        UNSIGNED,
        SIGNATURE_INVALID,
        KEY_UNAVAILABLE,
        SIGNING_TIME_INVALID
    }

    /** Stable payload-free integrity failure. */
    public static final class Violation
            extends RuntimeException {
        private final Reason reason;

        /** Creates one stable integrity violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome observation integrity rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
