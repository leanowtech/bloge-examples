package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable terminal governance review for one quarantined observation.
 *
 * <p>The artifact is evidence about the quarantine decision, not a replacement admission. Its
 * full enterprise scope, source command fingerprint, reviewer, and governance ticket make exact
 * retries auditable while preventing a review from becoming an ungoverned payload container.</p>
 *
 * @param schemaVersion review artifact version
 * @param reviewFingerprint canonical review fingerprint
 * @param sourceCommandFingerprint canonical request fingerprint used for idempotency
 * @param scope complete enterprise scope
 * @param observationRef exact quarantined observation
 * @param admissionRef exact quarantine admission
 * @param disposition terminal review disposition
 * @param reviewTicketRef exact external governance ticket
 * @param reasonCode stable low-cardinality reason
 * @param reviewedBy authenticated reviewer identity
 * @param reviewedAt trusted local decision time
 */
public record CapabilityObservationReview(
        String schemaVersion,
        String reviewFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef observationRef,
        MirrorArtifactRef admissionRef,
        CapabilityObservationReviewRequest.Disposition disposition,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode,
        String reviewedBy,
        Instant reviewedAt
) {
    /** Current review artifact version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityObservationReview.v1";
    /** Artifact kind used by review references. */
    public static final String ARTIFACT_KIND = "CAPABILITY_OBSERVATION_REVIEW";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates immutable review identity and bounded metadata. */
    public CapabilityObservationReview {
        schemaVersion = version(schemaVersion);
        reviewFingerprint = fingerprint(reviewFingerprint, "reviewFingerprint");
        sourceCommandFingerprint = fingerprint(
                sourceCommandFingerprint, "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        observationRef = ref(
                observationRef, CapabilityObservationEnvelope.ARTIFACT_KIND,
                "observationRef");
        admissionRef = ref(
                admissionRef, CapabilityObservationAdmission.ARTIFACT_KIND,
                "admissionRef");
        if (!admissionRef.id().equals(observationRef.id() + ":admission")) {
            throw new IllegalArgumentException(
                    "admissionRef must belong to observationRef");
        }
        disposition = Objects.requireNonNull(disposition, "disposition");
        reviewTicketRef = ref(
                reviewTicketRef, "GOVERNANCE_REVIEW_TICKET", "reviewTicketRef");
        reasonCode = reason(reasonCode);
        reviewedBy = identifier(reviewedBy, "reviewedBy");
        reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
    }

    /**
     * Returns the exact review artifact.
     *
     * @return immutable review reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                observationRef.id() + ":review",
                1,
                reviewFingerprint);
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
                    "unsupported capability observation review schemaVersion");
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
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,254}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
