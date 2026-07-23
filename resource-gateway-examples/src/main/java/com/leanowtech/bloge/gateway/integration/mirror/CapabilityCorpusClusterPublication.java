package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable owner-reviewed serving publication for one validated recorded cluster.
 *
 * <p>The publication is payload-free and freezes the exact current corpus, external validation,
 * owner policy, representative source, support set, deterministic match dimensions, identity
 * projections, holdout counts, and confidence interval. Runtime serving must still revalidate all
 * current heads, grants, retention, source lifecycle, validation authority, and payload content
 * addresses before using the cluster.</p>
 *
 * @param schemaVersion cluster publication wire version
 * @param clusterFingerprint canonical artifact fingerprint
 * @param sourceCommandFingerprint canonical publish-command fingerprint
 * @param scope complete enterprise scope
 * @param clusterId stable cluster identity
 * @param revision positive append-only cluster revision
 * @param predecessorRef exact previous cluster publication
 * @param capabilityRef exact capability
 * @param corpusPublicationRef exact corpus serving publication
 * @param corpusRevisionRef exact immutable corpus revision
 * @param publicationPolicyRef exact corpus publication policy
 * @param clusterPolicyRef exact cluster generalization policy
 * @param validationRef exact external validation proof
 * @param representativeSource exact recorded representative
 * @param members exact supporting observation sources
 * @param matchRequestPointers request dimensions requiring exact equality
 * @param identityMode response identity-safety strategy
 * @param identityProjections deterministic request-to-response identity projections
 * @param distinctIdentityCount measured distinct identities
 * @param holdout independent holdout precision assessment
 * @param confidence independently recomputable Wilson precision interval
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality approval reason
 * @param reviewedBy authenticated reviewer identity
 * @param publishedAt trusted local publication time
 * @param usableUntil exclusive serving horizon
 */
public record CapabilityCorpusClusterPublication(
        String schemaVersion,
        String clusterFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String clusterId,
        long revision,
        MirrorArtifactRef predecessorRef,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef corpusPublicationRef,
        MirrorArtifactRef corpusRevisionRef,
        MirrorArtifactRef publicationPolicyRef,
        MirrorArtifactRef clusterPolicyRef,
        MirrorArtifactRef validationRef,
        CapabilityCorpusClusterValidation.SourceCoordinate representativeSource,
        List<CapabilityCorpusClusterValidation.SourceCoordinate> members,
        List<String> matchRequestPointers,
        CapabilityCorpusClusterValidation.IdentityMode identityMode,
        List<CapabilityCorpusClusterValidation.IdentityProjection> identityProjections,
        int distinctIdentityCount,
        CapabilityCorpusClusterValidation.HoldoutAssessment holdout,
        ArtifactProvenance.Confidence confidence,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode,
        String reviewedBy,
        Instant publishedAt,
        Instant usableUntil
) {
    /** Current cluster publication wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusClusterPublication.v1";
    /** Artifact kind used by exact cluster publication references. */
    public static final String ARTIFACT_KIND =
            "CAPABILITY_CORPUS_CLUSTER_PUBLICATION";

    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Reuses validation invariants while enforcing immutable publication lineage. */
    public CapabilityCorpusClusterPublication {
        schemaVersion = version(schemaVersion);
        clusterFingerprint = fingerprint(
                clusterFingerprint, "clusterFingerprint");
        sourceCommandFingerprint = fingerprint(
                sourceCommandFingerprint, "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        clusterId = identifier(clusterId, "clusterId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (predecessorRef != null) {
            predecessorRef = ref(
                    predecessorRef, ARTIFACT_KIND, "predecessorRef");
        }
        if (revision == 1 && predecessorRef != null
                || revision > 1 && (predecessorRef == null
                || !predecessorRef.id().equals(clusterId)
                || predecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "predecessorRef does not describe the previous cluster");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        corpusPublicationRef = ref(
                corpusPublicationRef,
                CapabilityCorpusPublication.ARTIFACT_KIND,
                "corpusPublicationRef");
        corpusRevisionRef = ref(
                corpusRevisionRef,
                CapabilityCorpusRevision.ARTIFACT_KIND,
                "corpusRevisionRef");
        if (!corpusPublicationRef.id().equals(corpusRevisionRef.id())) {
            throw new IllegalArgumentException(
                    "corpus publication and revision must belong to one corpus");
        }
        publicationPolicyRef = ref(
                publicationPolicyRef,
                "CORPUS_PUBLICATION_POLICY",
                "publicationPolicyRef");
        clusterPolicyRef = ref(
                clusterPolicyRef, "CORPUS_CLUSTER_POLICY", "clusterPolicyRef");
        validationRef = ref(
                validationRef,
                CapabilityCorpusClusterValidation.ARTIFACT_KIND,
                "validationRef");
        CapabilityCorpusClusterValidation structuralValidation =
                new CapabilityCorpusClusterValidation(
                        CapabilityCorpusClusterValidation.SCHEMA_VERSION,
                        validationRef.fingerprint(),
                        scope,
                        validationRef.id(),
                        validationRef.revision(),
                        capabilityRef,
                        corpusPublicationRef,
                        corpusRevisionRef,
                        representativeSource,
                        members,
                        matchRequestPointers,
                        identityMode,
                        identityProjections,
                        distinctIdentityCount,
                        holdout,
                        confidence,
                        true,
                        "frozen-publication-validation",
                        Objects.requireNonNull(publishedAt, "publishedAt"),
                        Objects.requireNonNull(usableUntil, "usableUntil"));
        representativeSource = structuralValidation.representativeSource();
        members = structuralValidation.members();
        matchRequestPointers = structuralValidation.matchRequestPointers();
        identityMode = structuralValidation.identityMode();
        identityProjections = structuralValidation.identityProjections();
        holdout = structuralValidation.holdout();
        confidence = structuralValidation.confidence();
        reviewTicketRef = ref(
                reviewTicketRef, "GOVERNANCE_REVIEW_TICKET", "reviewTicketRef");
        reasonCode = reason(reasonCode);
        reviewedBy = identifier(reviewedBy, "reviewedBy");
        if (!usableUntil.isAfter(publishedAt)) {
            throw new IllegalArgumentException(
                    "cluster is outside its serving horizon");
        }
    }

    /**
     * Returns the immutable cluster publication reference.
     *
     * @return exact content-addressed cluster reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, clusterId, revision, clusterFingerprint);
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
                    "unsupported corpus cluster publication schemaVersion");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String reason(String value) {
        String exact = value == null ? "" : value.trim();
        if (!REASON.matcher(exact).matches()) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        return exact;
    }
}
