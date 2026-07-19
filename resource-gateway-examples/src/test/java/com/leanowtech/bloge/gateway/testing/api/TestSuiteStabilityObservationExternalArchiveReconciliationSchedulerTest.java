package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationExternalArchiveReconciliationSchedulerTest {

    @Test
    void visitsEveryBoundedAuthorityInOrderAndIsolatesOneFailure() {
        var service = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.class);
        when(service.authorities()).thenReturn(List.of("archive-a", "archive-b", "archive-c"));
        when(service.advance("archive-a")).thenReturn(attempt(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .INVENTORY_STAGED));
        when(service.advance("archive-b")).thenThrow(new IllegalStateException("unavailable"));
        when(service.advance("archive-c")).thenReturn(attempt(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .FINDING_COMPLETED));
        Instant attemptedAt = Instant.parse("2026-07-20T00:00:00Z");
        var scheduler = new
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(
                service, Clock.fixed(attemptedAt, ZoneOffset.UTC));

        var result = scheduler.reconcile();

        assertThat(result.status()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                        .DEGRADED);
        assertThat(result.configuredAuthorities()).isEqualTo(3);
        assertThat(result.succeededAuthorities()).isEqualTo(2);
        assertThat(result.failedAuthorities()).isEqualTo(1);
        assertThat(result.stageCounts()).containsEntry(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .INVENTORY_STAGED, 1);
        assertThat(result.stageCounts()).containsEntry(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .FINDING_COMPLETED, 1);
        assertThat(result.attemptedAt()).isEqualTo(attemptedAt);
        assertThat(result.lastSuccessfulAt()).isNull();
        assertThat(result.consecutiveUnhealthyTicks()).isEqualTo(1);
        assertThat(scheduler.latest()).isEqualTo(result);
        var order = inOrder(service);
        order.verify(service).advance("archive-a");
        order.verify(service).advance("archive-b");
        order.verify(service).advance("archive-c");
    }

    @Test
    void containsMembershipFailureAndCanRecoverOnNextTick() {
        var service = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.class);
        when(service.authorities())
                .thenThrow(new IllegalStateException("membership unavailable"))
                .thenReturn(List.of("archive-a"));
        when(service.advance("archive-a")).thenReturn(attempt(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                        .COMPARISON_STAGED));
        Instant attemptedAt = Instant.parse("2026-07-20T00:01:00Z");
        var scheduler = new
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(
                service, Clock.fixed(attemptedAt, ZoneOffset.UTC));

        var failed = scheduler.reconcile();
        assertThat(failed.status()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                        .FAILED);
        assertThat(failed.consecutiveUnhealthyTicks()).isEqualTo(1);
        assertThat(failed.lastSuccessfulAt()).isNull();
        var recovered = scheduler.reconcile();
        assertThat(recovered.status()).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                        .COMPLETED);
        assertThat(recovered.lastSuccessfulAt()).isEqualTo(attemptedAt);
        assertThat(recovered.consecutiveUnhealthyTicks()).isZero();
        assertThat(scheduler.latest().sequence()).isEqualTo(2);
    }

    @Test
    void rejectsLocalOverlapWithoutStartingASecondAuthorityPass() throws Exception {
        var service = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.authorities()).thenReturn(List.of("archive-a"));
        when(service.advance("archive-a")).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return attempt(TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage
                    .INVENTORY_COMPLETED);
        });
        var scheduler = new
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler(service);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(scheduler::reconcile);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(scheduler.reconcile().status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                            .LOCAL_OVERLAP);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                            .COMPLETED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static TestSuiteStabilityObservationExternalArchiveReconciliationService
            .AuthorityAttempt attempt(
                    TestSuiteStabilityObservationExternalArchiveReconciliationService.Stage stage) {
        return new TestSuiteStabilityObservationExternalArchiveReconciliationService
                .AuthorityAttempt(stage, 1, 1, 0);
    }
}
