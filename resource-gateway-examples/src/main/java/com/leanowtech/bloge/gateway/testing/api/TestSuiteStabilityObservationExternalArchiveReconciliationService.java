package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded pipeline coordinator for one external observation-archive authority.
 *
 * <p>The coordinator drains governed finding projection before comparison and drains comparison
 * before opening another remote inventory cycle. This downstream-first order supplies durable
 * backpressure: a fast authority cannot create an unbounded sequence of completed inventory cycles
 * while classification or finding projection is behind. Each invocation mutates at most one stage;
 * every stage keeps its own database transaction, replay gates, and lease fences.</p>
 */
public final class TestSuiteStabilityObservationExternalArchiveReconciliationService {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            inventories;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            comparisons;
    private final DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings;

    /**
     * Creates the profile-gated downstream-first pipeline.
     *
     * @param authority configured read-only inventory authority and stable membership source
     * @param inventories database-leased signed inventory-cycle control plane
     * @param comparisons frozen bidirectional comparison control plane
     * @param findings replay-verified governed finding projection control plane
     */
    public TestSuiteStabilityObservationExternalArchiveReconciliationService(
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                    inventories,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    comparisons,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.findings = Objects.requireNonNull(findings, "findings");
        authorities();
    }

    /**
     * Returns the complete configured authority set in stable lexical order.
     *
     * @return immutable non-empty list bounded by the external receipt protocol
     */
    public List<String> authorities() {
        List<String> configured = List.copyOf(authority.inventoryAuthorities());
        if (configured.isEmpty()
                || configured.size()
                > TestSuiteStabilityObservationExternalArchiveReceiptSet.MAXIMUM_RECEIPTS) {
            throw new IllegalStateException(
                    "External inventory authority membership is empty or unbounded");
        }
        Set<String> unique = new HashSet<>();
        String previous = "";
        for (String candidate : configured) {
            String exact = Objects.requireNonNullElse(candidate, "").trim();
            if (!IDENTIFIER.matcher(exact).matches() || !unique.add(exact)
                    || !previous.isEmpty() && previous.compareTo(exact) >= 0) {
                throw new IllegalStateException(
                        "External inventory authority membership is invalid or unstable");
            }
            previous = exact;
        }
        return configured;
    }

    /**
     * Advances at most one durable stage for one exact configured authority.
     *
     * <p>An active or absent initial inventory cycle advances inventory directly. Otherwise the
     * method first gives an incomplete finding projection one page, then an incomplete comparison
     * one page. A new inventory cycle starts only when both downstream stages report
     * {@code CURRENT}. Lease-busy is a successful no-op and never falls through to another stage.</p>
     *
     * @param authorityId exact configured authority
     * @return identity-free closed progress result
     */
    public AuthorityAttempt advance(String authorityId) {
        String exact = configuredAuthority(authorityId);
        DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .OperationalSnapshot inventory = inventories.operationalSnapshot(exact);
        if (inventory.activeCycle() || !inventory.completedCycle()) {
            return inventory(inventories.stageNextPage(exact));
        }

        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.ProjectionPage
                projection = findings.projectNextPage(exact);
        if (projection.status()
                != DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .ProjectionStatus.CURRENT) {
            return finding(projection);
        }

        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonPage comparison = comparisons.compareNextPage(exact);
        if (comparison.status()
                != DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonStatus.CURRENT) {
            return comparison(comparison);
        }
        return inventory(inventories.stageNextPage(exact));
    }

    private String configuredAuthority(String authorityId) {
        String exact = Objects.requireNonNullElse(authorityId, "").trim();
        if (!authorities().contains(exact)) {
            throw new IllegalArgumentException(
                    "External inventory authority is not configured");
        }
        return exact;
    }

    private static AuthorityAttempt inventory(
            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                    .PageAttempt page) {
        Stage stage = switch (page.status()) {
            case BUSY -> Stage.INVENTORY_LEASE_BUSY;
            case STAGED -> Stage.INVENTORY_STAGED;
            case COMPLETED -> Stage.INVENTORY_COMPLETED;
            case SNAPSHOT_EXPIRED -> Stage.INVENTORY_SNAPSHOT_EXPIRED;
        };
        return new AuthorityAttempt(stage, page.pageItemCount(),
                page.accumulatedObjectCount(), 0);
    }

    private static AuthorityAttempt comparison(
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    .ComparisonPage page) {
        Stage stage = page.status()
                == DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                .ComparisonStatus.STAGED
                ? Stage.COMPARISON_STAGED : Stage.COMPARISON_COMPLETED;
        return new AuthorityAttempt(stage, page.pageObjectCount(),
                page.classifiedObjectCount(), page.findingObjectCount());
    }

    private static AuthorityAttempt finding(
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                    .ProjectionPage page) {
        Stage stage = page.status()
                == DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .ProjectionStatus.STAGED
                ? Stage.FINDING_STAGED : Stage.FINDING_COMPLETED;
        return new AuthorityAttempt(stage, page.processedOnPage(),
                page.totalProcessed(), page.actionableTransitions());
    }

    /** Closed, fixed-cardinality stage outcomes suitable for logs and metrics. */
    public enum Stage {
        /** Another replica owns the inventory page lease. */
        INVENTORY_LEASE_BUSY,
        /** A non-terminal signed inventory page committed. */
        INVENTORY_STAGED,
        /** The terminal signed inventory page passed count/root replay. */
        INVENTORY_COMPLETED,
        /** The provider expired the pinned inventory snapshot. */
        INVENTORY_SNAPSHOT_EXPIRED,
        /** A non-terminal frozen comparison page committed. */
        COMPARISON_STAGED,
        /** A comparison passed union coverage and semantic replay. */
        COMPARISON_COMPLETED,
        /** A non-terminal governed finding page committed. */
        FINDING_STAGED,
        /** A finding projection passed source, event, and resulting-state replay. */
        FINDING_COMPLETED
    }

    /**
     * Identity-free result from one authority advance.
     *
     * @param stage exact bounded stage outcome
     * @param processedOnPage rows committed by this invocation
     * @param totalProcessed cumulative rows in the active or completed stage
     * @param findingsOrTransitions cumulative discrepancies or actionable transitions
     */
    public record AuthorityAttempt(
            Stage stage,
            int processedOnPage,
            long totalProcessed,
            long findingsOrTransitions) {
        /** Rejects impossible counters before operational consumers observe them. */
        public AuthorityAttempt {
            Objects.requireNonNull(stage, "stage");
            if (processedOnPage < 0 || totalProcessed < processedOnPage
                    || findingsOrTransitions < 0
                    || findingsOrTransitions > totalProcessed
                    || stage == Stage.INVENTORY_LEASE_BUSY
                    && (processedOnPage != 0 || totalProcessed != 0
                    || findingsOrTransitions != 0)) {
                throw new IllegalArgumentException(
                        "Invalid external reconciliation authority attempt");
            }
        }
    }
}
