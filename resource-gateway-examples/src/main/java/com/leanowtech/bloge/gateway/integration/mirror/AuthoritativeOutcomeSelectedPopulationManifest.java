package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed root of trust for every member selected into one authoritative outcome population.
 *
 * <p>The manifest binds an owner-versioned selection policy and independently governed selection
 * attestation to exact pre-treatment cohort, sampling-frame, inventory, unit-stratum arithmetic,
 * and content-addressed member chunks. It proves the denominator that must be reconciled; it does
 * not claim that observations arrived or that a business outcome matched.</p>
 *
 * @param schemaVersion exact manifest protocol version
 * @param populationId stable selected-population identity
 * @param revision positive immutable revision
 * @param manifestFingerprint canonical content address excluding this field and the seal
 * @param scope exact enterprise namespace
 * @param inventoryRef exact owner-approved Fidelity inventory
 * @param cohortRef exact immutable calibration cohort
 * @param samplingFrameRef exact eligible-population snapshot
 * @param selectionPolicyRef exact owner-versioned pre-treatment selection policy
 * @param selectionAuthoritySetRef exact independently governed selection authority membership
 * @param selectionAttestationRef exact external authority statement over the selected population
 * @param selectedAt pre-treatment selection cut
 * @param strata complete canonical unit-stratum denominator
 * @param chunks complete canonical member-chunk closure
 * @param totalEligiblePopulation exact sum of stratum eligible populations
 * @param totalSelectedPopulation exact sum of stratum selected populations and chunk members
 * @param attestedAt Resource Gateway attestation time
 * @param manifestSeal detached Resource Gateway signature over the content address
 */
