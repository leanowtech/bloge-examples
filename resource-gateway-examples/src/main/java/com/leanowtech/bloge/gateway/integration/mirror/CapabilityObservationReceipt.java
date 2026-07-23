package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Public atomic result of one capability-observation ingest request.
 *
 * <p>The receipt always carries the exact producer envelope and the terminal local decision that
 * was committed with it. An idempotent retry returns the original receipt rather than silently
 * re-evaluating mutable policy.</p>
 *
 * @param schemaVersion receipt wire version
 * @param envelope exact signed observation
 * @param admission terminal admitted or quarantined decision
 */
public record CapabilityObservationReceipt(
        String schemaVersion,
        CapabilityObservationEnvelope envelope,
        CapabilityObservationAdmission admission
) {
    /** Current receipt protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityObservationReceipt.v1";
    /** Integration envelope type. */
    public static final String ARTIFACT_KIND = "CAPABILITY_OBSERVATION_RECEIPT";

    /** Validates atomic identity linkage. */
    public CapabilityObservationReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported capability observation receipt schemaVersion");
        }
        envelope = Objects.requireNonNull(envelope, "envelope");
        admission = Objects.requireNonNull(admission, "admission");
        new CapabilityObservationRepository.StoredObservation(envelope, admission);
    }

    /**
     * Converts one verified repository result to its public atomic receipt.
     *
     * @param stored immutable stored observation
     * @return public receipt
     */
    public static CapabilityObservationReceipt from(
            CapabilityObservationRepository.StoredObservation stored) {
        CapabilityObservationRepository.StoredObservation exact =
                Objects.requireNonNull(stored, "stored");
        return new CapabilityObservationReceipt(
                "", exact.envelope(), exact.admission());
    }
}
