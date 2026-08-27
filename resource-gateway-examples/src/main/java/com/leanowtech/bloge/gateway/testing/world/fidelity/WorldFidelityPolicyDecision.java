package com.leanowtech.bloge.gateway.testing.world.fidelity;

/** Payload-free policy result that annotates, but never mutates, historical evidence. */
public record WorldFidelityPolicyDecision(
        String tenantId,
        String targetFingerprint,
        String evidenceFingerprint,
        WorldFidelityDriftRepository.DriftState driftState,
        WorldFidelityDriftService.EvidenceCeiling evidenceCeiling,
        boolean publicationAllowed,
        String decisionFingerprint) {
    public WorldFidelityPolicyDecision {
        tenantId = WorldFidelityRequest.text(tenantId, 512);
        targetFingerprint = WorldFidelityRunner.fingerprint(targetFingerprint);
        evidenceFingerprint = WorldFidelityRunner.fingerprint(evidenceFingerprint);
        if (evidenceCeiling == null) throw WorldFidelityException.of(
                WorldFidelityException.Code.INVALID_INPUT);
        decisionFingerprint = WorldFidelityRunner.fingerprint(decisionFingerprint);
    }
}
