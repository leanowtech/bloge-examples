package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for Business Oracle heads and immutable revisions. */
public interface BusinessOracleRepository {

    Optional<StoredBusinessOracle> findHead(EnterpriseScope scope, String oracleId);

    Optional<StoredBusinessOracle> findRevision(
            EnterpriseScope scope, String oracleId, long revision);

    List<StoredBusinessOracle> revisions(EnterpriseScope scope, String oracleId);

    OracleTargetSummary summarize(EnterpriseScope scope, ExactTargetRef target);

    Optional<StoredBusinessOracle> saveIfRevision(
            long expectedRevision,
            BusinessOracle candidate,
            PrincipalRef actor);

    record OracleTargetSummary(int total, int proposed, int approved, int superseded) {
        public OracleTargetSummary {
            if (total < 0 || proposed < 0 || approved < 0 || superseded < 0
                    || proposed + approved + superseded != total) {
                throw new IllegalArgumentException("Oracle target summary counts are invalid");
            }
        }
    }
}
