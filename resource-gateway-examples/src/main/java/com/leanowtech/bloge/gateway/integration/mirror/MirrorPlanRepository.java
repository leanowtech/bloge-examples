package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only persistence boundary for sealed, payload-free mirror plans.
 *
 * <p>Every lookup requires the complete enterprise scope. Implementations must verify the plan
 * seal on writes and reads and must treat an exact {@code scope + planId} retry as idempotent only
 * when its plan fingerprint is unchanged.</p>
 */
public interface MirrorPlanRepository {
    /**
     * Persists a sealed plan or returns the identical plan already stored under the same identity.
     *
     * @param plan sealed payload-free mirror plan
     * @return persisted plan
     */
    MirrorPlan create(MirrorPlan plan);

    /**
     * Finds one verified plan inside an exact enterprise scope.
     *
     * @param scope full authenticated enterprise scope
     * @param planId caller-scoped plan identity
     * @return verified plan when present
     */
    Optional<MirrorPlan> find(CapabilitySnapshot.Scope scope, String planId);
}
