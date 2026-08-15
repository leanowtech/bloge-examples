package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.CoverageVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Components;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryCorrectnessWorkspaceComponentSourceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void projectsFrozenDenominatorWithoutInventingFulfillment() {
        StoredCoverageInventory stored = storedInventory();
        var source = new InventoryCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(),
                new SingleInventoryRepository(stored));
        ExactAssetRef inventoryRef = inventoryRef(stored);

        Components result = source.load(
                coordinate(inventoryRef), page());

        assertThat(result.coverage().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.coverage().total()).isEqualTo(3);
        assertThat(result.coverage().fulfilled()).isZero();
        assertThat(result.coverage().waived()).isEqualTo(1);
        assertThat(result.coverage().uncovered()).isEqualTo(2);
        assertThat(result.verdict().coverage()).isEqualTo(CoverageVerdict.INCOMPLETE);
        assertThat(result.verdict().gate().name()).isEqualTo("BLOCKED");
        assertThat(result.capabilities()).contains("COVERAGE_INVENTORY_READ_V1");
        assertThat(result.reviews().approved()).isEqualTo(1);
    }

    @Test
    void exactReferenceDriftFailsClosedAsStaleCoverage() {
        StoredCoverageInventory stored = storedInventory();
        var source = new InventoryCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(),
                new SingleInventoryRepository(stored));
        ExactAssetRef forged = new ExactAssetRef(
                "INVENTORY", "loan-inventory", 2, fingerprint('f'));

        Components result = source.load(coordinate(forged), page());

        assertThat(result.coverage().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.verdict().coverage()).isEqualTo(CoverageVerdict.STALE);
        assertThat(result.staleReasons())
                .extracting(CorrectnessWorkspaceProjection.StaleReason::code)
                .containsExactly("INVENTORY_REFERENCE_STALE");
    }

    private StoredCoverageInventory storedInventory() {
        Instant now = Instant.parse("2026-08-15T08:00:00Z");
        CoverageInventory inventory = new CoverageInventory(
                "", "loan-inventory", 2, scope(), target(), InventoryLifecycle.FROZEN,
                List.of(
                        obligation("policy", ObligationLifecycle.FROZEN, null),
                        obligation("risk", ObligationLifecycle.FROZEN, null),
                        obligation("boundary", ObligationLifecycle.WAIVED,
                                new Waiver("Temporary exception", now.plusSeconds(3600),
                                        reviewer(), now.minusSeconds(60)))),
                List.of(new ExactSourceSnapshotRef(
                        "DAG", "loan-graph", 3, fingerprint('a'))),
                new ReviewRecord(
                        ReviewStatus.APPROVED, reviewer(), now, "Freeze denominator"),
                new AuditMetadata(now, now, author(), reviewer()));
        return StoredCoverageInventory.verified(mapper, inventory);
    }

    private CoverageObligation obligation(
            String id,
            ObligationLifecycle lifecycle,
            Waiver waiver
    ) {
        return new CoverageObligation(
                id, ObligationDimension.RISK, id, "Required behavior " + id,
                RiskLevel.HIGH, author(), ObligationSource.BUSINESS, lifecycle,
                waiver, List.of());
    }

    private Coordinate coordinate(ExactAssetRef inventoryRef) {
        return new Coordinate(
                scope(), new ExactAssetRef("DEFINITION", "definition-a", 1, fingerprint('d')),
                target(), inventoryRef);
    }

    private PageRequest page() {
        return new PageRequest("", 100, fingerprint('e'));
    }

    private ExactAssetRef inventoryRef(StoredCoverageInventory stored) {
        return new ExactAssetRef(
                "INVENTORY", "loan-inventory", 2, stored.inventoryFingerprint());
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private record SingleInventoryRepository(StoredCoverageInventory stored)
            implements CoverageInventoryRepository {
        @Override
        public Optional<StoredCoverageInventory> findHead(
                EnterpriseScope scope,
                String inventoryId
        ) {
            return matches(scope, inventoryId) ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public Optional<StoredCoverageInventory> findRevision(
                EnterpriseScope scope,
                String inventoryId,
                long revision
        ) {
            return matches(scope, inventoryId) && stored.inventory().revision() == revision
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public List<StoredCoverageInventory> revisions(
                EnterpriseScope scope,
                String inventoryId
        ) {
            return findHead(scope, inventoryId).stream().toList();
        }

        @Override
        public Optional<StoredCoverageInventory> saveIfRevision(
                long expectedRevision,
                CoverageInventory candidate,
                PrincipalRef actor
        ) {
            throw new UnsupportedOperationException();
        }

        private boolean matches(EnterpriseScope scope, String inventoryId) {
            return stored.inventory().scope().equals(scope)
                    && stored.inventory().inventoryId().equals(inventoryId);
        }
    }
}
