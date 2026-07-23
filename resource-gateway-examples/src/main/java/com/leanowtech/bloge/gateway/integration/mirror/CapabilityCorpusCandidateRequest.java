package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Payload-free command that freezes admitted observations into one corpus revision candidate.
 *
 * <p>The caller supplies only exact observation and admission references. Resource Gateway reads
 * their verified metadata, rechecks external source usability, derives risk signals, and builds
 * the revision. Sources must be strictly ordered by observation id so all clients produce one
 * canonical command identity.</p>
 *
 * @param schemaVersion command wire version
 * @param corpusId stable corpus identity inside the complete enterprise scope
 * @param revision positive append-only revision
 * @param expectedPredecessorRef exact previous corpus revision, absent only for revision one
 * @param capabilityRef exact capability shared by every source
 * @param sources exact admitted observation coordinates
 */
public record CapabilityCorpusCandidateRequest(
        String schemaVersion,
        String corpusId,
        long revision,
        MirrorArtifactRef expectedPredecessorRef,
        MirrorArtifactRef capabilityRef,
        List<SourceCoordinate> sources
) {
    /** Current corpus-candidate command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusCandidateRequest.v1";
    /** Maximum sources in one immutable corpus revision. */
    public static final int MAXIMUM_SOURCES = 1_000;

    /** Validates append coordinates and canonical source ordering. */
    public CapabilityCorpusCandidateRequest {
        schemaVersion = version(schemaVersion);
        corpusId = identifier(corpusId, "corpusId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (expectedPredecessorRef != null) {
            expectedPredecessorRef = ref(
                    expectedPredecessorRef,
                    CapabilityCorpusRevision.ARTIFACT_KIND,
                    "expectedPredecessorRef");
        }
        if (revision == 1 && expectedPredecessorRef != null
                || revision > 1 && (expectedPredecessorRef == null
                || !expectedPredecessorRef.id().equals(corpusId)
                || expectedPredecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "expectedPredecessorRef does not fence the previous revision");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        if (sources == null || sources.isEmpty()
                || sources.size() > MAXIMUM_SOURCES) {
            throw new IllegalArgumentException("sources size is invalid");
        }
        sources = List.copyOf(sources);
        var ids = new HashSet<String>();
        String previous = "";
        for (SourceCoordinate source : sources) {
            SourceCoordinate exact = Objects.requireNonNull(source, "source");
            String current = exact.observationRef().id();
            if (!ids.add(current) || current.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "sources must be unique and strictly ordered by observation id");
            }
            previous = current;
        }
    }

    /**
     * Exact source selected for the candidate.
     *
     * @param observationRef signed observation reference
     * @param admissionRef admitted decision reference
     */
    public record SourceCoordinate(
            MirrorArtifactRef observationRef,
            MirrorArtifactRef admissionRef
    ) {
        /** Validates the exact observation-to-admission identity. */
        public SourceCoordinate {
            observationRef = ref(
                    observationRef,
                    CapabilityObservationEnvelope.ARTIFACT_KIND,
                    "observationRef");
            admissionRef = ref(
                    admissionRef,
                    CapabilityObservationAdmission.ARTIFACT_KIND,
                    "admissionRef");
            if (!admissionRef.id().equals(observationRef.id() + ":admission")) {
                throw new IllegalArgumentException(
                        "admissionRef must belong to observationRef");
            }
        }
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported capability corpus candidate request schemaVersion");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
