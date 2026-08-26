package com.leanowtech.bloge.gateway.testing.world.draft;

/** Safe external view of a materialized draft; no Scenario context or fragment source is included. */
public record WorldDraftMaterializedProjection(String candidateId, String tenantId,
                                                String worldFingerprint, long worldRevision,
                                                String ruleFingerprint, String fragmentFingerprint,
                                                boolean scenarioPresent, boolean published) {
    public WorldDraftMaterializedProjection {
        if (candidateId == null || candidateId.isBlank() || tenantId == null || tenantId.isBlank()
                || !fp(worldFingerprint) || worldRevision < 1 || !fp(ruleFingerprint)
                || (fragmentFingerprint != null && !fragmentFingerprint.isBlank() && !fp(fragmentFingerprint))) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.MATERIALIZATION_INVALID);
        }
    }

    private static boolean fp(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
}
