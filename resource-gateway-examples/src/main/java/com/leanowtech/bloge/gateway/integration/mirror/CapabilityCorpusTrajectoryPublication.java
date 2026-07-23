package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable owner-reviewed serving publication for one recorded retry trajectory.
 *
 * <p>The artifact binds a current corpus publication and revision, an exact retry policy, the
 * canonical request fingerprint, and an ordered set of admitted observation sources. It remains
 * payload-free. Runtime serving must revalidate the current corpus head, policy, source lifecycle,
 * grants, retention, and response content addresses before exposing any attempt outcome.</p>
 *
 * @param schemaVersion trajectory publication wire version
 * @param trajectoryFingerprint canonical artifact fingerprint
 * @param sourceCommandFingerprint canonical publish-command fingerprint
 * @param scope complete enterprise scope
 * @param trajectoryId stable trajectory identity
 * @param revision positive append-only trajectory revision
 * @param predecessorRef exact previous trajectory publication, absent for revision one
 * @param capabilityRef exact capability shared by all attempts
 * @param corpusPublicationRef exact corpus serving publication reviewed with this trajectory
 * @param corpusRevisionRef exact immutable corpus revision behind the publication
 * @param publicationPolicyRef exact operator-owned publication policy
 * @param retryPolicyRef exact owner-approved retry policy
 * @param requestFingerprint canonical request content address shared by every attempt
 * @param attempts consecutive exact observation/admission sources
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality approval reason
 * @param reviewedBy authenticated reviewer identity
 * @param publishedAt trusted local publication time
 * @param usableUntil earliest exclusive source-use horizon
 */
public record CapabilityCorpusTrajectoryPublication(
        String schemaVersion,
        String trajectoryFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String trajectoryId,
        long revision,
        MirrorArtifactRef predecessorRef,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef corpusPublicationRef,
        MirrorArtifactRef corpusRevisionRef,
        MirrorArtifactRef publicationPolicyRef,
        MirrorArtifactRef retryPolicyRef,
        String requestFingerprint,
        List<CapabilityCorpusTrajectoryPublishRequest.AttemptSource> attempts,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode,
        String reviewedBy,
        Instant publishedAt,
        Instant usableUntil
) {
    /** Current trajectory publication version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusTrajectoryPublication.v1";
    /** Artifact kind used by exact trajectory publication references. */
    public static final String ARTIFACT_KIND =
            "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION";

    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates immutable lineage, exact references, and serving horizon. */
    public CapabilityCorpusTrajectoryPublication {
        schemaVersion = version(schemaVersion);
        trajectoryFingerprint = fingerprint(
                trajectoryFingerprint, "trajectoryFingerprint");
        sourceCommandFingerprint = fingerprint(
                sourceCommandFingerprint, "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        trajectoryId = identifier(trajectoryId, "trajectoryId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (predecessorRef != null) {
            predecessorRef = ref(
                    predecessorRef, ARTIFACT_KIND, "predecessorRef");
        }
        if (revision == 1 && predecessorRef != null
                || revision > 1 && (predecessorRef == null
                || !predecessorRef.id().equals(trajectoryId)
                || predecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "predecessorRef does not describe the previous trajectory");
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
        retryPolicyRef = ref(retryPolicyRef, "RETRY_POLICY", "retryPolicyRef");
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (attempts.size()
                < CapabilityCorpusTrajectoryPublishRequest.MINIMUM_ATTEMPTS
                || attempts.size()
                > CapabilityCorpusTrajectoryPublishRequest.MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "attempts must contain between 2 and 32 sources");
        }
        Set<MirrorArtifactRef> observations = new HashSet<>();
        for (int index = 0; index < attempts.size(); index++) {
            CapabilityCorpusTrajectoryPublishRequest.AttemptSource attempt =
                    Objects.requireNonNull(attempts.get(index), "attempt");
            if (attempt.attempt() != index + 1) {
                throw new IllegalArgumentException(
                        "attempts must be numbered consecutively from one");
            }
            if (!observations.add(attempt.observationRef())) {
                throw new IllegalArgumentException(
                        "attempts must use distinct observations");
            }
        }
        reviewTicketRef = ref(
                reviewTicketRef, "GOVERNANCE_REVIEW_TICKET", "reviewTicketRef");
        reasonCode = reason(reasonCode);
        reviewedBy = identifier(reviewedBy, "reviewedBy");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
        if (!usableUntil.isAfter(publishedAt)) {
            throw new IllegalArgumentException(
                    "trajectory is outside its serving horizon");
        }
    }

    /**
     * Returns the immutable trajectory publication reference.
     *
     * @return exact content-addressed trajectory reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, trajectoryId, revision, trajectoryFingerprint);
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
                    "unsupported corpus trajectory publication schemaVersion");
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
