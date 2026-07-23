package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Owner-reviewed command that publishes one externally validated recorded cluster.
 *
 * <p>The command carries references only. The governance service resolves the validation through
 * the trusted data-plane authority, proves that every member belongs to the exact current corpus
 * publication, and freezes the validated match and identity-projection rules in a payload-free
 * serving artifact.</p>
 *
 * @param schemaVersion command wire version
 * @param clusterId stable cluster identity
 * @param revision positive append-only cluster revision
 * @param expectedPredecessorRef exact previous cluster publication, absent for revision one
 * @param capabilityRef exact capability
 * @param corpusPublicationRef exact current corpus publication
 * @param clusterPolicyRef exact current owner policy
 * @param validationRef exact data-plane cluster validation
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality approval reason
 */
public record CapabilityCorpusClusterPublishRequest(
        String schemaVersion,
        String clusterId,
        long revision,
        MirrorArtifactRef expectedPredecessorRef,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef corpusPublicationRef,
        MirrorArtifactRef clusterPolicyRef,
        MirrorArtifactRef validationRef,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode
) {
    /** Current cluster-publication command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusClusterPublishRequest.v1";
    private static final Pattern REASON =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates exact lineage and artifact kinds. */
    public CapabilityCorpusClusterPublishRequest {
        schemaVersion = version(schemaVersion);
        clusterId = identifier(clusterId, "clusterId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (expectedPredecessorRef != null) {
            expectedPredecessorRef = ref(
                    expectedPredecessorRef,
                    CapabilityCorpusClusterPublication.ARTIFACT_KIND,
                    "expectedPredecessorRef");
        }
        if (revision == 1 && expectedPredecessorRef != null
                || revision > 1 && (expectedPredecessorRef == null
                || !expectedPredecessorRef.id().equals(clusterId)
                || expectedPredecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "expectedPredecessorRef does not fence the previous cluster");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        corpusPublicationRef = ref(
                corpusPublicationRef,
                CapabilityCorpusPublication.ARTIFACT_KIND,
                "corpusPublicationRef");
        clusterPolicyRef = ref(
                clusterPolicyRef, "CORPUS_CLUSTER_POLICY", "clusterPolicyRef");
        validationRef = ref(
                validationRef,
                CapabilityCorpusClusterValidation.ARTIFACT_KIND,
                "validationRef");
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
                    "unsupported corpus cluster publish request schemaVersion");
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
