package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free command that records the terminal review of one quarantined observation.
 *
 * <p>A review never changes the original admission. Even a false-positive disposition requires a
 * corrected observation with a new id to pass the normal admission pipeline. The command carries
 * no comment field so business payload or investigation detail cannot leak into the control
 * plane; detailed evidence belongs behind the exact governance ticket reference.</p>
 *
 * @param schemaVersion command wire version
 * @param observationRef exact signed observation under review
 * @param admissionRef exact terminal quarantine decision
 * @param disposition closed review disposition
 * @param reviewTicketRef exact external governance ticket
 * @param reasonCode stable low-cardinality review reason
 */
public record CapabilityObservationReviewRequest(
        String schemaVersion,
        MirrorArtifactRef observationRef,
        MirrorArtifactRef admissionRef,
        Disposition disposition,
        MirrorArtifactRef reviewTicketRef,
        String reasonCode
) {
    /** Current quarantine-review command version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityObservationReviewRequest.v1";
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates exact references and the closed payload-free reason. */
    public CapabilityObservationReviewRequest {
        schemaVersion = version(schemaVersion);
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
    }

    /** Closed terminal review disposition. */
    public enum Disposition {
        /** The original quarantine was correct and no producer retry is expected. */
        CONFIRMED_QUARANTINE,
        /** The producer must correct its envelope, key, or sanitization material. */
        PRODUCER_REMEDIATION_REQUIRED,
        /** An operator-owned policy or grant must be corrected before a new observation. */
        POLICY_REMEDIATION_REQUIRED,
        /** The sample requires a separate security investigation. */
        SECURITY_INVESTIGATION_REQUIRED,
        /** The rejection was a false positive, but a new observation must still be ingested. */
        FALSE_POSITIVE_REINGEST_REQUIRED
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
                    "unsupported capability observation review request schemaVersion");
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
