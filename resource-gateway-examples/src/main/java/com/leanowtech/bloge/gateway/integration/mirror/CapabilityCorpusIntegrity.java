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

    private String fingerprint(Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                mapper, value, MAXIMUM_CANONICAL_BYTES);
    }
}
