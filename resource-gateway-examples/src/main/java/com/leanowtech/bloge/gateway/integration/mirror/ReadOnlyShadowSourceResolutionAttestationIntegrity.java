package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Content-addressing and detached-signature boundary for source-resolution attestations.
 */
public final class ReadOnlyShadowSourceResolutionAttestationIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a source-resolution attestation integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed source-resolution authority
     * @param clock trusted verification clock
     */
    public ReadOnlyShadowSourceResolutionAttestationIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Addresses, signs, and immediately verifies one unsigned attestation.
     *
     * @param attestation structurally valid unsigned attestation
     * @return verified immutable attestation
     */
    public ReadOnlyShadowSourceResolutionAttestation sign(
            ReadOnlyShadowSourceResolutionAttestation attestation) {
        ReadOnlyShadowSourceResolutionAttestation source =
                Objects.requireNonNull(
                        attestation, "attestation");
        if (source.attestationSeal().signed()) {
            return verify(source);
        }
        ReadOnlyShadowSourceResolutionAttestation addressed =
                source.attestationFingerprint().isBlank()
                        ? source.withFingerprint(
                        source.calculateFingerprint(mapper))
                        : source;
        addressed.verify(mapper);
        VisualRunEvidenceSeal seal = signer.seal(
                addressed.attestationMaterialFingerprint(mapper),
                "read-only-shadow-source-resolution:"
                        + addressed.attestationFingerprint()
                        .substring("sha256:".length()));
        return verify(addressed.withSeal(seal));
    }

    /**
     * Recomputes the content address and verifies the detached authority signature.
     *
     * @param attestation untrusted decoded or persisted attestation
     * @return canonical verified attestation
     */
    public ReadOnlyShadowSourceResolutionAttestation verify(
            ReadOnlyShadowSourceResolutionAttestation attestation) {
        ReadOnlyShadowSourceResolutionAttestation exact =
                Objects.requireNonNull(
                        attestation, "attestation");
        exact.verify(mapper);
        VisualRunEvidenceSeal seal = exact.attestationSeal();
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
     * Probes the configured source-resolution authority.
     *
     * @return whether signing and verification authority is usable
     */
    public boolean available() {
        try {
            return signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Closed source-resolution integrity rejection vocabulary. */
    public enum Reason {
        /** Attestation does not carry a detached signature. */
        UNSIGNED,
        /** Detached signature does not verify. */
        SIGNATURE_INVALID,
        /** Referenced verification key is unavailable or untrusted. */
        KEY_UNAVAILABLE,
        /** Signing time falls outside the admitted clock window. */
        SIGNING_TIME_INVALID
    }

    /** Stable payload-free source-resolution integrity rejection. */
    public static final class Violation extends RuntimeException {
        /** Closed reason retained without source payloads. */
        private final Reason reason;

        /**
         * Creates one stable integrity rejection.
         *
         * @param reason closed rejection reason
         */
        public Violation(Reason reason) {
            super("Read-only Shadow source resolution rejected: "
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
