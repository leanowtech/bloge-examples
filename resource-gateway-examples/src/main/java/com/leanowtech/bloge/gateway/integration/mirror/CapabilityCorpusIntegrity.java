package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Objects;

/**
 * Canonical content-addressing boundary for corpus governance commands and artifacts.
 *
 * <p>Command fingerprints provide deterministic idempotency without trusting a caller-submitted
 * hash. Artifact fingerprints bind complete scope, lineage, source metadata, policy, reviewer,
 * risk, and use-horizon coordinates. The boundary performs no authority decision; repositories and
 * the independent test-kit recompute the same canonical values.</p>
 */
public final class CapabilityCorpusIntegrity {
    /** Maximum canonical command or artifact size. */
    public static final int MAXIMUM_CANONICAL_BYTES = 4 * 1024 * 1024;
    private static final String ZERO_FINGERPRINT = "sha256:" + "0".repeat(64);

    private final ObjectMapper mapper;

    /**
     * Creates the corpus content-addressing boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public CapabilityCorpusIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Computes the canonical quarantine-review command identity.
     *
     * @param request exact review command
     * @return canonical SHA-256 fingerprint
     */
    public String reviewCommandFingerprint(
            CapabilityObservationReviewRequest request) {
        return fingerprint(Objects.requireNonNull(request, "request"));
    }

    /**
     * Seals one terminal quarantine review.
     *
     * @param candidate review carrying a placeholder fingerprint
     * @return content-addressed review
     */
    public CapabilityObservationReview sealReview(
            CapabilityObservationReview candidate) {
        CapabilityObservationReview exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityObservationReview blank = new CapabilityObservationReview(
                exact.schemaVersion(),
                ZERO_FINGERPRINT,
                exact.sourceCommandFingerprint(),
                exact.scope(),
                exact.observationRef(),
                exact.admissionRef(),
                exact.disposition(),
                exact.reviewTicketRef(),
                exact.reasonCode(),
                exact.reviewedBy(),
                exact.reviewedAt());
        return new CapabilityObservationReview(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.sourceCommandFingerprint(),
                blank.scope(),
                blank.observationRef(),
                blank.admissionRef(),
                blank.disposition(),
                blank.reviewTicketRef(),
                blank.reasonCode(),
                blank.reviewedBy(),
                blank.reviewedAt());
    }

