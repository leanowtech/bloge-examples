package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain-separated signing and independent verification boundary for read-only shadow
 * comparisons.
 *
 * <p>The boundary always recomputes protocol semantics and content addressing before it trusts a
 * detached seal. Signing immediately re-verifies the returned provider material. Verification-key
 * outage, invalid signatures, and impossible signing times fail closed.</p>
 */
public final class ReadOnlyShadowComparisonIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a production integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed shadow-comparison signing authority
     */
    public ReadOnlyShadowComparisonIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer) {
        this(mapper, signer, Clock.systemUTC());
    }

    /** Deterministic constructor for lifecycle and rotation tests. */
    ReadOnlyShadowComparisonIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Content-addresses, signs, and locally re-verifies one unsigned comparison.
     *
     * @param comparison structurally valid unsigned comparison
     * @return exact comparison carrying a verified detached seal
     */
    public ReadOnlyShadowComparison sign(
            ReadOnlyShadowComparison comparison) {
        ReadOnlyShadowComparison source =
                Objects.requireNonNull(
                        comparison, "comparison");
        ReadOnlyShadowComparison addressed =
                source.comparisonFingerprint().isBlank()
                        ? source.withFingerprint(
                        source.calculateFingerprint(mapper))
                        : source;
        addressed.verify(mapper);
        if (addressed.comparisonSeal().signed()) {
            return verify(addressed);
        }
        String material =
                addressed.attestationMaterialFingerprint(mapper);
        VisualRunEvidenceSeal seal = signer.seal(
                material,
                "read-only-shadow-comparison:"
                        + addressed.comparisonFingerprint()
                        .substring("sha256:".length()));
        return verify(
                addressed.withComparisonSeal(seal));
    }

    /**
     * Independently verifies one decoded or persisted signed comparison.
     *
     * @param comparison untrusted signed comparison
     * @return canonical verified comparison
     */
    public ReadOnlyShadowComparison verify(
            ReadOnlyShadowComparison comparison) {
        ReadOnlyShadowComparison exact =
                Objects.requireNonNull(
                        comparison, "comparison");
        exact.verify(mapper);
        VisualRunEvidenceSeal seal =
                exact.comparisonSeal();
        if (!seal.signed()) {
            throw new Violation(Reason.UNSIGNED);
        }
        String material =
                exact.attestationMaterialFingerprint(mapper);
        VisualEvidenceSigner.Verification verification =
                signer.verify(seal, material);
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
        if (seal.signedAt().isBefore(
                exact.observedAt().minus(
                        MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isAfter(
                now.plus(MAXIMUM_CLOCK_SKEW))) {
            throw new Violation(
                    Reason.SIGNING_TIME_INVALID);
        }
        return exact;
    }

    /** @return whether the exact signing and verification authority is currently usable */
    public boolean available() {
        try {
            return signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Closed payload-free integrity rejection vocabulary. */
    public enum Reason {
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
            super("Read-only shadow comparison integrity rejected: "
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
