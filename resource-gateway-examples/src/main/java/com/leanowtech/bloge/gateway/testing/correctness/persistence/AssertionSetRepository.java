package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for Assertion Set heads and immutable revisions. */
public interface AssertionSetRepository {

    Optional<StoredAssertionSet> findHead(EnterpriseScope scope, String assertionSetId);

    Optional<StoredAssertionSet> findRevision(
            EnterpriseScope scope, String assertionSetId, long revision);

    List<StoredAssertionSet> revisions(EnterpriseScope scope, String assertionSetId);

    AssertionTargetSummary summarize(EnterpriseScope scope, ExactTargetRef target);

    Optional<StoredAssertionSet> saveIfRevision(
            EnterpriseScope scope,
            long expectedRevision,
            AssertionSet candidate,
            PrincipalRef actor);

    record AssertionTargetSummary(
            int total,
            int draft,
            int valid,
            int stale,
            int unsupported
    ) {
        public AssertionTargetSummary {
            if (total < 0 || draft < 0 || valid < 0 || stale < 0 || unsupported < 0
                    || draft + valid + stale != total || unsupported > total) {
                throw new IllegalArgumentException("Assertion target summary counts are invalid");
            }
        }
    }
}
