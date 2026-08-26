package com.leanowtech.bloge.gateway.testing.world.draft;

/**
 * Atomic promotion boundary for a materialized World draft.
 *
 * <p>The implementation owns receipt persistence, governed catalog publication, asset promotion,
 * and the candidate head CAS. Callers must not reproduce that sequence outside this port.</p>
 */
@FunctionalInterface
public interface WorldDraftPromotionTransaction {
    WorldDraftCandidate promote(WorldDraftCandidate expected,
                                WorldDraftCandidateService.Access access);
}
