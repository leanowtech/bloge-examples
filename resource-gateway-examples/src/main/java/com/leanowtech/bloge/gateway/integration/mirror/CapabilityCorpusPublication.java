package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable owner-reviewed serving publication for one exact corpus revision.
 *
 * <p>Resolvers may consume only the latest verified publication, never a candidate revision
 * directly. A publication binds an optimistic predecessor, operator-owned policy, authenticated
 * reviewer, exact governance ticket, and the source use horizon. New publications append; they do
 * not mutate a serving row in place.</p>
 *
 * @param schemaVersion publication wire version
 * @param publicationFingerprint canonical publication fingerprint
 * @param sourceCommandFingerprint canonical publish-command fingerprint
 * @param scope complete enterprise scope
 * @param corpusId stable corpus identity
 * @param revision positive publication generation
 * @param predecessorRef exact previous publication, absent for generation one
 * @param corpusRevisionRef exact immutable corpus revision
 * @param publicationPolicyRef exact operator-owned publication policy
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality publication reason
 * @param reviewedBy authenticated reviewer identity
 * @param publishedAt trusted local publication time
 * @param usableUntil exclusive serving horizon inherited from the corpus revision
 */
public record CapabilityCorpusPublication(
        String schemaVersion,
        String publicationFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String corpusId,
        long revision,
        MirrorArtifactRef predecessorRef,
        MirrorArtifactRef corpusRevisionRef,
        MirrorArtifactRef publicationPolicyRef,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode,
        String reviewedBy,
        Instant publishedAt,
        Instant usableUntil
) {
    /** Current corpus publication version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusPublication.v1";
    /** Artifact kind used by publication lineage. */
    public static final String ARTIFACT_KIND = "CAPABILITY_CORPUS_PUBLICATION";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates publication lineage, policy, and serving horizon. */
    public CapabilityCorpusPublication {
        schemaVersion = version(schemaVersion);
        publicationFingerprint = fingerprint(
                publicationFingerprint, "publicationFingerprint");
        sourceCommandFingerprint = fingerprint(
                sourceCommandFingerprint, "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        corpusId = identifier(corpusId, "corpusId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (predecessorRef != null) {
            predecessorRef = ref(
                    predecessorRef, ARTIFACT_KIND, "predecessorRef");
        }
        if (revision == 1 && predecessorRef != null
                || revision > 1 && (predecessorRef == null
                || !predecessorRef.id().equals(corpusId)
                || predecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "predecessorRef does not describe the previous publication");
        }
        corpusRevisionRef = ref(
                corpusRevisionRef,
                CapabilityCorpusRevision.ARTIFACT_KIND,
                "corpusRevisionRef");
        if (!corpusRevisionRef.id().equals(corpusId)) {
            throw new IllegalArgumentException(
                    "corpusRevisionRef must belong to corpusId");
        }
        publicationPolicyRef = ref(
                publicationPolicyRef,
                "CORPUS_PUBLICATION_POLICY",
                "publicationPolicyRef");
        reviewTicketRef = ref(
                reviewTicketRef, "GOVERNANCE_REVIEW_TICKET", "reviewTicketRef");
        reasonCode = reason(reasonCode);
        reviewedBy = identifier(reviewedBy, "reviewedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
        if (!usableUntil.isAfter(publishedAt)) {
            throw new IllegalArgumentException(
                    "publication is already outside its serving horizon");
        }
    }

    /**
     * Returns the exact publication reference.
     *
     * @return immutable serving publication reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, corpusId, revision, publicationFingerprint);
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
                    "unsupported capability corpus publication schemaVersion");
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

    private static String reason(String value) {
        String exact = value == null ? "" : value.trim();
        if (!REASON.matcher(exact).matches()) {
            throw new IllegalArgumentException("reasonCode is invalid");
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
}
