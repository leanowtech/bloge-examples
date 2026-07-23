package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owner-reviewed command that publishes one explicit recorded retry trajectory.
 *
 * <p>The command never asks Resource Gateway to infer retry grouping from trace order. It binds
 * one current corpus publication, one retry policy, and a consecutive sequence of exact
 * observation/admission sources. The governance service independently proves that all sources
 * belong to the publication, carry the same canonical request, and form a valid retry sequence
 * before creating a serving artifact.</p>
 *
 * @param schemaVersion command wire version
 * @param trajectoryId stable trajectory identity
 * @param revision positive append-only trajectory revision
 * @param expectedPredecessorRef exact previous trajectory publication, absent for revision one
 * @param capabilityRef exact capability shared by every attempt
 * @param corpusPublicationRef exact current corpus serving publication
 * @param retryPolicyRef exact owner-approved retry policy
 * @param attempts consecutive exact observation/admission attempt sources
 * @param reviewTicketRef exact owner-review ticket
 * @param reasonCode stable low-cardinality approval reason
 */
public record CapabilityCorpusTrajectoryPublishRequest(
        String schemaVersion,
        String trajectoryId,
        long revision,
        MirrorArtifactRef expectedPredecessorRef,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef corpusPublicationRef,
        MirrorArtifactRef retryPolicyRef,
        List<AttemptSource> attempts,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode
) {
    /** Current trajectory-publication command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusTrajectoryPublishRequest.v1";
    /** Minimum attempts required to distinguish a trajectory from an exact sample. */
    public static final int MINIMUM_ATTEMPTS = 2;
    /** Hard bound preventing an unbounded retry program from entering a fixture generation. */
    public static final int MAXIMUM_ATTEMPTS = 32;

    private static final Pattern REASON =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates exact lineage, artifact kinds, and consecutive attempt numbering. */
    public CapabilityCorpusTrajectoryPublishRequest {
        schemaVersion = version(schemaVersion);
        trajectoryId = identifier(trajectoryId, "trajectoryId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (expectedPredecessorRef != null) {
            expectedPredecessorRef = ref(
                    expectedPredecessorRef,
                    CapabilityCorpusTrajectoryPublication.ARTIFACT_KIND,
                    "expectedPredecessorRef");
        }
        if (revision == 1 && expectedPredecessorRef != null
                || revision > 1 && (expectedPredecessorRef == null
                || !expectedPredecessorRef.id().equals(trajectoryId)
                || expectedPredecessorRef.revision() != revision - 1)) {
            throw new IllegalArgumentException(
                    "expectedPredecessorRef does not fence the previous trajectory");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        corpusPublicationRef = ref(
                corpusPublicationRef,
                CapabilityCorpusPublication.ARTIFACT_KIND,
                "corpusPublicationRef");
        retryPolicyRef = ref(retryPolicyRef, "RETRY_POLICY", "retryPolicyRef");
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        if (attempts.size() < MINIMUM_ATTEMPTS
                || attempts.size() > MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "attempts must contain between 2 and 32 sources");
        }
        Set<MirrorArtifactRef> observations = new HashSet<>();
        for (int index = 0; index < attempts.size(); index++) {
            AttemptSource attempt = Objects.requireNonNull(
                    attempts.get(index), "attempt");
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
    }

    /**
     * Exact admitted source used for one trajectory attempt.
     *
     * @param attempt one-based attempt number
     * @param observationRef exact signed observation
     * @param admissionRef exact admitted decision for the observation
     */
    public record AttemptSource(
            int attempt,
            MirrorArtifactRef observationRef,
            MirrorArtifactRef admissionRef
    ) {
        /** Validates one exact one-based source coordinate. */
        public AttemptSource {
            if (attempt < 1 || attempt > MAXIMUM_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "attempt is outside the trajectory bound");
            }
            observationRef = ref(
                    observationRef,
                    CapabilityObservationEnvelope.ARTIFACT_KIND,
                    "observationRef");
            admissionRef = ref(
                    admissionRef,
                    CapabilityObservationAdmission.ARTIFACT_KIND,
                    "admissionRef");
            if (!admissionRef.id().equals(
                    observationRef.id() + ":admission")) {
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
                    "unsupported corpus trajectory publish request schemaVersion");
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
