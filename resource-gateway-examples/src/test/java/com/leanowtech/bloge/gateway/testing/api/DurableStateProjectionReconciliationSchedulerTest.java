package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableStateProjectionReconciliationSchedulerTest {

    @Test
    void advancesOnlyAfterSuccessAndRetriesTheSameCursorAfterStoreFailure() {
        DurableStateProjectionReconciler reconciler =
                mock(DurableStateProjectionReconciler.class);
        DurableStateProjectionReconciler.ScanCursor start =
                DurableStateProjectionReconciler.ScanCursor.start();
        DurableStateProjectionReconciler.ScanCursor next =
                new DurableStateProjectionReconciler.ScanCursor("engine-10", "item-10");
        when(reconciler.sweep(start, 1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED))
                .thenReturn(result(next));
        when(reconciler.sweep(next, 1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(result(start));
        DurableStateProjectionReconciliationScheduler scheduler =
                new DurableStateProjectionReconciliationScheduler(
                        reconciler, 5000,
                        DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);

        scheduler.reconcile();
        scheduler.reconcile();
        scheduler.reconcile();

        verify(reconciler).sweep(start, 1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
        verify(reconciler, times(2)).sweep(next, 1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
    }

    @Test
    void rejectsUnknownRepairPolicyInsteadOfSilentlyDisablingRepair() {
        assertThatThrownBy(() -> DurableStateProjectionReconciler.RepairMode.parse("best-effort"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DurableStateProjectionReconciler.SweepResult result(
            DurableStateProjectionReconciler.ScanCursor cursor) {
        return new DurableStateProjectionReconciler.SweepResult(
                cursor, 0, 0, 0, 0, 0, 0, List.of());
    }
}
