package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Host-owned exact binding from a compiler-issued rehearsal plan to its Author source.
 *
 * <p>The default runtime deliberately resolves nothing. Enterprise hosts may provide a resolver
 * backed by an immutable plan-to-source binding registry; implementations must never guess from
 * labels or mutable latest revisions.</p>
 */
@FunctionalInterface
public interface ScenarioRehearsalAuthorTargetResolver {
    /**
     * Resolves an exact authorable source for one immutable compiled plan.
     *
     * @param scope complete enterprise scope
     * @param compiledPlanRef exact compiler-issued plan reference
     * @param runId optional terminal child run identity
     * @return exact Author target, or empty when no proven binding exists
     */
    Optional<ScenarioRehearsalBatchItemAttemptTimeline.AuthorTarget> resolve(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef compiledPlanRef,
            String runId);

    /** @return fail-closed resolver used when no source-binding registry is installed */
    static ScenarioRehearsalAuthorTargetResolver unavailable() {
        return (scope, compiledPlanRef, runId) -> Optional.empty();
    }
}
