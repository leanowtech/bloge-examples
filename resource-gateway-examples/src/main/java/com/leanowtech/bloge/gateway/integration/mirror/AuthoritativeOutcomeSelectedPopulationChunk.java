package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed, payload-free member page for one authoritative outcome selected population.
 *
 * <p>A chunk freezes exact pre-treatment inclusion coordinates without carrying a customer
 * identifier or business payload. Global ordinals are contiguous across chunks; unit, stratum,
 * sample ordinal, inclusion, subject, and attribution fingerprints let the completeness projector
 * match every selected member to one outcome observation without trusting arrival order.</p>
 *
 * @param schemaVersion exact chunk protocol version
 * @param chunkId stable chunk identity
 * @param chunkFingerprint canonical content address with this field blanked
 * @param populationId stable selected-population identity
 * @param populationRevision positive immutable population revision
 * @param scope exact enterprise namespace
 * @param inventoryRef exact owner-approved Fidelity inventory
 * @param cohortRef exact immutable calibration cohort
 * @param samplingFrameRef exact eligible-population snapshot
 * @param selectedAt pre-treatment selection cut
 * @param chunkIndex zero-based chunk order
 * @param firstGlobalOrdinal one-based first selected-population ordinal
 * @param members complete ordered members in this chunk
 */
public record AuthoritativeOutcomeSelectedPopulationChunk(
        String schemaVersion,
        String chunkId,
        String chunkFingerprint,
        String populationId,
        long populationRevision,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        MirrorArtifactRef cohortRef,
        MirrorArtifactRef samplingFrameRef,
        java.time.Instant selectedAt,
        int chunkIndex,
        long firstGlobalOrdinal,
        List<Member> members
) {
    /** Current immutable selected-population chunk wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationChunk.v1";
    /** Artifact kind referenced by selected-population manifests. */
    public static final String ARTIFACT_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_CHUNK";
    /** Maximum members in one independently addressable chunk. */
    public static final int MAXIMUM_MEMBERS = 4_096;
    /** Maximum canonical bytes admitted to one chunk content address. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            4 * 1024 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces one ordered, duplicate-free, payload-free member page. */
    public AuthoritativeOutcomeSelectedPopulationChunk {
        schemaVersion = version(schemaVersion);
        chunkId = identifier(chunkId, "chunkId");
        chunkFingerprint = optionalFingerprint(
                chunkFingerprint, "chunkFingerprint");
        populationId = identifier(
                populationId, "populationId");
        if (populationRevision < 1) {
            throw new IllegalArgumentException(
                    "selected population revision must be positive");
        }
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
        selectedAt = Objects.requireNonNull(
                selectedAt, "selectedAt");
        if (chunkIndex < 0 || firstGlobalOrdinal < 1) {
            throw new IllegalArgumentException(
                    "selected population chunk coordinates are invalid");
        }
        members = members == null
                ? List.of() : List.copyOf(members);
        if (members.isEmpty()
                || members.size() > MAXIMUM_MEMBERS) {
            throw new IllegalArgumentException(
                    "selected population chunk members must be non-empty and bounded");
        }
        Set<String> samplePositions = new HashSet<>();
        Set<String> inclusions = new HashSet<>();
        Set<String> attributions = new HashSet<>();
        for (int index = 0; index < members.size(); index++) {
            Member member = Objects.requireNonNull(
                    members.get(index), "member");
            long expectedOrdinal = Math.addExact(
                    firstGlobalOrdinal, index);
            String position = member.unitId() + "\u0000"
                    + member.stratumId() + "\u0000"
                    + member.sampleOrdinal();
            if (member.globalOrdinal() != expectedOrdinal
                    || !samplePositions.add(position)
                    || !inclusions.add(
                    member.inclusionFingerprint())
                    || !attributions.add(
                    member.attributionKeyFingerprint())) {
                throw new IllegalArgumentException(
                        "selected population members must be contiguous and uniquely addressable");
            }
        }
    }

    /**
     * One selected member address, free of customer identifiers and business payloads.
     *
     * @param globalOrdinal one-based position across the complete population
     * @param unitId exact Fidelity inventory unit
     * @param stratumId exact owner-defined sampling stratum
     * @param sampleOrdinal one-based position within the unit stratum
     * @param inclusionFingerprint domain-separated deterministic inclusion material
     * @param subjectFingerprint domain-separated subject identity
     * @param attributionKeyFingerprint domain-separated action-to-outcome correlation identity
     */
    public record Member(
            long globalOrdinal,
            String unitId,
            String stratumId,
            long sampleOrdinal,
            String inclusionFingerprint,
            String subjectFingerprint,
            String attributionKeyFingerprint
    ) {
        /** Validates one bounded payload-free member coordinate. */
        public Member {
            if (globalOrdinal < 1 || sampleOrdinal < 1) {
                throw new IllegalArgumentException(
                        "selected population member ordinals must be positive");
            }
            unitId = identifier(unitId, "unitId");
            stratumId = identifier(
                    stratumId, "stratumId");
            inclusionFingerprint = fingerprint(
                    inclusionFingerprint,
                    "inclusionFingerprint");
            subjectFingerprint = fingerprint(
                    subjectFingerprint,
                    "subjectFingerprint");
            attributionKeyFingerprint = fingerprint(
                    attributionKeyFingerprint,
                    "attributionKeyFingerprint");
        }
    }

    /**
     * Recomputes this chunk content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (chunkFingerprint.isBlank()
                || !chunkFingerprint.equals(
                calculateFingerprint(mapper))) {
            throw new IllegalArgumentException(
                    "selected population chunk fingerprint mismatch");
        }
    }

    /**
     * Calculates the canonical content address with the address blanked.
     *
     * @param mapper canonical protocol mapper
     * @return canonical SHA-256 content address
     */
    public String calculateFingerprint(ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Returns an identical chunk carrying a replacement address.
     *
     * @param value canonical address or blank before sealing
     * @return content-identical chunk
     */
    public AuthoritativeOutcomeSelectedPopulationChunk
    withFingerprint(String value) {
        return new AuthoritativeOutcomeSelectedPopulationChunk(
                schemaVersion,
                chunkId,
                value,
                populationId,
                populationRevision,
                scope,
                inventoryRef,
                cohortRef,
                samplingFrameRef,
                selectedAt,
                chunkIndex,
                firstGlobalOrdinal,
                members);
    }

    /** @return exact chunk reference after content addressing */
    public MirrorArtifactRef artifactRef() {
        if (chunkFingerprint.isBlank()) {
            throw new IllegalStateException(
                    "selected population chunk is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                chunkId,
                1,
                chunkFingerprint);
    }

    /** Keeps subject and attribution fingerprints out of generic logs. */
    @Override
    public String toString() {
        return "AuthoritativeOutcomeSelectedPopulationChunk[chunkId="
                + chunkId + ", populationId=" + populationId
                + ", populationRevision=" + populationRevision
                + ", chunkIndex=" + chunkIndex
                + ", members=" + members.size() + "]";
    }

    private static String version(String value) {
        String normalized = value == null
                || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported selected population chunk schemaVersion");
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

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
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
}
