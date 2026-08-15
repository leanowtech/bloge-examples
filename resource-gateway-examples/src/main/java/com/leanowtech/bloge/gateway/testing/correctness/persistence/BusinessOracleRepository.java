package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for Business Oracle heads and immutable revisions. */
public interface BusinessOracleRepository {

    Optional<StoredBusinessOracle> findHead(EnterpriseScope scope, String oracleId);

    Optional<StoredBusinessOracle> findRevision(
            EnterpriseScope scope, String oracleId, long revision);

    List<StoredBusinessOracle> revisions(EnterpriseScope scope, String oracleId);

    Optional<StoredBusinessOracle> saveIfRevision(
            long expectedRevision,
            BusinessOracle candidate,
            PrincipalRef actor);
}
