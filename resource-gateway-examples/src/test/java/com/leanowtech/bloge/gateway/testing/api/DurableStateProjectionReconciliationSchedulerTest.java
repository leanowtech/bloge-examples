package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableStateProjectionReconciliationSchedulerTest {

    @Test
    void delegatesDurableCursorOwnershipAndSurvivesBusyAndFailedTicks() {
        DatabaseDurableStateProjectionControlPlane controlPlane =
                mock(DatabaseDurableStateProjectionControlPlane.class);
        DurableStateProjectionTelemetry telemetry = mock(DurableStateProjectionTelemetry.class);
        DurableStateProjectionReconciler.ScanCursor next =
                new DurableStateProjectionReconciler.ScanCursor("engine-10", "item-10");
        when(controlPlane.reconcilePage(1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED))
                .thenReturn(DatabaseDurableStateProjectionControlPlane.SweepAttempt.busy())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new DatabaseDurableStateProjectionControlPlane.SweepAttempt(
                        DatabaseDurableStateProjectionControlPlane.SweepStatus.COMPLETED,
                        result(next)));
        DurableStateProjectionReconciliationScheduler scheduler =
                new DurableStateProjectionReconciliationScheduler(
                        controlPlane, 5000,
                        DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED, telemetry);

        scheduler.reconcile();
        scheduler.reconcile();
        scheduler.reconcile();

        verify(controlPlane, org.mockito.Mockito.times(3)).reconcilePage(1000,
                DurableStateProjectionReconciler.RepairMode.REPAIR_DERIVED);
        verify(telemetry, times(2)).recordReconciliation(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Duration.class));
        verify(telemetry).recordReconciliationFailure(
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void rejectsUnknownRepairPolicyInsteadOfSilentlyDisablingRepair() {
        assertThatThrownBy(() -> DurableStateProjectionReconciler.RepairMode.parse("best-effort"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DurableStateProjectionReconciler.SweepResult result(
            DurableStateProjectionReconciler.ScanCursor cursor) {
        return new DurableStateProjectionReconciler.SweepResult(
                cursor, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
