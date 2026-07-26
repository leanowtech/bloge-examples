package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Domain-separated Resource Gateway signing and independent legal-authority verification boundary
 * for selected-member dispositions.
 */
public final class
AuthoritativeOutcomeSelectedPopulationDispositionIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final
    AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
            authorityVerifier;
    private final Clock clock;

    /**
     * Creates a production disposition integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed Resource Gateway signer
     * @param authorityVerifier independent retention and deletion authority
     */
    public AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
                    authorityVerifier) {
        this(
                mapper,
                signer,
                authorityVerifier,
                Clock.systemUTC());
    }

    /** Deterministic constructor for authority, signature, and clock tests. */
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
                    authorityVerifier,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.authorityVerifier = Objects.requireNonNull(
                authorityVerifier, "authorityVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Independently verifies, content-addresses, and signs one legal disposition.
     *
     * @param disposition unsigned authority disposition
     * @return exact disposition carrying a verified Resource Gateway seal
     */
    public AuthoritativeOutcomeSelectedPopulationDisposition
    sign(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
        AuthoritativeOutcomeSelectedPopulationDisposition source =
                Objects.requireNonNull(
                        disposition, "disposition");
        AuthoritativeOutcomeSelectedPopulationDisposition timed =
                source.dispositionFingerprint().isBlank()
                        && !source.dispositionSeal().signed()
                        ? source.withAttestedAt(clock.instant())
                        : source;
        AuthoritativeOutcomeSelectedPopulationDisposition
                addressed =
                timed.dispositionFingerprint().isBlank()
                        ? withFingerprint(
                        timed,
                        timed.calculateFingerprint(mapper))
                        : timed;
        addressed.verify(mapper);
        verifyAuthority(addressed);
        if (addressed.dispositionSeal().signed()) {
            return verify(addressed);
        }
        VisualRunEvidenceSeal seal = signer.seal(
                addressed.attestationMaterialFingerprint(mapper),
                "authoritative-outcome-member-disposition:"
                        + addressed.dispositionFingerprint()
                        .substring("sha256:".length()));
        return verify(
                addressed.withDispositionSeal(seal));
    }

    /**
     * Performs local Resource Gateway and independent legal-authority verification.
     *
     * @param disposition untrusted signed disposition
     * @return exact verified disposition
     */
    public AuthoritativeOutcomeSelectedPopulationDisposition
    verify(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
        AuthoritativeOutcomeSelectedPopulationDisposition exact =
                verifyLocally(disposition);
        verifyAuthority(exact);
        return exact;
    }

    /**
     * Verifies content address, Resource Gateway seal, and signed time without customer I/O.
     *
     * @param disposition untrusted signed disposition
     * @return disposition passing the local custody boundary
     */
    public AuthoritativeOutcomeSelectedPopulationDisposition
    verifyLocally(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
        AuthoritativeOutcomeSelectedPopulationDisposition exact =
                Objects.requireNonNull(
                        disposition, "disposition");
        try {
            exact.verify(mapper);
        } catch (RuntimeException invalid) {
            throw new Violation(Reason.STRUCTURE_INVALID);
        }
        VisualRunEvidenceSeal seal = exact.dispositionSeal();
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
        return exact;
    }

    /** @return whether both external deletion authority and RG signer are usable */
    public boolean available() {
        try {
            return authorityVerifier.available()
                    && signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void verifyAuthority(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition) {
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
            authorityVerifier.verify(disposition);
        } catch (Violation violation) {
            throw violation;
        } catch (RuntimeException rejected) {
            throw new Violation(
                    Reason.AUTHORITY_REJECTED);
        }
    }

    private static
    AuthoritativeOutcomeSelectedPopulationDisposition
    withFingerprint(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    source,
            String fingerprint) {
        return new AuthoritativeOutcomeSelectedPopulationDisposition(
                source.schemaVersion(),
                source.dispositionId(),
                source.revision(),
                fingerprint,
                source.scope(),
                source.populationRef(),
                source.unitId(),
                source.stratumId(),
                source.sampleOrdinal(),
                source.inclusionFingerprint(),
                source.subjectFingerprint(),
                source.attributionKeyFingerprint(),
                source.disposition(),
                source.reason(),
                source.retentionPolicyRef(),
                source.deletionApprovalRef(),
                source.deletionAuthoritySetRef(),
                source.effectiveAt(),
                source.attestedAt(),
                VisualRunEvidenceSeal.unsigned());
    }

    /** Closed payload-free legal-disposition rejection vocabulary. */
    public enum Reason {
        AUTHORITY_UNAVAILABLE,
        AUTHORITY_REJECTED,
        UNSIGNED,
        SIGNATURE_INVALID,
        KEY_UNAVAILABLE,
        SIGNING_TIME_INVALID,
        STRUCTURE_INVALID
    }

    /** Stable payload-free legal-disposition integrity failure. */
    public static final class Violation
            extends RuntimeException {
        private final Reason reason;

        /** Creates one stable integrity violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome selected member disposition rejected: "
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
