package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;

/**
 * Versioned payload-free state evidence nested in a signed mirror run.
 *
 * <p>Jackson uses the already present {@code schemaVersion} property to select the immutable
 * protocol record. Consumers must still run the corresponding canonical integrity verifier;
 * successful polymorphic decoding proves only type selection, not trust.</p>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "schemaVersion",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(
                value = MirrorStateRunEvidence.class,
                name = MirrorStateRunEvidence.SCHEMA_VERSION),
        @JsonSubTypes.Type(
                value = MirrorStateTransitionRunEvidence.class,
                name = MirrorStateTransitionRunEvidence.SCHEMA_VERSION)
})
public sealed interface MirrorStateEvidence
        permits MirrorStateRunEvidence,
        MirrorStateTransitionRunEvidence {
    /** @return exact state-evidence protocol version */
    String schemaVersion();

    /** @return canonical fingerprint of the complete state-evidence value */
    String stateEvidenceFingerprint();

    /** @return exact terminal mirror run identity */
    String runId();

    /** @return exact sealed mirror-plan fingerprint */
    String planFingerprint();

    /** @return initial Session state head admitted by the run */
    MirrorArtifactRef sessionStateRef();

    /** @return exact state model used by the run */
    MirrorArtifactRef stateModelRef();

    /** @return initial committed Session revision */
    long stateRevision();

    /** @return initial business-world fingerprint */
    String worldFingerprint();

    /** @return initial deterministic Session logical time */
    Instant logicalClock();

    /**
     * Returns the same protocol value with a replacement canonical fingerprint.
     *
     * @param value blank material fingerprint or canonical sealed fingerprint
     * @return immutable evidence of the same concrete protocol version
     */
    MirrorStateEvidence withFingerprint(String value);
}
