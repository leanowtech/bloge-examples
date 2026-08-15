package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for Assertion Set heads and immutable revisions. */
public interface AssertionSetRepository {

    Optional<StoredAssertionSet> findHead(EnterpriseScope scope, String assertionSetId);

    Optional<StoredAssertionSet> findRevision(
            EnterpriseScope scope, String assertionSetId, long revision);

    List<StoredAssertionSet> revisions(EnterpriseScope scope, String assertionSetId);

    Optional<StoredAssertionSet> saveIfRevision(
            EnterpriseScope scope,
            long expectedRevision,
            AssertionSet candidate,
            PrincipalRef actor);
}
