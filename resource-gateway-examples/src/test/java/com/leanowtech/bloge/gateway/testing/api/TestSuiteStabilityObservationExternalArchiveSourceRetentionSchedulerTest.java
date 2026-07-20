package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationExternalArchiveSourceRetentionSchedulerTest {

    private static final Instant START = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void distinguishesCommittedWorkFromNormalCrossReplicaLeaseContention() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class);
        var completed = completed(3, false);
        var busy = new
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .RetentionAttempt(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetentionStatus.LEASE_BUSY,
                null);
        when(controlPlane.retain(Duration.ofDays(365), Duration.ofDays(30), 100))
                .thenReturn(completed, busy);
        MutableClock clock = new MutableClock(START);
        var scheduler = scheduler(controlPlane, clock);

        assertThat(scheduler.retain()).isEqualTo(completed);
        assertThat(scheduler.latest()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                            .TickStatus.COMPLETED);
            assertThat(result.lastSuccessfulAt()).isEqualTo(START);
            assertThat(result.consecutiveFailures()).isZero();
        });
        clock.advance(Duration.ofMinutes(1));
        assertThat(scheduler.retain()).isEqualTo(busy);
        assertThat(scheduler.latest()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                            .TickStatus.LEASE_BUSY);
            assertThat(result.lastSuccessfulAt()).isEqualTo(START);
            assertThat(result.consecutiveFailures()).isZero();
            assertThat(result.sequence()).isEqualTo(2);
        });
        verify(controlPlane, times(2)).retain(
                Duration.ofDays(365), Duration.ofDays(30), 100);
    }

    @Test
    void containsFailuresAndClearsTheFailureBudgetAfterACommittedRetry() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class);
        when(controlPlane.retain(Duration.ofDays(365), Duration.ofDays(30), 100))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(completed(0, true));
        MutableClock clock = new MutableClock(START);
        var scheduler = scheduler(controlPlane, clock);

        assertThat(scheduler.retain()).isNull();
        assertThat(scheduler.latest()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                            .TickStatus.FAILED);
            assertThat(result.consecutiveFailures()).isEqualTo(1);
            assertThat(result.lastSuccessfulAt()).isNull();
        });
        clock.advance(Duration.ofMinutes(1));
        assertThat(scheduler.retain()).isNotNull();
        assertThat(scheduler.latest()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                            .TickStatus.COMPLETED);
            assertThat(result.consecutiveFailures()).isZero();
            assertThat(result.lastSuccessfulAt()).isEqualTo(clock.instant());
        });
    }

    @Test
    void rejectsProcessLocalOverlapWithoutIssuingASecondDatabaseMutation() throws Exception {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(controlPlane.retain(Duration.ofDays(365), Duration.ofDays(30), 100))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                    return completed(0, false);
                });
        var scheduler = scheduler(controlPlane, new MutableClock(START));
        CompletableFuture<?> first = CompletableFuture.supplyAsync(scheduler::retain);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(scheduler.retain()).isNull();
        assertThat(scheduler.latest()).satisfies(result -> {
            assertThat(result.status()).isEqualTo(
                    TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                            .TickStatus.LOCAL_OVERLAP);
            assertThat(result.consecutiveFailures()).isEqualTo(1);
        });

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        verify(controlPlane).retain(Duration.ofDays(365), Duration.ofDays(30), 100);
    }

    @Test
    void rejectsUnboundedWindowsPagesAndInvalidTickShapes() {
        var controlPlane = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class);

        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
                controlPlane, Duration.ofHours(23), Duration.ofDays(30), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processedRetention");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
                controlPlane, Duration.ofDays(365), Duration.ofDays(3651), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiredRetention");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
                controlPlane, Duration.ofDays(365), Duration.ofDays(30), 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 through 500");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickResult(
                1,
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickStatus
                        .COMPLETED,
                START, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler scheduler(
            DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    controlPlane,
            Clock clock) {
        return new TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler(
                controlPlane, Duration.ofDays(365), Duration.ofDays(30), 100, clock);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            .RetentionAttempt completed(int pages, boolean retired) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .RetentionAttempt(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetentionStatus.COMPLETED,
                new DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .RetentionResult(
                        DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                                .RetirementMode.PROCESSED,
                        0, 0, 0, pages, retired, retired ? "" : "active-cycle", START));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
