package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain-separated signing and read-time verification boundary for Fidelity profiles.
 *
 * <p>The profile content address excludes its detached seal. This boundary signs the profile's
 * domain-separated attestation material under a stable idempotency key, then locally verifies the
 * provider result before persistence. Every read repeats arithmetic, fingerprint, key-lifecycle,
 * signature, and signing-time checks; an unavailable signer or verification key never degrades to
 * accepting an unsigned profile.</p>
 */
public final class DomainFidelityProfileIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates the production integrity boundary using the server UTC clock.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed managed evidence signer
     */
    public DomainFidelityProfileIntegrity(
            ObjectMapper mapper, VisualEvidenceSigner signer) {
        this(mapper, signer, Clock.systemUTC());
    }

    /** Deterministic constructor for lifecycle and rotation tests. */
    DomainFidelityProfileIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Signs or idempotently recovers one exact unsigned profile.
     *
     * @param profile content-addressed unsigned projection
     * @return identical profile carrying a locally verified detached seal
     */
    public DomainFidelityProfile sign(
            DomainFidelityProfile profile) {
        DomainFidelityProfile exact = Objects.requireNonNull(
                profile, "profile");
        exact.verify(mapper);
        if (exact.profileSeal().signed()) {
            return verify(exact);
        }
        String material =
                exact.attestationMaterialFingerprint(mapper);
        VisualRunEvidenceSeal seal = signer.seal(
                material,
                "domain-fidelity-profile:"
                        + exact.profileFingerprint()
                        .substring("sha256:".length()));
        return verify(exact.withProfileSeal(seal));
    }

    /**
     * Re-verifies one persisted signed profile.
     *
     * @param profile untrusted stored or decoded profile
     * @return verified immutable profile
     */
    public DomainFidelityProfile verify(
            DomainFidelityProfile profile) {
        DomainFidelityProfile exact = Objects.requireNonNull(
                profile, "profile");
        exact.verify(mapper);
        VisualRunEvidenceSeal seal = exact.profileSeal();
        if (!seal.signed()) {
            throw new Violation(Reason.UNSIGNED);
        }
        String material =
                exact.attestationMaterialFingerprint(mapper);
        VisualEvidenceSigner.Verification verification =
                signer.verify(seal, material);
        if (!verification.valid()) {
            throw new Violation(
                    "UNAVAILABLE".equals(verification.status())
                            || "KEY_UNAVAILABLE".equals(
                            verification.status())
                            ? Reason.KEY_UNAVAILABLE
                            : Reason.SIGNATURE_INVALID);
        }
        Instant now = clock.instant();
        if (seal.signedAt().isBefore(
                exact.measuredAt().minus(MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isAfter(
                now.plus(MAXIMUM_CLOCK_SKEW))) {
            throw new Violation(Reason.SIGNING_TIME_INVALID);
        }
        return exact;
    }

    /** @return whether the configured signing authority is currently usable */
    public boolean available() {
        try {
            return signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Closed profile-signing rejection vocabulary. */
    public enum Reason {
        UNSIGNED,
        SIGNATURE_INVALID,
        KEY_UNAVAILABLE,
        SIGNING_TIME_INVALID
    }

    /** Payload-free integrity failure carrying only a stable reason. */
    public static final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable profile integrity failure. */
        public Violation(Reason reason) {
            super("Domain Fidelity profile integrity rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