    /**
     * Verifies a persisted quarantine review fingerprint.
     *
     * @param review untrusted review
     * @return true only for exact canonical content
     */
    public boolean reviewVerified(CapabilityObservationReview review) {
        try {
            return review != null
                    && review.reviewFingerprint().equals(
                    sealReview(review).reviewFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Computes the canonical candidate-command identity.
     *
     * @param request exact candidate command
     * @return canonical SHA-256 fingerprint
     */
    public String candidateCommandFingerprint(
            CapabilityCorpusCandidateRequest request) {
        return fingerprint(Objects.requireNonNull(request, "request"));
    }

    /**
     * Seals one immutable corpus revision.
     *
     * @param candidate revision carrying a placeholder fingerprint
     * @return content-addressed revision
     */
    public CapabilityCorpusRevision sealRevision(
            CapabilityCorpusRevision candidate) {
        CapabilityCorpusRevision exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityCorpusRevision blank = new CapabilityCorpusRevision(
                exact.schemaVersion(),
                ZERO_FINGERPRINT,
                exact.sourceCommandFingerprint(),
                exact.scope(),
                exact.corpusId(),
                exact.revision(),
                exact.predecessorRef(),
                exact.capabilityRef(),
                exact.governancePolicyRef(),
                exact.sources(),
                exact.riskSummary(),
                exact.createdBy(),
                exact.createdAt(),
                exact.usableUntil());
        return new CapabilityCorpusRevision(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.sourceCommandFingerprint(),
                blank.scope(),
                blank.corpusId(),
                blank.revision(),
                blank.predecessorRef(),
                blank.capabilityRef(),
                blank.governancePolicyRef(),
                blank.sources(),
                blank.riskSummary(),
                blank.createdBy(),
                blank.createdAt(),
                blank.usableUntil());
    }

    /**
     * Verifies a persisted corpus revision fingerprint.
     *
     * @param revision untrusted revision
     * @return true only for exact canonical content
     */
    public boolean revisionVerified(CapabilityCorpusRevision revision) {
        try {
            return revision != null
                    && revision.revisionFingerprint().equals(
                    sealRevision(revision).revisionFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Computes the canonical publication-command identity.
     *
     * @param request exact publication command
     * @return canonical SHA-256 fingerprint
     */
    public String publishCommandFingerprint(
            CapabilityCorpusPublishRequest request) {
        return fingerprint(Objects.requireNonNull(request, "request"));
    }

    /**
     * Computes a one-way fingerprint for payload-free trace coordinates.
     *
     * @param trace exact source trace coordinates
     * @return canonical trace fingerprint
     */
    public String traceFingerprint(
            CapabilityObservationEnvelope.TraceCoordinates trace) {
        return fingerprint(Objects.requireNonNull(trace, "trace"));
    }

    /**
     * Seals one immutable corpus publication.
     *
     * @param candidate publication carrying a placeholder fingerprint
     * @return content-addressed publication
     */
    public CapabilityCorpusPublication sealPublication(
            CapabilityCorpusPublication candidate) {
        CapabilityCorpusPublication exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityCorpusPublication blank = new CapabilityCorpusPublication(
                exact.schemaVersion(),
                ZERO_FINGERPRINT,
                exact.sourceCommandFingerprint(),
                exact.scope(),
                exact.corpusId(),
                exact.revision(),
                exact.predecessorRef(),
                exact.corpusRevisionRef(),
                exact.publicationPolicyRef(),
                exact.reviewTicketRef(),
                exact.reasonCode(),
                exact.reviewedBy(),
                exact.publishedAt(),
                exact.usableUntil());
        return new CapabilityCorpusPublication(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.sourceCommandFingerprint(),
                blank.scope(),
                blank.corpusId(),
                blank.revision(),
                blank.predecessorRef(),
                blank.corpusRevisionRef(),
                blank.publicationPolicyRef(),
                blank.reviewTicketRef(),
                blank.reasonCode(),
                blank.reviewedBy(),
                blank.publishedAt(),
                blank.usableUntil());
    }

    /**
     * Verifies a persisted corpus publication fingerprint.
     *
     * @param publication untrusted publication
     * @return true only for exact canonical content
     */
    public boolean publicationVerified(
            CapabilityCorpusPublication publication) {
        try {
            return publication != null
                    && publication.publicationFingerprint().equals(
                    sealPublication(publication).publicationFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Computes the canonical trajectory-publication command identity.
     *
     * @param request exact owner-reviewed trajectory command
     * @return canonical SHA-256 fingerprint
     */
    public String trajectoryCommandFingerprint(
            CapabilityCorpusTrajectoryPublishRequest request) {
        return fingerprint(Objects.requireNonNull(request, "request"));
    }

    /**
     * Seals one immutable trajectory publication.
     *
     * @param candidate publication carrying a placeholder fingerprint
     * @return content-addressed trajectory publication
     */
    public CapabilityCorpusTrajectoryPublication sealTrajectory(
            CapabilityCorpusTrajectoryPublication candidate) {
        CapabilityCorpusTrajectoryPublication exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityCorpusTrajectoryPublication blank =
                new CapabilityCorpusTrajectoryPublication(
                        exact.schemaVersion(),
                        ZERO_FINGERPRINT,
                        exact.sourceCommandFingerprint(),
                        exact.scope(),
                        exact.trajectoryId(),
                        exact.revision(),
                        exact.predecessorRef(),
                        exact.capabilityRef(),
                        exact.corpusPublicationRef(),
                        exact.corpusRevisionRef(),
                        exact.publicationPolicyRef(),
                        exact.retryPolicyRef(),
                        exact.requestFingerprint(),
                        exact.attempts(),
                        exact.reviewTicketRef(),
                        exact.reasonCode(),
                        exact.reviewedBy(),
                        exact.publishedAt(),
                        exact.usableUntil());
        return new CapabilityCorpusTrajectoryPublication(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.sourceCommandFingerprint(),
                blank.scope(),
                blank.trajectoryId(),
                blank.revision(),
                blank.predecessorRef(),
                blank.capabilityRef(),
                blank.corpusPublicationRef(),
                blank.corpusRevisionRef(),
                blank.publicationPolicyRef(),
                blank.retryPolicyRef(),
                blank.requestFingerprint(),
                blank.attempts(),
                blank.reviewTicketRef(),
                blank.reasonCode(),
                blank.reviewedBy(),
                blank.publishedAt(),
                blank.usableUntil());
    }

    /**
     * Verifies a persisted trajectory publication fingerprint.
     *
     * @param publication untrusted trajectory publication
     * @return true only for exact canonical content
     */
    public boolean trajectoryVerified(
            CapabilityCorpusTrajectoryPublication publication) {
        try {
            return publication != null
                    && publication.trajectoryFingerprint().equals(
                    sealTrajectory(publication).trajectoryFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Seals one externally produced payload-free cluster validation.
     *
     * @param candidate validation carrying a placeholder fingerprint
     * @return content-addressed cluster validation
     */
    public CapabilityCorpusClusterValidation sealClusterValidation(
            CapabilityCorpusClusterValidation candidate) {
        CapabilityCorpusClusterValidation exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityCorpusClusterValidation blank =
                new CapabilityCorpusClusterValidation(
                        exact.schemaVersion(),
                        ZERO_FINGERPRINT,
                        exact.scope(),
                        exact.validationId(),
                        exact.revision(),
                        exact.capabilityRef(),
                        exact.corpusPublicationRef(),
                        exact.corpusRevisionRef(),
                        exact.representativeSource(),
                        exact.members(),
                        exact.matchRequestPointers(),
                        exact.identityMode(),
                        exact.identityProjections(),
                        exact.distinctIdentityCount(),
                        exact.holdout(),
                        exact.confidence(),
                        exact.identityCoverageComplete(),
                        exact.validatedBy(),
                        exact.validatedAt(),
                        exact.expiresAt());
        return new CapabilityCorpusClusterValidation(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.scope(),
                blank.validationId(),
                blank.revision(),
                blank.capabilityRef(),
                blank.corpusPublicationRef(),
                blank.corpusRevisionRef(),
                blank.representativeSource(),
                blank.members(),
                blank.matchRequestPointers(),
                blank.identityMode(),
                blank.identityProjections(),
                blank.distinctIdentityCount(),
                blank.holdout(),
                blank.confidence(),
                blank.identityCoverageComplete(),
                blank.validatedBy(),
                blank.validatedAt(),
                blank.expiresAt());
    }

    /**
     * Verifies an externally resolved cluster-validation content address.
     *
     * @param validation untrusted cluster validation
     * @return true only for exact canonical content
     */
    public boolean clusterValidationVerified(
            CapabilityCorpusClusterValidation validation) {
        try {
            return validation != null
                    && validation.validationFingerprint().equals(
                    sealClusterValidation(validation).validationFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Computes the canonical cluster-publication command identity.
     *
     * @param request exact owner-reviewed cluster command
     * @return canonical SHA-256 fingerprint
     */
    public String clusterCommandFingerprint(
            CapabilityCorpusClusterPublishRequest request) {
        return fingerprint(Objects.requireNonNull(request, "request"));
    }

    /**
     * Seals one immutable cluster publication.
     *
     * @param candidate publication carrying a placeholder fingerprint
     * @return content-addressed cluster publication
     */
    public CapabilityCorpusClusterPublication sealCluster(
            CapabilityCorpusClusterPublication candidate) {
        CapabilityCorpusClusterPublication exact =
                Objects.requireNonNull(candidate, "candidate");
        CapabilityCorpusClusterPublication blank =
                new CapabilityCorpusClusterPublication(
                        exact.schemaVersion(),
                        ZERO_FINGERPRINT,
                        exact.sourceCommandFingerprint(),
                        exact.scope(),
                        exact.clusterId(),
                        exact.revision(),
                        exact.predecessorRef(),
                        exact.capabilityRef(),
                        exact.corpusPublicationRef(),
                        exact.corpusRevisionRef(),
                        exact.publicationPolicyRef(),
                        exact.clusterPolicyRef(),
                        exact.validationRef(),
                        exact.representativeSource(),
                        exact.members(),
                        exact.matchRequestPointers(),
                        exact.identityMode(),
                        exact.identityProjections(),
                        exact.distinctIdentityCount(),
                        exact.holdout(),
                        exact.confidence(),
                        exact.reviewTicketRef(),
                        exact.reasonCode(),
                        exact.reviewedBy(),
                        exact.publishedAt(),
                        exact.usableUntil());
        return new CapabilityCorpusClusterPublication(
                blank.schemaVersion(),
                fingerprint(blank),
                blank.sourceCommandFingerprint(),
                blank.scope(),
                blank.clusterId(),
                blank.revision(),
                blank.predecessorRef(),
                blank.capabilityRef(),
                blank.corpusPublicationRef(),
                blank.corpusRevisionRef(),
                blank.publicationPolicyRef(),
                blank.clusterPolicyRef(),
                blank.validationRef(),
                blank.representativeSource(),
                blank.members(),
                blank.matchRequestPointers(),
                blank.identityMode(),
                blank.identityProjections(),
                blank.distinctIdentityCount(),
                blank.holdout(),
                blank.confidence(),
                blank.reviewTicketRef(),
                blank.reasonCode(),
                blank.reviewedBy(),
                blank.publishedAt(),
                blank.usableUntil());
    }

    /**
     * Verifies a persisted cluster publication fingerprint.
     *
     * @param publication untrusted cluster publication
     * @return true only for exact canonical content
     */
    public boolean clusterVerified(
            CapabilityCorpusClusterPublication publication) {
        try {
            return publication != null
                    && publication.clusterFingerprint().equals(
                    sealCluster(publication).clusterFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper, value, MAXIMUM_CANONICAL_BYTES);
    }
}
