package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.CoverageVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.StaleReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adds an exact frozen-denominator summary without deriving fulfillment client-side. */
public final class InventoryCorrectnessWorkspaceComponentSource
        implements CorrectnessWorkspaceComponentSource {

    private final CorrectnessWorkspaceComponentSource delegate;
    private final CoverageInventoryRepository inventories;

    public InventoryCorrectnessWorkspaceComponentSource(
            CorrectnessWorkspaceComponentSource delegate,
            CoverageInventoryRepository inventories
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    @Override
    public Components load(Coordinate coordinate, PageRequest pageRequest) {
        Components base = delegate.load(coordinate, pageRequest);
        ExactAssetRef requested = coordinate.activeInventoryRef();
        if (requested == null) return base;

        StoredCoverageInventory stored = inventories.findRevision(
                coordinate.scope(), requested.id(), requested.revision()).orElse(null);
        if (!valid(stored, requested, coordinate)) {
            List<StaleReason> stale = new ArrayList<>(base.staleReasons());
            stale.add(new StaleReason(
                    "INVENTORY_REFERENCE_STALE", "INVENTORY", requested));
            return copy(base, CoverageSummary.unavailable(),
                    verdict(base.verdict(), CoverageVerdict.STALE,
                            "INVENTORY_REFERENCE_STALE", "REVIEW_COVERAGE_IMPACT"),
                    List.copyOf(stale), base.reviews(), base.capabilities());
        }

        CoverageInventory inventory = stored.inventory();
        int total = (int) inventory.obligations().stream()
                .filter(value -> value.lifecycle() != ObligationLifecycle.RETIRED).count();
        int waived = (int) inventory.obligations().stream()
                .filter(value -> value.lifecycle() == ObligationLifecycle.WAIVED).count();
        int uncovered = (int) inventory.obligations().stream()
                .filter(value -> value.lifecycle() == ObligationLifecycle.FROZEN).count();
        CoverageSummary coverage = new CoverageSummary(
                Availability.AVAILABLE, requested, inventory.lifecycle().name(),
                total, 0, waived, uncovered);
        CoverageVerdict coverageVerdict = uncovered == 0
                ? CoverageVerdict.COMPLETE : CoverageVerdict.INCOMPLETE;
        String reason = uncovered == 0 ? "COVERAGE_WAIVERS_RECORDED" : "COVERAGE_CASES_REQUIRED";
        String action = uncovered == 0 ? "REVIEW_WAIVERS" : "CREATE_CASES_FROM_OBLIGATIONS";
        ReviewSummary reviews = new ReviewSummary(
                base.reviews().pending(), base.reviews().approved() + 1,
                base.reviews().rejected(), base.reviews().stale());
        List<String> capabilities = new ArrayList<>(base.capabilities());
        capabilities.add("COVERAGE_INVENTORY_READ_V1");
        return copy(base, coverage,
                verdict(base.verdict(), coverageVerdict, reason, action),
                base.staleReasons(), reviews, List.copyOf(capabilities));
    }

    private static boolean valid(
            StoredCoverageInventory stored,
            ExactAssetRef requested,
            Coordinate coordinate
    ) {
        if (stored == null || !"INVENTORY".equals(requested.kind())
                || !stored.inventoryFingerprint().equals(requested.fingerprint())) {
            return false;
        }
        CoverageInventory value = stored.inventory();
        return value.lifecycle() == InventoryLifecycle.FROZEN
                && value.target().equals(coordinate.target())
                && value.scope().equals(coordinate.scope())
                && value.inventoryId().equals(requested.id())
                && value.revision() == requested.revision();
    }

    private static CorrectnessVerdict verdict(
            CorrectnessVerdict base,
            CoverageVerdict coverage,
            String reasonCode,
            String action
    ) {
        List<CorrectnessVerdict.Reason> reasons = new ArrayList<>(base.reasons());
        reasons.removeIf(reason -> reason.axis().equals("COVERAGE"));
        reasons.add(new CorrectnessVerdict.Reason(
                reasonCode, "COVERAGE", "correctness.coverage." + reasonCode.toLowerCase()));
        List<CorrectnessVerdict.Remediation> actions = new ArrayList<>(base.nextActions());
        actions.removeIf(item -> item.command().equals("OPEN_COVERAGE_INVENTORY"));
        actions.add(new CorrectnessVerdict.Remediation(action, reasonCode));
        return new CorrectnessVerdict(
                base.execution(), base.assertions(), coverage, base.evidence(), base.gate(),
                base.proofLevel(), List.copyOf(reasons), List.copyOf(actions));
    }

    private static Components copy(
            Components base,
            CoverageSummary coverage,
            CorrectnessVerdict verdict,
            List<StaleReason> staleReasons,
            ReviewSummary reviews,
            List<String> capabilities
    ) {
        return new Components(
                coverage, base.cases(), base.fixtures(), reviews, base.lastPublication(),
                base.lastRun(), verdict, staleReasons, capabilities, base.commandPolicy());
    }
}
