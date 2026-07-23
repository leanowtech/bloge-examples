package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical content-addressing boundary for local capability-observation decisions.
 *
 * <p>The decision is not a producer assertion and therefore has no producer signature. Its
 * content address still binds the exact observation, capability, policy, grant, key, state,
 * reason, and use horizon so persistence and downstream corpus revisioning can detect drift.</p>
 */
public final class CapabilityObservationAdmissionIntegrity {
    /** Maximum canonical admission decision size. */
    public static final int MAXIMUM_ADMISSION_BYTES = 256 * 1024;

    private final ObjectMapper mapper;

    /**
     * Creates the canonical admission integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public CapabilityObservationAdmissionIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Creates an admitted decision with an exact bounded use horizon.
     *
     * @param envelope exact signed observation
     * @param policyRef exact operator-owned policy
     * @param authorityKeyRef exact verified producer key
     * @param decidedAt trusted local decision time
     * @param usableUntil exclusive corpus-use bound
     * @return sealed admitted decision
     */
    public CapabilityObservationAdmission admitted(
            CapabilityObservationEnvelope envelope,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef authorityKeyRef,
            Instant decidedAt,
            Instant usableUntil) {
        return seal(candidate(
                envelope,
                policyRef,
                authorityKeyRef,
                CapabilityObservationAdmission.State.ADMITTED,
                CapabilityObservationAdmission.Reason.ACCEPTED,
                decidedAt,
                usableUntil));
    }

    /**
     * Creates a durable quarantine decision.
     *
     * @param envelope exact signed observation
     * @param policyRef resolved policy, or {@code null}
     * @param authorityKeyRef resolved producer key, or {@code null}
     * @param reason closed non-accepted reason
     * @param decidedAt trusted local decision time
     * @return sealed quarantine decision
     */
    public CapabilityObservationAdmission quarantined(
            CapabilityObservationEnvelope envelope,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef authorityKeyRef,
            CapabilityObservationAdmission.Reason reason,
            Instant decidedAt) {
        return seal(candidate(
                envelope,
                policyRef,
                authorityKeyRef,
                CapabilityObservationAdmission.State.QUARANTINED,
                Objects.requireNonNull(reason, "reason"),
                decidedAt,
                decidedAt));
    }

    /**
     * Recomputes and verifies the complete decision fingerprint.
     *
     * @param admission untrusted or persisted decision
     * @return true only when the content address is exact
     */
    public boolean verified(CapabilityObservationAdmission admission) {
        if (admission == null) {
            return false;
        }
        try {
            return admission.admissionFingerprint().equals(seal(admission).admissionFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private CapabilityObservationAdmission candidate(
            CapabilityObservationEnvelope envelope,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef authorityKeyRef,
            CapabilityObservationAdmission.State state,
            CapabilityObservationAdmission.Reason reason,
            Instant decidedAt,
            Instant usableUntil) {
        CapabilityObservationEnvelope exact = Objects.requireNonNull(envelope, "envelope");
        return new CapabilityObservationAdmission(
                "",
                zeroFingerprint(),
                exact.artifactRef(),
                exact.material().scope(),
                exact.material().capabilityRef(),
                policyRef,
                exact.material().dataUseGrant().grantRef(),
                authorityKeyRef,
                state,
                reason,
                decidedAt,
                usableUntil);
    }

    private CapabilityObservationAdmission seal(CapabilityObservationAdmission admission) {
        CapabilityObservationAdmission exact =
                Objects.requireNonNull(admission, "admission");
        var material = new DecisionMaterial(
                CapabilityObservationAdmission.SCHEMA_VERSION,
                "",
                exact.observationRef(),
                exact.scope(),
                exact.capabilityRef(),
                exact.policyRef(),
                exact.dataUseGrantRef(),
                exact.authorityKeyRef(),
                exact.state(),
                exact.reason(),
                exact.decidedAt(),
                exact.usableUntil());
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_ADMISSION_BYTES);
        return new CapabilityObservationAdmission(
                exact.schemaVersion(),
                fingerprint,
                exact.observationRef(),
                exact.scope(),
                exact.capabilityRef(),
                exact.policyRef(),
                exact.dataUseGrantRef(),
                exact.authorityKeyRef(),
                exact.state(),
                exact.reason(),
                exact.decidedAt(),
                exact.usableUntil());
    }

    private static String zeroFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private record DecisionMaterial(
            String schemaVersion,
            String admissionFingerprint,
            MirrorArtifactRef observationRef,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            MirrorArtifactRef policyRef,
            MirrorArtifactRef dataUseGrantRef,
            MirrorArtifactRef authorityKeyRef,
            CapabilityObservationAdmission.State state,
            CapabilityObservationAdmission.Reason reason,
            Instant decidedAt,
            Instant usableUntil
    ) {
    }
}
