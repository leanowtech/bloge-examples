package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable local decision for one signed capability observation.
 *
 * <p>An admitted decision means every protocol, authority, purpose, capability, retention, and
 * external sanitization-reference check passed. Quarantine is a durable terminal admission
 * outcome, not a partially usable corpus record. Missing policy and key references are allowed
 * only for quarantine so an unconfigured producer can be retained for investigation without
 * inventing trust coordinates.</p>
 *
 * @param schemaVersion admission wire version
 * @param admissionFingerprint canonical decision fingerprint
 * @param observationRef exact signed observation
 * @param scope complete enterprise scope
 * @param capabilityRef exact observed capability
 * @param policyRef exact local admission policy, or {@code null} before policy resolution
 * @param dataUseGrantRef exact submitted purpose grant
 * @param authorityKeyRef exact verified producer key, or {@code null} before key resolution
 * @param state terminal admission state
 * @param reason closed admission reason
 * @param decidedAt trusted local decision time
 * @param usableUntil exclusive corpus-use bound, equal to decision time when quarantined
 */
public record CapabilityObservationAdmission(
        String schemaVersion,
        String admissionFingerprint,
        MirrorArtifactRef observationRef,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef policyRef,
        MirrorArtifactRef dataUseGrantRef,
        MirrorArtifactRef authorityKeyRef,
        State state,
        Reason reason,
        Instant decidedAt,
        Instant usableUntil
) {
    /** Current admission protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityObservationAdmission.v1";
    /** Artifact kind used by immutable admission decisions. */
    public static final String ARTIFACT_KIND = "CAPABILITY_OBSERVATION_ADMISSION";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates exact references and terminal-state invariants. */
    public CapabilityObservationAdmission {
        schemaVersion = version(schemaVersion);
        admissionFingerprint = fingerprint(
                admissionFingerprint, "admissionFingerprint");
        observationRef = ref(
                observationRef, CapabilityObservationEnvelope.ARTIFACT_KIND,
                "observationRef");
        scope = Objects.requireNonNull(scope, "scope");
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        if (policyRef != null) {
            policyRef = ref(
                    policyRef, "OBSERVATION_ADMISSION_POLICY", "policyRef");
        }
        dataUseGrantRef = ref(
                dataUseGrantRef, "DATA_USE_GRANT", "dataUseGrantRef");
        if (authorityKeyRef != null) {
            authorityKeyRef = ref(
                    authorityKeyRef, "OBSERVATION_AUTHORITY_KEY",
                    "authorityKeyRef");
        }
        state = Objects.requireNonNull(state, "state");
        reason = Objects.requireNonNull(reason, "reason");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        usableUntil = Objects.requireNonNull(usableUntil, "usableUntil");
        if (state == State.ADMITTED
                && (reason != Reason.ACCEPTED || policyRef == null
                || authorityKeyRef == null || !usableUntil.isAfter(decidedAt))) {
            throw new IllegalArgumentException(
                    "admitted observation decision is incomplete");
        }
        if (state == State.QUARANTINED
                && (reason == Reason.ACCEPTED || !usableUntil.equals(decidedAt))) {
            throw new IllegalArgumentException(
                    "quarantined observation decision is inconsistent");
        }
    }

    /**
     * Returns the content-addressed admission decision.
     *
     * @return exact admission artifact reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                observationRef.id() + ":admission",
                1,
                admissionFingerprint);
    }

    /** Terminal corpus admission state. */
    public enum State {
        /** Observation may be consumed until its bounded use horizon. */
        ADMITTED,
        /** Observation is retained only in quarantine and must never be served. */
        QUARANTINED
    }

    /** Closed decision vocabulary safe for metrics, audit, and governance workbooks. */
    public enum Reason {
        /** Every admission control passed. */
        ACCEPTED,
        /** No governed policy exists for the submitted coordinates. */
        ADMISSION_POLICY_NOT_FOUND,
        /** Capability revision is absent, drifted, or not eligible for observation use. */
        CAPABILITY_NOT_ELIGIBLE,
        /** Producer signature, content address, or key lifecycle is invalid. */
        INTEGRITY_REJECTED,
        /** Purpose grant, permitted use, or grant window is unacceptable. */
        DATA_USE_GRANT_REJECTED,
        /** Observation occurrence time violates age or future-skew policy. */
        OBSERVATION_WINDOW_REJECTED,
        /** Sanitized payload metadata violates size, classification, region, or retention policy. */
        PAYLOAD_POLICY_REJECTED,
        /** External vault or sanitization-proof verification rejected a payload reference. */
        PAYLOAD_REFERENCE_REJECTED
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
        String exact = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported capability observation admission schemaVersion");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }
}
