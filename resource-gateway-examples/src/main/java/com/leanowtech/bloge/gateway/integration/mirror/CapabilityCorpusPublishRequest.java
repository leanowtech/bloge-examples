package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owner-reviewed command that publishes one exact corpus revision as the serving head.
 *
 * <p>The publication revision and expected predecessor form an optimistic append fence. The
 * caller cannot submit policy coordinates or reviewer identity; both are resolved from
 * operator-owned configuration and the authenticated workload.</p>
 *
 * @param schemaVersion command wire version
 * @param corpusId stable corpus identity
 * @param publicationRevision positive publication generation
 * @param expectedPublicationRef exact previous publication, absent only for generation one
 * @param corpusRevisionRef exact candidate revision to serve
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality publication reason
 */
public record CapabilityCorpusPublishRequest(
        String schemaVersion,
        String corpusId,
        long publicationRevision,
        MirrorArtifactRef expectedPublicationRef,
        MirrorArtifactRef corpusRevisionRef,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode
) {
    /** Current corpus-publication command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusPublishRequest.v1";
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates publication fencing and exact governance references. */
    public CapabilityCorpusPublishRequest {
        schemaVersion = version(schemaVersion);
        corpusId = identifier(corpusId, "corpusId");
        if (publicationRevision < 1) {
            throw new IllegalArgumentException(
                    "publicationRevision must be positive");
        }
        if (expectedPublicationRef != null) {
            expectedPublicationRef = ref(
                    expectedPublicationRef,
                    CapabilityCorpusPublication.ARTIFACT_KIND,
                    "expectedPublicationRef");
        }
        if (publicationRevision == 1 && expectedPublicationRef != null
                || publicationRevision > 1 && (expectedPublicationRef == null
                || !expectedPublicationRef.id().equals(corpusId)
                || expectedPublicationRef.revision() != publicationRevision - 1)) {
            throw new IllegalArgumentException(
                    "expectedPublicationRef does not fence the previous publication");
        }
        corpusRevisionRef = ref(
                corpusRevisionRef,
                CapabilityCorpusRevision.ARTIFACT_KIND,
                "corpusRevisionRef");
        if (!corpusRevisionRef.id().equals(corpusId)) {
            throw new IllegalArgumentException(
                    "corpusRevisionRef must belong to corpusId");
        }
        reviewTicketRef = ref(
                reviewTicketRef, "GOVERNANCE_REVIEW_TICKET", "reviewTicketRef");
        reasonCode = reason(reasonCode);
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
                    "unsupported capability corpus publish request schemaVersion");
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

    private static String reason(String value) {
        String exact = value == null ? "" : value.trim();
        if (!REASON.matcher(exact).matches()) {
            throw new IllegalArgumentException("reasonCode is invalid");
        }
        return exact;
    }
}
