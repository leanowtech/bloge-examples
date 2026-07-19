package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationExternalArchiveReconciliationServiceTest {
    private static final String AUTHORITY = "archive-a";

    private TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority;
    private DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            inventories;
    private DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
            comparisons;
    private DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings;

    @BeforeEach
    void setUp() {
        authority = mock(TestSuiteStabilityObservationExternalArchiveInventoryAuthority.class);
        inventories = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane.class);
        comparisons = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane.class);
        findings = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.class);
        when(authority.inventoryAuthorities()).thenReturn(List.of(AUTHORITY));
    }

    @Test
    void initialOrActiveCycleAdvancesOnlyInventory() {
        var service = service();
        when(inventories.operationalSnapshot(AUTHORITY))
                .thenReturn(snapshot(false, false))
                .thenReturn(snapshot(true, true));
        when(inventories.stageNextPage(AUTHORITY))
                .thenReturn(page(
                        DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                                .PageStatus.STAGED, 2, 2))
                .thenReturn(page(
                        DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                                .PageStatus.COMPLETED, 1, 3));

        assertThat(service.advance(AUTHORITY).stage()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .INVENTORY_STAGED);
        assertThat(service.advance(AUTHORITY).stage()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .INVENTORY_COMPLETED);

        verify(findings, never()).projectNextPage(AUTHORITY);
        verify(comparisons, never()).compareNextPage(AUTHORITY);
    }

    @Test
    void findingBacklogHasPriorityAndPreventsComparisonOrNewInventory() {
        var service = service();
        when(inventories.operationalSnapshot(AUTHORITY)).thenReturn(snapshot(false, true));
        when(findings.projectNextPage(AUTHORITY)).thenReturn(new
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .ProjectionPage(
                        DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                                .ProjectionStatus.STAGED,
                        AUTHORITY, uuid(), uuid(), 0, 5, 5, 2));

        var result = service.advance(AUTHORITY);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .FINDING_STAGED);
        assertThat(result.processedOnPage()).isEqualTo(5);
        assertThat(result.findingsOrTransitions()).isEqualTo(2);
        verify(comparisons, never()).compareNextPage(AUTHORITY);
        verify(inventories, never()).stageNextPage(AUTHORITY);
    }

    @Test
    void comparisonBacklogRunsOnlyAfterFindingProjectionIsCurrent() {
        var service = service();
        when(inventories.operationalSnapshot(AUTHORITY)).thenReturn(snapshot(false, true));
        when(findings.projectNextPage(AUTHORITY)).thenReturn(currentProjection());
        when(comparisons.compareNextPage(AUTHORITY)).thenReturn(new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonPage(
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .ComparisonStatus.COMPLETED,
                        AUTHORITY, uuid(), uuid(), 0, 7, 7, 3));

        var result = service.advance(AUTHORITY);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .COMPARISON_COMPLETED);
        assertThat(result.totalProcessed()).isEqualTo(7);
        assertThat(result.findingsOrTransitions()).isEqualTo(3);
        verify(inventories, never()).stageNextPage(AUTHORITY);
    }

    @Test
    void newInventoryStartsOnlyAfterBothDownstreamStagesAreCurrent() {
        var service = service();
        when(inventories.operationalSnapshot(AUTHORITY)).thenReturn(snapshot(false, true));
        when(findings.projectNextPage(AUTHORITY)).thenReturn(currentProjection());
        when(comparisons.compareNextPage(AUTHORITY)).thenReturn(new
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .ComparisonPage(
                        DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                                .ComparisonStatus.CURRENT,
                        AUTHORITY, uuid(), uuid(), 1, 0, 4, 0));
        when(inventories.stageNextPage(AUTHORITY)).thenReturn(page(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.BUSY, 0, 0));

        var result = service.advance(AUTHORITY);

        assertThat(result.stage()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .INVENTORY_LEASE_BUSY);
        verify(findings).projectNextPage(AUTHORITY);
        verify(comparisons).compareNextPage(AUTHORITY);
        verify(inventories).stageNextPage(AUTHORITY);
    }

    @Test
    void rejectsUnknownUnsortedDuplicateAndUnboundedMembership() {
        var service = service();
        assertThatThrownBy(() -> service.advance("archive-b"))
                .isInstanceOf(IllegalArgumentException.class);

        when(authority.inventoryAuthorities()).thenReturn(List.of("archive-b", "archive-a"));
        assertThatThrownBy(this::service)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("membership");

        when(authority.inventoryAuthorities()).thenReturn(List.of("archive-a", "archive-a"));
        assertThatThrownBy(this::service)
                .isInstanceOf(IllegalStateException.class);

        List<String> unbounded = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            unbounded.add("archive-" + index);
        }
        when(authority.inventoryAuthorities()).thenReturn(unbounded);
        assertThatThrownBy(this::service)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unbounded");
    }

    @Test
    void operationalSnapshotRejectsImpossibleLifecycleShapes() {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");

        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .OperationalSnapshot(
                        now, true, false, false, 0, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .OperationalSnapshot(
                        now, false, true, false, 1, 1, now, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestSuiteStabilityObservationExternalArchiveReconciliationService service() {
        return new TestSuiteStabilityObservationExternalArchiveReconciliationService(
                authority, inventories, comparisons, findings);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
            .ProjectionPage currentProjection() {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                .ProjectionPage(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .ProjectionStatus.CURRENT,
                AUTHORITY, "", "", 0, 0, 0, 0);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            .OperationalSnapshot snapshot(boolean active, boolean completed) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        return new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .OperationalSnapshot(
                now, false, active, completed, active ? 1 : 0, active ? 2 : 0,
                active ? now.minusSeconds(10) : null,
                active ? now.minusSeconds(5) : null,
                completed ? now.minusSeconds(20) : null);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            .PageAttempt page(
                    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                            .PageStatus status,
                    int pageItems,
                    long total) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .PageAttempt(status, AUTHORITY,
                status == DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.BUSY ? "" : uuid(),
                0, pageItems, total);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
