package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Content-addressing, closure validation, and dual-authority signing boundary for selected
 * populations.
 *
 * <p>Local verification recomputes every chunk and root address, exact chunk reference, contiguous
 * global ordinal, cross-chunk uniqueness constraint, and unit-stratum denominator. Full
 * verification additionally invokes the independent customer selection authority. Both that
 * authority and the Resource Gateway signer must pass before a population can support completeness
 * or calibration claims.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationIntegrity {
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final VisualEvidenceSigner signer;
    private final
    AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
            authorityVerifier;
    private final Clock clock;

    /**
     * Creates a production selected-population integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param signer governed Resource Gateway signer
     * @param authorityVerifier independent customer selection authority
     */
    public AuthoritativeOutcomeSelectedPopulationIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
                    authorityVerifier) {
        this(
                mapper,
                signer,
                authorityVerifier,
                Clock.systemUTC());
    }

    /** Deterministic constructor for lifecycle, outage, and signature tests. */
    AuthoritativeOutcomeSelectedPopulationIntegrity(
            ObjectMapper mapper,
            VisualEvidenceSigner signer,
            AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
                    authorityVerifier,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.authorityVerifier = Objects.requireNonNull(
                authorityVerifier, "authorityVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Content-addresses one immutable member chunk.
     *
     * @param chunk structurally valid chunk with a blank or exact address
     * @return exact content-addressed chunk
     */
    public AuthoritativeOutcomeSelectedPopulationChunk
    sealChunk(
            AuthoritativeOutcomeSelectedPopulationChunk
                    chunk) {
        AuthoritativeOutcomeSelectedPopulationChunk source =
                Objects.requireNonNull(chunk, "chunk");
        AuthoritativeOutcomeSelectedPopulationChunk addressed =
                source.chunkFingerprint().isBlank()
                        ? source.withFingerprint(
                        source.calculateFingerprint(mapper))
                        : source;
        addressed.verify(mapper);
        return addressed;
    }

    /**
     * Independently verifies, content-addresses, and signs one complete selected population.
     *
     * @param manifest unsigned root
     * @param chunks exact ordered content-addressed member chunks
     * @return exact root carrying a verified Resource Gateway seal
     */
    public AuthoritativeOutcomeSelectedPopulationManifest
    sign(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
        AuthoritativeOutcomeSelectedPopulationManifest source =
                Objects.requireNonNull(manifest, "manifest");
        AuthoritativeOutcomeSelectedPopulationManifest timed =
                source.manifestFingerprint().isBlank()
                        && !source.manifestSeal().signed()
                        ? source.withAttestedAt(clock.instant())
                        : source;
        AuthoritativeOutcomeSelectedPopulationManifest
                addressed = timed.manifestFingerprint().isBlank()
                ? withFingerprint(
                timed,
                timed.calculateFingerprint(mapper))
                : timed;
        verifyClosure(addressed, chunks);
        verifyAuthority(addressed, chunks);
        if (addressed.manifestSeal().signed()) {
            return verify(addressed, chunks);
        }
        VisualRunEvidenceSeal seal = signer.seal(
                addressed.attestationMaterialFingerprint(mapper),
                "authoritative-outcome-selected-population:"
                        + addressed.manifestFingerprint()
                        .substring("sha256:".length()));
        return verify(
                addressed.withManifestSeal(seal),
                chunks);
    }

    /**
     * Performs full local and independent-authority verification.
     *
     * @param manifest untrusted signed root
     * @param chunks untrusted ordered member chunks
     * @return exact verified root
     */
    public AuthoritativeOutcomeSelectedPopulationManifest
    verify(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
        AuthoritativeOutcomeSelectedPopulationManifest exact =
                verifyLocally(manifest, chunks);
        verifyAuthority(exact, chunks);
        return exact;
    }

    /**
     * Verifies root/chunk closure, Resource Gateway seal, and signed time without customer I/O.
     *
     * @param manifest untrusted signed root
     * @param chunks untrusted ordered member chunks
     * @return root passing the local custody boundary
     */
    public AuthoritativeOutcomeSelectedPopulationManifest
    verifyLocally(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
        AuthoritativeOutcomeSelectedPopulationManifest exact =
                Objects.requireNonNull(manifest, "manifest");
        try {
            verifyClosure(exact, chunks);
        } catch (Violation violation) {
            throw violation;
        } catch (RuntimeException invalid) {
            throw new Violation(Reason.STRUCTURE_INVALID);
        }
        VisualRunEvidenceSeal seal = exact.manifestSeal();
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

    /** @return whether both customer selection authority and RG signing authority are usable */
    public boolean available() {
        try {
            return authorityVerifier.available()
                    && signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void verifyClosure(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    untrustedChunks) {
        manifest.verify(mapper);
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                chunks = untrustedChunks == null
                ? List.of() : List.copyOf(untrustedChunks);
        if (chunks.size() != manifest.chunks().size()) {
            throw new Violation(Reason.CHUNK_CLOSURE_INVALID);
        }
        Map<StratumKey, Long> counts = new HashMap<>();
        Set<String> positions = new HashSet<>();
        Set<String> inclusions = new HashSet<>();
        Set<String> attributions = new HashSet<>();
        for (int index = 0; index < chunks.size(); index++) {
            AuthoritativeOutcomeSelectedPopulationChunk
                    chunk = Objects.requireNonNull(
                    chunks.get(index), "chunk");
            AuthoritativeOutcomeSelectedPopulationManifest
                    .ChunkDescriptor descriptor =
                    manifest.chunks().get(index);
            chunk.verify(mapper);
            if (chunk.chunkIndex() != index
                    || !descriptor.chunkRef().equals(
                    chunk.artifactRef())
                    || chunk.firstGlobalOrdinal()
                    != descriptor.firstGlobalOrdinal()
                    || chunk.members().size()
                    != descriptor.memberCount()
                    || chunk.members().getLast()
                    .globalOrdinal()
                    != descriptor.lastGlobalOrdinal()
                    || !chunk.populationId().equals(
                    manifest.populationId())
                    || chunk.populationRevision()
                    != manifest.revision()
                    || !chunk.scope().equals(manifest.scope())
                    || !chunk.inventoryRef().equals(
                    manifest.inventoryRef())
                    || !chunk.cohortRef().equals(
                    manifest.cohortRef())
                    || !chunk.samplingFrameRef().equals(
                    manifest.samplingFrameRef())
                    || !chunk.selectedAt().equals(
                    manifest.selectedAt())) {
                throw new Violation(
                        Reason.CHUNK_CLOSURE_INVALID);
            }
            for (AuthoritativeOutcomeSelectedPopulationChunk
                    .Member member : chunk.members()) {
                StratumKey key = new StratumKey(
                        member.unitId(),
                        member.stratumId());
                String position = member.unitId() + "\u0000"
                        + member.stratumId() + "\u0000"
                        + member.sampleOrdinal();
                if (!positions.add(position)
                        || !inclusions.add(
                        member.inclusionFingerprint())
                        || !attributions.add(
                        member.attributionKeyFingerprint())) {
                    throw new Violation(
                            Reason.MEMBER_EQUIVOCATION);
                }
                counts.merge(key, 1L, Math::addExact);
            }
        }
        Map<StratumKey, Long> expected = new HashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum : manifest.strata()) {
            expected.put(
                    new StratumKey(
                            stratum.unitId(),
                            stratum.stratumId()),
                    stratum.selectedPopulationSize());
        }
        if (!counts.equals(expected)) {
            throw new Violation(
                    Reason.STRATUM_DENOMINATOR_INVALID);
        }
        for (AuthoritativeOutcomeSelectedPopulationChunk
                chunk : chunks) {
            for (AuthoritativeOutcomeSelectedPopulationChunk
                    .Member member : chunk.members()) {
                Long bound = expected.get(
                        new StratumKey(
                                member.unitId(),
                                member.stratumId()));
                if (bound == null
                        || member.sampleOrdinal() > bound) {
                    throw new Violation(
                            Reason.STRATUM_DENOMINATOR_INVALID);
                }
            }
        }
    }

    private void verifyAuthority(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
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
            authorityVerifier.verify(
                    manifest, List.copyOf(chunks));
        } catch (Violation violation) {
            throw violation;
        } catch (RuntimeException rejected) {
            throw new Violation(
                    Reason.AUTHORITY_REJECTED);
        }
    }

    private static
    AuthoritativeOutcomeSelectedPopulationManifest
    withFingerprint(
            AuthoritativeOutcomeSelectedPopulationManifest
                    source,
            String fingerprint) {
        return new AuthoritativeOutcomeSelectedPopulationManifest(
                source.schemaVersion(),
                source.populationId(),
                source.revision(),
                fingerprint,
                source.scope(),
                source.inventoryRef(),
                source.cohortRef(),
                source.samplingFrameRef(),
                source.selectionPolicyRef(),
                source.selectionAuthoritySetRef(),
                source.selectionAttestationRef(),
                source.selectedAt(),
                source.strata(),
                source.chunks(),
                source.totalEligiblePopulation(),
                source.totalSelectedPopulation(),
                source.attestedAt(),
                VisualRunEvidenceSeal.unsigned());
    }

    private record StratumKey(
            String unitId,
            String stratumId
    ) {
    }

    /** Closed payload-free selected-population rejection vocabulary. */
    public enum Reason {
        AUTHORITY_UNAVAILABLE,
        AUTHORITY_REJECTED,
        UNSIGNED,
        SIGNATURE_INVALID,
        KEY_UNAVAILABLE,
        SIGNING_TIME_INVALID,
        STRUCTURE_INVALID,
        CHUNK_CLOSURE_INVALID,
        MEMBER_EQUIVOCATION,
        STRATUM_DENOMINATOR_INVALID
    }

    /** Stable payload-free selected-population integrity failure. */
    public static final class Violation
            extends RuntimeException {
        private final Reason reason;

        /** Creates one stable integrity violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome selected population rejected: "
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