public record AuthoritativeOutcomeSelectedPopulationManifest(
        String schemaVersion,
        String populationId,
        long revision,
        String manifestFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        MirrorArtifactRef cohortRef,
        MirrorArtifactRef samplingFrameRef,
        MirrorArtifactRef selectionPolicyRef,
        MirrorArtifactRef selectionAuthoritySetRef,
        MirrorArtifactRef selectionAttestationRef,
        Instant selectedAt,
        List<Stratum> strata,
        List<ChunkDescriptor> chunks,
        long totalEligiblePopulation,
        long totalSelectedPopulation,
        Instant attestedAt,
        VisualRunEvidenceSeal manifestSeal
) {
    /** Current selected-population root wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationManifest.v1";
    /** Artifact kind linked by completeness assessments and calibration evidence. */
    public static final String ARTIFACT_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST";
    /** Maximum unit-stratum coordinates admitted by one root. */
    public static final int MAXIMUM_STRATA = 65_536;
    /** Maximum independently addressable chunks admitted by one root. */
    public static final int MAXIMUM_CHUNKS = 4_096;
    /** Maximum members represented by one manifest revision. */
    public static final long MAXIMUM_SELECTED_POPULATION =
            (long) MAXIMUM_CHUNKS
                    * AuthoritativeOutcomeSelectedPopulationChunk
                    .MAXIMUM_MEMBERS;
    /** Maximum canonical root bytes admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            8 * 1024 * 1024;
    /** Maximum domain-separated signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact arithmetic, canonical ordering, and contiguous chunk coverage. */
    public AuthoritativeOutcomeSelectedPopulationManifest {
        schemaVersion = version(schemaVersion);
        populationId = identifier(
                populationId, "populationId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "selected population revision must be positive");
        }
        manifestFingerprint = optionalFingerprint(
                manifestFingerprint,
                "manifestFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        inventoryRef = requireKind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        cohortRef = requireKind(
                cohortRef,
                "OUTCOME_CALIBRATION_COHORT",
                "cohortRef");
        samplingFrameRef = requireKind(
                samplingFrameRef,
                "OUTCOME_SAMPLING_FRAME",
                "samplingFrameRef");
        selectionPolicyRef = requireKind(
                selectionPolicyRef,
                "OUTCOME_SELECTION_POLICY",
                "selectionPolicyRef");
        selectionAuthoritySetRef = requireKind(
                selectionAuthoritySetRef,
                "OUTCOME_SELECTION_AUTHORITY_SET",
                "selectionAuthoritySetRef");
        selectionAttestationRef = requireKind(
                selectionAttestationRef,
                "OUTCOME_SELECTION_ATTESTATION",
                "selectionAttestationRef");
        selectedAt = Objects.requireNonNull(
                selectedAt, "selectedAt");
        attestedAt = Objects.requireNonNull(
                attestedAt, "attestedAt");
        if (attestedAt.isBefore(selectedAt)) {
            throw new IllegalArgumentException(
                    "selected population attestation cannot precede selection");
        }
        strata = strata == null
                ? List.of() : List.copyOf(strata);
        chunks = chunks == null
                ? List.of() : List.copyOf(chunks);
        validateStrata(
                strata,
                totalEligiblePopulation,
                totalSelectedPopulation);
        validateChunks(
                chunks, totalSelectedPopulation);
        manifestSeal = manifestSeal == null
                ? VisualRunEvidenceSeal.unsigned()
                : manifestSeal;
    }

    /**
     * Complete denominator shape for one inventory unit and sampling stratum.
     *
     * @param unitId exact Fidelity inventory unit
     * @param stratumId exact owner-defined sampling stratum
     * @param eligiblePopulationSize complete eligible population at the selection cut
     * @param selectedPopulationSize exact selected member count
     * @param selectionMode closed pre-treatment selection algorithm
     */
    public record Stratum(
            String unitId,
            String stratumId,
            long eligiblePopulationSize,
            long selectedPopulationSize,
            AuthoritativeOutcomeObservation.SelectionMode
                    selectionMode
    ) {
        /** Validates one non-empty bounded stratum denominator. */
        public Stratum {
            unitId = identifier(unitId, "unitId");
            stratumId = identifier(
                    stratumId, "stratumId");
            selectionMode = Objects.requireNonNull(
                    selectionMode, "selectionMode");
            if (eligiblePopulationSize < 1
                    || selectedPopulationSize < 1
                    || selectedPopulationSize
                    > eligiblePopulationSize
                    || selectionMode
                    == AuthoritativeOutcomeObservation
                    .SelectionMode.CENSUS
                    && selectedPopulationSize
                    != eligiblePopulationSize) {
                throw new IllegalArgumentException(
                        "selected population stratum arithmetic is invalid");
            }
        }
    }

    /**
     * Exact reference and ordinal range for one member chunk.
     *
     * @param chunkIndex zero-based canonical chunk order
     * @param chunkRef exact content-addressed member chunk
     * @param firstGlobalOrdinal one-based first member ordinal
     * @param lastGlobalOrdinal one-based inclusive last member ordinal
     * @param memberCount exact chunk member count
     */
    public record ChunkDescriptor(
            int chunkIndex,
            MirrorArtifactRef chunkRef,
            long firstGlobalOrdinal,
            long lastGlobalOrdinal,
            int memberCount
    ) {
        /** Validates one bounded internally consistent chunk range. */
        public ChunkDescriptor {
            if (chunkIndex < 0
                    || firstGlobalOrdinal < 1
                    || lastGlobalOrdinal
                    < firstGlobalOrdinal
                    || memberCount < 1
                    || memberCount
                    > AuthoritativeOutcomeSelectedPopulationChunk
                    .MAXIMUM_MEMBERS
                    || Math.addExact(
                    firstGlobalOrdinal,
                    memberCount - 1L)
                    != lastGlobalOrdinal) {
                throw new IllegalArgumentException(
                        "selected population chunk descriptor is invalid");
            }
            chunkRef = requireKind(
                    chunkRef,
                    AuthoritativeOutcomeSelectedPopulationChunk
                            .ARTIFACT_KIND,
                    "chunkRef");
        }
    }

    /**
     * Recomputes protocol semantics and the root content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (manifestFingerprint.isBlank()
                || !manifestFingerprint.equals(
                calculateFingerprint(mapper))) {
            throw new IllegalArgumentException(
                    "selected population manifest fingerprint mismatch");
        }
    }

    /**
     * Calculates the root address with address and detached seal blanked.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 content address
     */
    public String calculateFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprintAndSeal(
                        "",
                        VisualRunEvidenceSeal.unsigned()),
                MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Returns domain-separated Resource Gateway signing material.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 attestation material
     */
    public String attestationMaterialFingerprint(
            ObjectMapper mapper) {
        if (manifestFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected population manifest must be content-addressed before signing");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new AttestationMaterial(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_V1",
                        schemaVersion,
                        populationId,
                        revision,
                        inventoryRef,
                        cohortRef,
                        samplingFrameRef,
                        selectedAt,
                        attestedAt,
                        manifestFingerprint),
                MAXIMUM_ATTESTATION_BYTES);
    }

    /**
     * Calculates a stable connector-ingestion identity before Resource Gateway attestation.
     *
     * <p>The material excludes only the Resource Gateway content address, trusted
     * {@code attestedAt}, and detached seal. A response-loss retry of the same authority closure
     * therefore resolves to the first signed revision, while any denominator, policy, authority,
     * chunk, or member-set change conflicts.</p>
     *
     * @param mapper canonical protocol mapper
     * @return domain-separated immutable ingestion fingerprint
     */
    public String ingestionMaterialFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new IngestionMaterial(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_INGESTION_V1",
                        schemaVersion,
                        populationId,
                        revision,
                        scope,
                        inventoryRef,
                        cohortRef,
                        samplingFrameRef,
                        selectionPolicyRef,
                        selectionAuthoritySetRef,
                        selectionAttestationRef,
                        selectedAt,
                        strata,
                        chunks,
                        totalEligiblePopulation,
                        totalSelectedPopulation),
                MAXIMUM_CANONICAL_BYTES);
    }

    /** @return exact selected-population root reference after signing */
    public MirrorArtifactRef artifactRef() {
        if (manifestFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected population manifest is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                populationId,
                revision,
                manifestFingerprint);
    }

    /**
     * Attaches a detached Resource Gateway signature.
     *
     * @param seal governed producer seal
     * @return identical manifest carrying the seal
     */
    public AuthoritativeOutcomeSelectedPopulationManifest
    withManifestSeal(VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                manifestFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    /**
     * Replaces the provisional Resource Gateway attestation time.
     *
     * @param value trusted signing-intent time
     * @return unsigned manifest carrying the exact time
     */
    AuthoritativeOutcomeSelectedPopulationManifest
    withAttestedAt(Instant value) {
        return new AuthoritativeOutcomeSelectedPopulationManifest(
                schemaVersion,
                populationId,
                revision,
                "",
                scope,
                inventoryRef,
                cohortRef,
                samplingFrameRef,
                selectionPolicyRef,
                selectionAuthoritySetRef,
                selectionAttestationRef,
                selectedAt,
                strata,
                chunks,
                totalEligiblePopulation,
                totalSelectedPopulation,
                Objects.requireNonNull(value, "value"),
                VisualRunEvidenceSeal.unsigned());
    }

    /** Keeps customer member fingerprints out of generic logs. */
    @Override
    public String toString() {
        return "AuthoritativeOutcomeSelectedPopulationManifest[populationId="
                + populationId + ", revision=" + revision
                + ", strata=" + strata.size()
                + ", chunks=" + chunks.size()
                + ", selected=" + totalSelectedPopulation + "]";
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
    withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new AuthoritativeOutcomeSelectedPopulationManifest(
                schemaVersion,
                populationId,
                revision,
                fingerprint,
                scope,
                inventoryRef,
                cohortRef,
                samplingFrameRef,
                selectionPolicyRef,
                selectionAuthoritySetRef,
                selectionAttestationRef,
                selectedAt,
                strata,
                chunks,
                totalEligiblePopulation,
                totalSelectedPopulation,
                attestedAt,
                seal);
    }

    private static void validateStrata(
            List<Stratum> values,
            long eligible,
            long selected) {
        if (values.isEmpty()
                || values.size() > MAXIMUM_STRATA) {
            throw new IllegalArgumentException(
                    "selected population strata must be non-empty and bounded");
        }
        long derivedEligible = 0;
        long derivedSelected = 0;
        Stratum previous = null;
        for (Stratum value : values) {
            Stratum exact = Objects.requireNonNull(
                    value, "stratum");
            if (previous != null
                    && compareStrata(previous, exact) >= 0) {
                throw new IllegalArgumentException(
                        "selected population strata must be unique and canonically ordered");
            }
            derivedEligible = Math.addExact(
                    derivedEligible,
                    exact.eligiblePopulationSize());
            derivedSelected = Math.addExact(
                    derivedSelected,
                    exact.selectedPopulationSize());
            previous = exact;
        }
        if (eligible < 1
                || selected < 1
                || selected > eligible
                || selected > MAXIMUM_SELECTED_POPULATION
                || derivedEligible != eligible
                || derivedSelected != selected) {
            throw new IllegalArgumentException(
                    "selected population totals are not derived from strata");
        }
    }

    private static void validateChunks(
            List<ChunkDescriptor> values,
            long selected) {
        if (values.isEmpty()
                || values.size() > MAXIMUM_CHUNKS) {
            throw new IllegalArgumentException(
                    "selected population chunks must be non-empty and bounded");
        }
        Set<MirrorArtifactRef> references = new HashSet<>();
        long nextOrdinal = 1;
        long members = 0;
        for (int index = 0; index < values.size(); index++) {
            ChunkDescriptor value = Objects.requireNonNull(
                    values.get(index), "chunkDescriptor");
            if (value.chunkIndex() != index
                    || value.firstGlobalOrdinal()
                    != nextOrdinal
                    || !references.add(value.chunkRef())) {
                throw new IllegalArgumentException(
                        "selected population chunks must uniquely and contiguously cover the denominator");
            }
            nextOrdinal = Math.addExact(
                    value.lastGlobalOrdinal(), 1);
            members = Math.addExact(
                    members, value.memberCount());
        }
        if (members != selected
                || nextOrdinal != selected + 1) {
            throw new IllegalArgumentException(
                    "selected population chunk members do not equal the selected denominator");
        }
    }

    private static int compareStrata(
            Stratum left, Stratum right) {
        return Comparator.comparing(Stratum::unitId)
                .thenComparing(Stratum::stratumId)
                .compare(left, right);
    }

    private static String version(String value) {
        String normalized = value == null
                || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported selected population manifest schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a bounded identifier");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or a canonical SHA-256 fingerprint");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef reference,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(reference, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }

    private record AttestationMaterial(
            String domain,
            String schemaVersion,
            String populationId,
            long revision,
            MirrorArtifactRef inventoryRef,
            MirrorArtifactRef cohortRef,
            MirrorArtifactRef samplingFrameRef,
            Instant selectedAt,
            Instant attestedAt,
            String manifestFingerprint
    ) {
    }

    private record IngestionMaterial(
            String domain,
            String schemaVersion,
            String populationId,
            long revision,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef inventoryRef,
            MirrorArtifactRef cohortRef,
            MirrorArtifactRef samplingFrameRef,
            MirrorArtifactRef selectionPolicyRef,
            MirrorArtifactRef selectionAuthoritySetRef,
            MirrorArtifactRef selectionAttestationRef,
            Instant selectedAt,
            List<Stratum> strata,
            List<ChunkDescriptor> chunks,
            long totalEligiblePopulation,
            long totalSelectedPopulation
    ) {
    }
}
