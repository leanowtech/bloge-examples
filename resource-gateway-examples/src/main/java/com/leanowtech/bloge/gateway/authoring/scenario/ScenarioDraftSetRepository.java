package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.util.List;
import java.util.Optional;

/**
 * Scope-isolated mutable Scenario authoring repository with immutable revision history.
 */
public interface ScenarioDraftSetRepository {

    /**
     * Resolves the current revision inside one exact enterprise scope.
     *
     * @param scope complete tenant/organization/project/environment/region scope
     * @param scenarioDraftSetId stable authoring asset id
     * @return current stored revision
     */
    Optional<StoredScenarioDraftSet> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId);

    /**
     * Lists retained immutable snapshots newest first.
     *
     * @param scope complete enterprise scope
     * @param scenarioDraftSetId stable authoring asset id
     * @return revision history
     */
    List<StoredScenarioDraftSet> revisions(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId);

    /**
     * Resolves one retained exact source revision.
     *
     * @param scope complete enterprise scope
     * @param scenarioDraftSetId stable authoring asset id
     * @param revision positive retained revision
     * @return exact immutable source snapshot
     */
    default Optional<StoredScenarioDraftSet> findRevision(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            long revision) {
        return revisions(scope, scenarioDraftSetId).stream()
                .filter(candidate -> candidate.revision() == revision)
                .findFirst();
    }

    /**
     * Stores the next revision only when the current revision equals the caller's expectation.
     *
     * <p>An expected revision of zero creates a new asset. Returning empty means another author
     * changed or created the asset; no silent last-write-wins update is permitted.</p>
     *
     * @param expectedRevision revision observed by the caller
     * @param candidate authoring payload
     * @param actor verified workload actor
     * @return stored next revision, or empty on optimistic-concurrency conflict
     */
    Optional<StoredScenarioDraftSet> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSet candidate,
            String actor);

    /**
     * Stores a Scenario revision and its server-authoritative Contract baseline atomically.
     *
     * <p>The default preserves compatibility with simple adapters. Industrial repositories should
     * override it so compatibility reports remain available across process restarts.</p>
     *
     * @param expectedRevision revision observed by the caller
     * @param candidate authoring payload
     * @param contractBaseline exact Contract accepted by validation
     * @param actor verified workload actor
     * @return stored next revision, or empty on optimistic-concurrency conflict
     */
    default Optional<StoredScenarioDraftSet> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSet candidate,
            ContractDraft contractBaseline,
            String actor) {
        return saveIfRevision(expectedRevision, candidate, actor);
    }

    /**
     * Reads the immutable Contract baseline captured with one Scenario revision.
     *
     * @param scope complete enterprise scope
     * @param scenarioDraftSetId stable authoring asset id
     * @param revision exact retained Scenario revision
     * @return integrity-verified baseline when the adapter supports capture
     */
    default Optional<ScenarioContractBaseline> findContractBaseline(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            long revision) {
        return Optional.empty();
    }
}
