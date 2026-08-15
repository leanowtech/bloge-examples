package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;

import java.util.List;
import java.util.Optional;

/** Scope-exact CAS store for Coverage Inventory heads and immutable revisions. */
public interface CoverageInventoryRepository {

    Optional<StoredCoverageInventory> findHead(EnterpriseScope scope, String inventoryId);

    Optional<StoredCoverageInventory> findRevision(
            EnterpriseScope scope, String inventoryId, long revision);

    List<StoredCoverageInventory> revisions(EnterpriseScope scope, String inventoryId);

    Optional<StoredCoverageInventory> saveIfRevision(
            long expectedRevision,
            CoverageInventory candidate,
            PrincipalRef actor);
}
