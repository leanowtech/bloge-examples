package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteRunLeaseCoordinatorTest {

    @Test
    void heartbeatLossStopsNewSchedulingAndUsesOnlyScopedPayloadFreeIdentity() throws Exception {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CountDownLatch heartbeat = new CountDownLatch(1);
        CapturingRepository repository = new CapturingRepository(heartbeat, false);
        try (TestSuiteRunLeaseCoordinator coordinator = new TestSuiteRunLeaseCoordinator(
                repository, "instance-a", Duration.ofSeconds(2), Duration.ofMillis(10),
                Clock.fixed(now, ZoneOffset.UTC), true)) {
            TestSuiteRunRecord record = record(now);
            TestSuiteRunLease initial = coordinator.newLease();
            assertThat(initial.ownerId()).isEqualTo("instance-a");
            assertThat(initial.expiresAt()).isEqualTo(now.plusSeconds(2));

            try (TestSuiteRunLeaseCoordinator.LeaseGuard guard = coordinator.monitor(record)) {
                assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitLeaseLoss(guard)).isTrue();
                assertThat(repository.observedScope)
                        .containsExactly("tenant-a", "test", "suite-run-1", "instance-a");
            }
        }
    }

    @Test
    void databaseClockFailureAlsoMarksOwnershipUncertain() throws Exception {
        Instant now = Instant.parse("2026-07-16T12:00:00Z");
        CountDownLatch heartbeat = new CountDownLatch(1);
        CapturingRepository repository = new CapturingRepository(heartbeat, true);
        try (TestSuiteRunLeaseCoordinator coordinator = new TestSuiteRunLeaseCoordinator(
                repository, "instance-a", Duration.ofSeconds(2), Duration.ofMillis(10),
                null, true);
             TestSuiteRunLeaseCoordinator.LeaseGuard guard = coordinator.monitor(record(now))) {
            assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitLeaseLoss(guard)).isTrue();
            assertThat(repository.observedScope).isEmpty();
        }
    }

    private static boolean awaitLeaseLoss(TestSuiteRunLeaseCoordinator.LeaseGuard guard)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (guard.held() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return !guard.held();
    }

    private static TestSuiteRunRecord record(Instant now) {
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.RUNNING, "TEST_SUITE_EXECUTION", null, null, now, null,
                List.of(), TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of());
        return new TestSuiteRunRecord("suite-run-1", "request-1", "sha256:" + "a".repeat(64),
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, now, now.plusSeconds(3600));
    }

    private static final class CapturingRepository implements TestSuiteRunRepository {
        private final CountDownLatch heartbeat;
        private final boolean failClock;
        private List<String> observedScope = List.of();

        private CapturingRepository(CountDownLatch heartbeat, boolean failClock) {
            this.heartbeat = heartbeat;
            this.failClock = failClock;
        }

        @Override
        public Instant currentTime() {
            if (failClock) {
                heartbeat.countDown();
                throw new IllegalStateException("database clock unavailable");
            }
            return Instant.now();
        }

        @Override
        public boolean renewLease(String tenantId, String environmentId, String suiteRunId,
                                  String ownerId, Instant expiresAt, Instant observedAt) {
            observedScope = List.of(tenantId, environmentId, suiteRunId, ownerId);
            heartbeat.countDown();
            return false;
        }

        @Override
        public TestSuiteRunRecord create(TestSuiteRunRecord record, TestSuiteRunLease lease) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TestSuiteRunRecord update(TestSuiteRunRecord record, TestSuiteRunLease lease,
                                         Instant observedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AbandonedTestSuiteRun> findAbandoned(Instant observedAt, int limit) {
            return List.of();
        }

        @Override
        public boolean reconcileAbandoned(AbandonedTestSuiteRun abandoned,
                                          TestSuiteRunRecord terminal, Instant observedAt) {
            return false;
        }

        @Override
        public Optional<TestSuiteRunRecord> find(String tenantId, String environmentId,
                                                 String suiteRunId) {
            return Optional.empty();
        }

        @Override
        public Optional<TestSuiteRunRecord> findByClientRequestId(
                String tenantId, String environmentId, String clientRequestId) {
            return Optional.empty();
        }
    }
}
