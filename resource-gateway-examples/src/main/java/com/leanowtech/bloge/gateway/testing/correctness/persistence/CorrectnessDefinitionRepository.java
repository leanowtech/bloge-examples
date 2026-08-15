package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for mutable Definition heads and immutable retained revisions. */
public interface CorrectnessDefinitionRepository {

    Optional<StoredCorrectnessDefinition> findHead(EnterpriseScope scope, String definitionId);

    /**
     * Resolves at most two current definitions for an exact target coordinate.
     *
     * <p>Two rows are sufficient to distinguish not-found, unique, and ambiguous lookups without
     * loading an unbounded tenant catalog. Callers must require an explicit definition id when
     * this method returns two rows.</p>
     */
    List<StoredCorrectnessDefinition> findHeadCandidatesByTarget(
            EnterpriseScope scope,
            TargetKind targetKind,
            String targetId,
            String targetFingerprint);

    Optional<StoredCorrectnessDefinition> findRevision(
            EnterpriseScope scope, String definitionId, long revision);

    List<StoredCorrectnessDefinition> revisions(EnterpriseScope scope, String definitionId);

    Optional<StoredCorrectnessDefinition> saveIfRevision(
            long expectedRevision,
            CorrectnessDefinition candidate,
            PrincipalRef actor);
}
