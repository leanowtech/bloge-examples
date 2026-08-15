package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;

import java.util.Optional;

/** Scope-exact immutable store for payload-free correctness evidence companions. */
public interface CorrectnessEvidenceRepository {

    Optional<StoredCorrectnessEvidenceCompanion> find(
            EnterpriseScope scope, String suiteRunId);

    StoredCorrectnessEvidenceCompanion saveIfAbsent(
            EnterpriseScope scope, StoredCorrectnessEvidenceCompanion candidate);
}
