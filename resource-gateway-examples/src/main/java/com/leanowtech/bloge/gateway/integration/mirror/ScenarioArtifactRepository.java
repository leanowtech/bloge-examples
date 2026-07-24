package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only, full-scope registry for governed ScenarioPack protocol artifacts.
 *
 * <p>Implementations verify canonical content identities on both writes and reads. An exact
 * scope, artifact id, and revision retry is idempotent only when the fingerprint is unchanged.</p>
 */
public interface ScenarioArtifactRepository {
    /** Persists one sealed handling assertion revision. */
    CaseHandlingAssertion create(CaseHandlingAssertion assertion);

    /** Persists one sealed ScenarioCase revision. */
    ScenarioCase create(ScenarioCase scenarioCase);

    /** Persists one sealed ScenarioPack revision. */
    ScenarioPack create(ScenarioPack pack);

    /** Persists one independently verified signed checkpoint bundle. */
    MirrorSessionCheckpointBundle create(MirrorSessionCheckpointBundle checkpoint);

    /** Finds one exact assertion revision inside a complete enterprise scope. */
    Optional<CaseHandlingAssertion> findAssertion(
            CapabilitySnapshot.Scope scope, String assertionId, long revision);

    /** Finds one exact ScenarioCase revision inside a complete enterprise scope. */
    Optional<ScenarioCase> findCase(
            CapabilitySnapshot.Scope scope, String caseId, long revision);

    /** Finds one exact ScenarioPack revision inside a complete enterprise scope. */
    Optional<ScenarioPack> findPack(
            CapabilitySnapshot.Scope scope, String packId, long revision);

    /** Finds one exact signed checkpoint revision inside a complete enterprise scope. */
    Optional<MirrorSessionCheckpointBundle> findCheckpoint(
            CapabilitySnapshot.Scope scope, String checkpointId, long revision);
}
