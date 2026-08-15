package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for mutable Definition heads and immutable retained revisions. */
public interface CorrectnessDefinitionRepository {

    Optional<StoredCorrectnessDefinition> findHead(EnterpriseScope scope, String definitionId);

    Optional<StoredCorrectnessDefinition> findRevision(
            EnterpriseScope scope, String definitionId, long revision);

    List<StoredCorrectnessDefinition> revisions(EnterpriseScope scope, String definitionId);

    Optional<StoredCorrectnessDefinition> saveIfRevision(
            long expectedRevision,
            CorrectnessDefinition candidate,
            PrincipalRef actor);
}
