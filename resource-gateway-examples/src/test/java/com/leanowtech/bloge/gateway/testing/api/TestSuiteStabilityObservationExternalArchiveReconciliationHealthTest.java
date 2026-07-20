package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityObservationExternalArchiveReconciliationHealthTest {

    private static final Instant START = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void reportsHealthyOnlyWhenSchedulerStagesEvidenceAndRetentionAreFresh() {
        MutableClock clock = new MutableClock(START);
        Fixtures fixtures = healthyFixtures(clock);
        when(fixtures.retention().operationalSnapshot(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365)))
                .thenReturn(retention(clock.instant(), 7, 0, 0, 0,
                        clock.instant().minus(Duration.ofMinutes(30))));
        var health = fixtures.health(clock, policy());

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails())
                .containsEntry("state", "HEALTHY")
                .containsEntry("configuredAuthorityCount", 2)
                .containsEntry("openFindings", 7L)
                .containsEntry("sourceRetentionState", "HEALTHY")
                .containsEntry("processedSourceBacklog", 0L)
                .containsEntry("authoritiesWithoutCompletedEvidence", 0)
                .doesNotContainKeys("authorityId", "objectId", "fingerprint");
        assertThat(result.toString()).doesNotContain("archive-a", "archive-b");
        assertThat(health.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.configured()).isTrue();
            assertThat(descriptor.ready()).isTrue();
            assertThat(descriptor.state()).isEqualTo("HEALTHY");
            assertThat(descriptor.authorityCount()).isEqualTo(2);
            assertThat(descriptor.sourceRetention().configured()).isTrue();
            assertThat(descriptor.sourceRetention().ready()).isTrue();
        });
    }

    @Test
    void staysInitializingDuringBoundedFirstPassGrace() {
        MutableClock clock = new MutableClock(START);
        Fixtures fixtures = uninitializedFixtures(clock);
        var health = fixtures.health(clock, policy());

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(result.getDetails())
                .containsEntry("state", "INITIALIZING")
                .containsEntry("violations", List.of());
        assertThat(health.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.configured()).isTrue();
            assertThat(descriptor.ready()).isFalse();
            assertThat(descriptor.state()).isEqualTo("INITIALIZING");
        });
    }

    @Test
    void failsClosedWhenFirstSchedulerEvidenceAndRetentionSuccessNeverArrive() {
        MutableClock clock = new MutableClock(START);
        Fixtures fixtures = uninitializedFixtures(clock);
        var health = fixtures.health(clock, policy());
        clock.advance(Duration.ofHours(3));

        health.refresh();

        assertThat(health.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(health)).containsExactly(
                "SCHEDULER_NEVER_SUCCEEDED",
                "COMPLETED_EVIDENCE_NEVER_PRODUCED",
                "RETENTION_NEVER_SUCCEEDED",
                "SOURCE_RETENTION_NEVER_SUCCEEDED");
    }

    @Test
    void reportsEveryIndependentStallFreshnessAndBacklogRootCause() {
        MutableClock clock = new MutableClock(START.plus(Duration.ofHours(3)));
        var service = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.class);
        var scheduler = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class);
        var inventories = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .class);
        var comparisons = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .class);
        var findings = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.class);
        var retention = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class);
        var sourceScheduler = mock(
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class);
        var sourceRetention = mock(
                DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class);
        when(service.authorities()).thenReturn(List.of("archive-a"));
        Instant now = clock.instant();
        when(scheduler.latest()).thenReturn(tick(now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(2)), 3));
        when(inventories.operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .OperationalSnapshot(now, false, true, true, 2, 20,
                        now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(2)),
                        now.minus(Duration.ofDays(2))));
        when(comparisons.operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .OperationalSnapshot(now, true, true, 2, 20, 3,
                        now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(2)),
                        now.minus(Duration.ofDays(2))));
        when(findings.operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .OperationalSnapshot(now, true, true, 2, 20, 3,
                        now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(2)),
                        now.minus(Duration.ofDays(2))));
        when(retention.operationalSnapshot(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365)))
                .thenReturn(retention(now, 11, 2, 3, 4,
                        now.minus(Duration.ofHours(3))));
        when(sourceScheduler.latest()).thenReturn(sourceTick(
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickStatus
                        .FAILED,
                now.minus(Duration.ofHours(2)), null, 3));
        when(sourceRetention.operationalSnapshot(
                Duration.ofDays(365), Duration.ofDays(30)))
                .thenReturn(sourceRetention(now, true,
                        now.minus(Duration.ofHours(4)), 5, 6,
                        now.minus(Duration.ofHours(3))));
        var health = new TestSuiteStabilityObservationExternalArchiveReconciliationHealth(
                service, scheduler, inventories, comparisons, findings, retention,
                sourceScheduler, sourceRetention,
                policy(), clock);
        clock.advance(Duration.ofHours(3));

        health.refresh();

        assertThat(health.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(violations(health)).containsExactly(
                "SCHEDULER_FAILURE_BUDGET_EXCEEDED",
                "SCHEDULER_STALE",
                "INVENTORY_STAGE_STALLED",
                "COMPARISON_STAGE_STALLED",
                "FINDING_STAGE_STALLED",
                "COMPLETED_EVIDENCE_STALE",
                "RETENTION_STALE",
                "RESOLVED_FINDING_BACKLOG_EXCEEDED",
                "FINDING_ARCHIVE_BACKLOG_EXCEEDED",
                "FINDING_EVIDENCE_BACKLOG_EXCEEDED",
                "SOURCE_RETENTION_SCHEDULER_FAILURE_BUDGET_EXCEEDED",
                "SOURCE_RETENTION_STALE",
                "SOURCE_RETIREMENT_STALLED",
                "PROCESSED_SOURCE_BACKLOG_EXCEEDED",
                "EXPIRED_SOURCE_BACKLOG_EXCEEDED");
        assertThat(health.health().getDetails())
                .containsEntry("staleStages", 3)
                .containsEntry("openFindings", 11L)
                .containsEntry("sourceRetentionState", "SLO_VIOLATED")
                .containsEntry("activeSourceRetirements", 1L)
                .containsEntry("processedSourceBacklog", 5L)
                .containsEntry("expiredSourceBacklog", 6L);
        assertThat(health.descriptor().sourceRetention()).satisfies(source -> {
            assertThat(source.ready()).isFalse();
            assertThat(source.state()).isEqualTo("SLO_VIOLATED");
            assertThat(source.violations()).containsExactly(
                    "SOURCE_RETENTION_SCHEDULER_FAILURE_BUDGET_EXCEEDED",
                    "SOURCE_RETENTION_STALE", "SOURCE_RETIREMENT_STALLED",
                    "PROCESSED_SOURCE_BACKLOG_EXCEEDED",
                    "EXPIRED_SOURCE_BACKLOG_EXCEEDED");
        });
    }

    @Test
    void durableSnapshotFailureMakesHealthDownWithoutLeakingExceptionOrIdentity() {
        MutableClock clock = new MutableClock(START);
        Fixtures fixtures = healthyFixtures(clock);
        when(fixtures.comparisons().operationalSnapshot("archive-a"))
                .thenThrow(new IllegalStateException("archive-a secret-fingerprint"));
        var health = fixtures.health(clock, policy());

        var result = health.health();

        assertThat(result.getStatus()).isEqualTo(Status.DOWN);
        assertThat(result.getDetails()).containsEntry("state", "STORE_UNAVAILABLE")
                .containsEntry("violations",
                        List.of("RECONCILIATION_STORE_UNAVAILABLE"));
        assertThat(result.toString()).doesNotContain("archive-a", "secret-fingerprint");
        assertThat(health.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.ready()).isFalse();
            assertThat(descriptor.sourceRetention().configured()).isTrue();
            assertThat(descriptor.sourceRetention().ready()).isFalse();
            assertThat(descriptor.sourceRetention().state()).isEqualTo("STORE_UNAVAILABLE");
        });
    }

    @Test
    void rejectsScheduleBlindOrUnboundedHealthPoliciesAndInvalidDescriptors() {
        var valid = policy();
        assertThat(valid.maximumSchedulerStaleness()).isEqualTo(Duration.ofMinutes(15));

        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Policy(
                Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(15), 2, Duration.ofMinutes(30), Duration.ofDays(1),
                Duration.ofHours(1), Duration.ofHours(2), Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(365), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startupGrace");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Policy(
                Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofMinutes(5),
                Duration.ofMinutes(4), 2, Duration.ofMinutes(30), Duration.ofDays(1),
                Duration.ofHours(1), Duration.ofHours(2), Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(365), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumSchedulerStaleness");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                "", false, true, "HEALTHY", List.of(), START, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                "", true, false, "SLO_VIOLATED", List.of("free-form-error"), START, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown");
        assertThatThrownBy(() -> new
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                "bloge.unknown.v1", true, true, "HEALTHY", List.of(), START, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @SuppressWarnings("unchecked")
    private static List<String> violations(
            TestSuiteStabilityObservationExternalArchiveReconciliationHealth health) {
        return (List<String>) health.health().getDetails().get("violations");
    }

    private static Fixtures healthyFixtures(MutableClock clock) {
        Fixtures fixtures = fixtures();
        Instant now = clock.instant();
        when(fixtures.scheduler().latest()).thenReturn(
                tick(now.minus(Duration.ofMinutes(1)),
                        now.minus(Duration.ofMinutes(1)), 0));
        when(fixtures.inventories().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .OperationalSnapshot(now, false, false, true, 0, 0,
                        null, null, now.minus(Duration.ofHours(1))));
        when(fixtures.comparisons().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .OperationalSnapshot(now, true, false, 0, 0, 0,
                        null, null, now.minus(Duration.ofHours(1))));
        when(fixtures.findings().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .OperationalSnapshot(now, true, false, 0, 0, 0,
                        null, null, now.minus(Duration.ofHours(1))));
        when(fixtures.sourceScheduler().latest()).thenReturn(sourceTick(
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickStatus
                        .COMPLETED,
                now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(1)), 0));
        when(fixtures.sourceRetention().operationalSnapshot(
                Duration.ofDays(365), Duration.ofDays(30)))
                .thenReturn(sourceRetention(now, false, null, 0, 0,
                        now.minus(Duration.ofMinutes(30))));
        return fixtures;
    }

    private static Fixtures uninitializedFixtures(MutableClock clock) {
        Fixtures fixtures = fixtures();
        Instant now = clock.instant();
        when(fixtures.scheduler().latest()).thenReturn(new
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickResult(
                0,
                TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                        .NOT_RUN,
                0, 0, 0, Map.of(), Duration.ZERO, null, null, 0));
        when(fixtures.inventories().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .OperationalSnapshot(now, false, false, false, 0, 0,
                        null, null, null));
        when(fixtures.comparisons().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .OperationalSnapshot(now, false, false, 0, 0, 0,
                        null, null, null));
        when(fixtures.findings().operationalSnapshot(anyString())).thenReturn(
                new DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane
                        .OperationalSnapshot(now, false, false, 0, 0, 0,
                        null, null, null));
        when(fixtures.retention().operationalSnapshot(
                Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(365)))
                .thenReturn(retention(now, 0, 0, 0, 0, null));
        when(fixtures.sourceScheduler().latest()).thenReturn(sourceTick(
                TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickStatus
                        .NOT_RUN,
                null, null, 0));
        when(fixtures.sourceRetention().operationalSnapshot(
                Duration.ofDays(365), Duration.ofDays(30)))
                .thenReturn(sourceRetention(now, false, null, 0, 0, null));
        return fixtures;
    }

    private static Fixtures fixtures() {
        var service = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationService.class);
        when(service.authorities()).thenReturn(List.of("archive-a", "archive-b"));
        return new Fixtures(service,
                mock(TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.class),
                mock(DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .class),
                mock(DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                        .class),
                mock(DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane.class),
                mock(DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                        .class),
                mock(TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.class),
                mock(DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                        .class));
    }

    private static TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickResult
            tick(Instant attemptedAt, Instant successfulAt, long unhealthy) {
        var status = unhealthy == 0
                ? TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                .COMPLETED
                : TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickStatus
                .DEGRADED;
        return new TestSuiteStabilityObservationExternalArchiveReconciliationScheduler.TickResult(
                Math.max(1, unhealthy + 1), status, 2,
                unhealthy == 0 ? 2 : 1, unhealthy == 0 ? 0 : 1,
                unhealthy == 0
                        ? Map.of(TestSuiteStabilityObservationExternalArchiveReconciliationService
                        .Stage.INVENTORY_COMPLETED, 2)
                        : Map.of(TestSuiteStabilityObservationExternalArchiveReconciliationService
                        .Stage.INVENTORY_COMPLETED, 1),
                Duration.ofMillis(5), attemptedAt, successfulAt, unhealthy);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
            .OperationalSnapshot retention(
            Instant observedAt,
            long open,
            long overdueResolved,
            long overdueArchives,
            long overdueEvidence,
            Instant lastSuccessAt) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                .OperationalSnapshot(observedAt, false, false,
                0, 0, 0, 0, 0, 0, open, overdueResolved, overdueArchives,
                overdueEvidence, lastSuccessAt);
    }

    private static TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickResult
            sourceTick(
            TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickStatus status,
            Instant attemptedAt,
            Instant lastSuccessfulAt,
            long failures) {
        return new TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler.TickResult(
                status == TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler
                        .TickStatus.NOT_RUN ? 0 : Math.max(1, failures),
                status, attemptedAt, lastSuccessfulAt, failures);
    }

    private static DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
            .OperationalSnapshot sourceRetention(
            Instant observedAt,
            boolean active,
            Instant activeUpdatedAt,
            long processedBacklog,
            long expiredBacklog,
            Instant lastSuccessAt) {
        return new DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                .OperationalSnapshot(observedAt, active, active ? 1 : 0, activeUpdatedAt,
                processedBacklog, expiredBacklog, 0, 0, 0, 0, 0, lastSuccessAt);
    }

    private static TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Policy policy() {
        return new TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Policy(
                Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofMinutes(5),
                Duration.ofMinutes(15), 2, Duration.ofMinutes(30), Duration.ofDays(1),
                Duration.ofHours(1), Duration.ofHours(2), Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(365), 0, 0, 0);
    }

    private record Fixtures(
            TestSuiteStabilityObservationExternalArchiveReconciliationService service,
            TestSuiteStabilityObservationExternalArchiveReconciliationScheduler scheduler,
            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                    inventories,
            DatabaseTestSuiteStabilityObservationExternalArchiveClassificationControlPlane
                    comparisons,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingControlPlane findings,
            DatabaseTestSuiteStabilityObservationExternalArchiveFindingRetentionControlPlane
                    retention,
            TestSuiteStabilityObservationExternalArchiveSourceRetentionScheduler sourceScheduler,
            DatabaseTestSuiteStabilityObservationExternalArchiveSourceRetentionControlPlane
                    sourceRetention) {
        private TestSuiteStabilityObservationExternalArchiveReconciliationHealth health(
                Clock clock,
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Policy policy) {
            return new TestSuiteStabilityObservationExternalArchiveReconciliationHealth(
                    service, scheduler, inventories, comparisons, findings, retention,
                    sourceScheduler, sourceRetention, policy, clock);
        }
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
