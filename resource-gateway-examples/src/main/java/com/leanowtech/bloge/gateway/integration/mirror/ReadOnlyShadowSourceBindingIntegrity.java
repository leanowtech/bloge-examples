package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressing and detached-signature boundary for Shadow source bindings.
 *
 * <p>Signing computes the nested baseline identity before the outer binding identity and
 * immediately verifies the authority result. Reads recompute both addresses before trusting the
 * seal, preventing a valid signature from being attached to structurally different source
 * coordinates.</p>
 */
public final class ReadOnlyShadowSourceBindingIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a source-binding integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed detached-source signing authority
     */
    public ReadOnlyShadowSourceBindingIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer) {
        this(mapper, signer, Clock.systemUTC());
    }

    /**
     * Creates a source-binding integrity boundary with an explicit clock.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed detached-source signing authority
     * @param clock trusted verification clock
     */
    public ReadOnlyShadowSourceBindingIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Addresses, signs, and locally re-verifies one unsigned binding.
     *
     * @param binding structurally valid unsigned source binding
     * @return verified immutable source binding
     */
    public ReadOnlyShadowSourceBinding sign(
            ReadOnlyShadowSourceBinding binding) {
        ReadOnlyShadowSourceBinding source =
                Objects.requireNonNull(binding, "binding");
        if (source.bindingSeal().signed()) {
            return verify(source);
        }
        String baselineFingerprint =
                source.baselineObservationFingerprint().isBlank()
                        ? source.baselineFingerprint(mapper)
                        : source.baselineObservationFingerprint();
        ReadOnlyShadowSourceBinding addressed =
                source.withFingerprints(
                        baselineFingerprint,
                        source.bindingFingerprint());
        if (addressed.bindingFingerprint().isBlank()) {
            addressed = addressed.withFingerprints(
                    addressed.baselineObservationFingerprint(),
                    addressed.calculateFingerprint(mapper));
        }
        addressed.verify(mapper);
        String material =
                addressed.attestationMaterialFingerprint(mapper);
        VisualRunEvidenceSeal seal = signer.seal(
                material,
                "read-only-shadow-source-binding:"
                        + addressed.bindingFingerprint()
                        .substring("sha256:".length()));
        return verify(addressed.withBindingSeal(seal));
    }

    /**
     * Recomputes content addresses and verifies the detached authority signature.
     *
     * @param binding untrusted decoded or persisted binding
     * @return canonical verified binding
     */
    public ReadOnlyShadowSourceBinding verify(
            ReadOnlyShadowSourceBinding binding) {
        ReadOnlyShadowSourceBinding exact =
                Objects.requireNonNull(binding, "binding");
        exact.verify(mapper);
        VisualRunEvidenceSeal seal = exact.bindingSeal();
        if (!seal.signed()) {
            throw new Violation(Reason.UNSIGNED);
        }
        VisualEvidenceSigner.Verification verification =
                signer.verify(
                        seal,
                        exact.attestationMaterialFingerprint(mapper));
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
                exact.issuedAt().minus(MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isAfter(
                now.plus(MAXIMUM_CLOCK_SKEW))) {
            throw new Violation(Reason.SIGNING_TIME_INVALID);
        }
        return exact;
    }

    /**
     * Probes the configured source-binding authority.
     *
     * @return whether the exact signing and verification authority is usable
     */
    public boolean available() {
        try {
            return signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Closed source-binding integrity rejection vocabulary. */
    public enum Reason {
        /** Binding does not carry a detached authority signature. */
        UNSIGNED,
        /** Detached authority signature does not verify. */
        SIGNATURE_INVALID,
        /** Referenced verification key is not currently trusted. */
        KEY_UNAVAILABLE,
        /** Signature time falls outside the admitted clock window. */
        SIGNING_TIME_INVALID
    }

    /** Stable payload-free source-binding integrity rejection. */
    public static final class Violation extends RuntimeException {
        /** Closed reason retained without source payloads. */
        private final Reason reason;

        /**
         * Creates one stable integrity rejection.
         *
         * @param reason closed rejection reason
         */
        public Violation(Reason reason) {
            super("Read-only Shadow source binding rejected: "
                    + Objects.requireNonNull(reason, "reason").name());
            this.reason = reason;
        }

        /**
         * Returns the stable rejection reason.
         *
         * @return closed rejection reason
         */
        public Reason reason() {
            return reason;
        }
    }
}
