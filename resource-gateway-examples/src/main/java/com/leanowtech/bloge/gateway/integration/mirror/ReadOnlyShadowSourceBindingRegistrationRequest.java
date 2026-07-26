package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;

/**
 * Unsigned registration command for one detached read-only Shadow source binding.
 *
 * <p>Content addresses and the authority seal are intentionally absent. The server derives both
 * after resolving the exact candidate evidence bundle and validating the complete binding
 * closure.</p>
 *
 * @param schemaVersion exact registration wire version
 * @param bindingId stable binding identity
 * @param revision positive immutable revision
 * @param scope exact enterprise namespace
 * @param scenarioCaseRef exact classified Scenario case
 * @param targetCapabilityRef exact observed capability
 * @param candidatePlanRef exact sealed candidate plan
 * @param baselineBindingRef exact governed baseline connector generation
 * @param comparisonPolicyRef exact comparison policy
 * @param requestContextFingerprint canonical paired request identity
 * @param baseline payload-free normalized baseline observation
 * @param candidateEvidenceRef exact signed candidate evidence bundle
 * @param validFrom earliest consumption time
 * @param expiresAt exclusive consumption expiry
 * @param issuedAt source-binding authority issue time
 */
public record ReadOnlyShadowSourceBindingRegistrationRequest(
        String schemaVersion,
        String bindingId,
        long revision,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef candidatePlanRef,
        MirrorArtifactRef baselineBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        String requestContextFingerprint,
        ReadOnlyShadowSourceBinding.BaselineObservation baseline,
        MirrorArtifactRef candidateEvidenceRef,
        Instant validFrom,
        Instant expiresAt,
        Instant issuedAt
) {
    /** Current unsigned source-binding registration protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowSourceBindingRegistrationRequest.v1";

    /** Reuses the signed artifact's structural validation without accepting caller signatures. */
    public ReadOnlyShadowSourceBindingRegistrationRequest {
        if (!SCHEMA_VERSION.equals(
                schemaVersion == null ? "" : schemaVersion.trim())) {
            throw new IllegalArgumentException(
                    "unsupported source-binding registration schemaVersion");
        }
        ReadOnlyShadowSourceBinding normalized =
                new ReadOnlyShadowSourceBinding(
                        ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                        "",
                        bindingId,
                        revision,
                        scope,
                        scenarioCaseRef,
                        targetCapabilityRef,
                        candidatePlanRef,
                        baselineBindingRef,
                        comparisonPolicyRef,
                        requestContextFingerprint,
                        "",
                        baseline,
                        candidateEvidenceRef,
                        validFrom,
                        expiresAt,
                        issuedAt,
                        VisualRunEvidenceSeal.unsigned());
        schemaVersion = SCHEMA_VERSION;
        bindingId = normalized.bindingId();
        revision = normalized.revision();
        scope = normalized.scope();
        scenarioCaseRef = normalized.scenarioCaseRef();
        targetCapabilityRef = normalized.targetCapabilityRef();
        candidatePlanRef = normalized.candidatePlanRef();
        baselineBindingRef = normalized.baselineBindingRef();
        comparisonPolicyRef = normalized.comparisonPolicyRef();
        requestContextFingerprint =
                normalized.requestContextFingerprint();
        baseline = normalized.baseline();
        candidateEvidenceRef = normalized.candidateEvidenceRef();
        validFrom = normalized.validFrom();
        expiresAt = normalized.expiresAt();
        issuedAt = normalized.issuedAt();
    }

    /**
     * Converts caller-owned coordinates into a structurally valid unsigned binding.
     *
     * @return structurally valid unsigned artifact ready for server admission
     */
    public ReadOnlyShadowSourceBinding toUnsignedBinding() {
        return new ReadOnlyShadowSourceBinding(
                ReadOnlyShadowSourceBinding.SCHEMA_VERSION,
                "",
                bindingId,
                revision,
                scope,
                scenarioCaseRef,
                targetCapabilityRef,
                candidatePlanRef,
                baselineBindingRef,
                comparisonPolicyRef,
                requestContextFingerprint,
                "",
                baseline,
                candidateEvidenceRef,
                validFrom,
                expiresAt,
                issuedAt,
                VisualRunEvidenceSeal.unsigned());
    }
}
