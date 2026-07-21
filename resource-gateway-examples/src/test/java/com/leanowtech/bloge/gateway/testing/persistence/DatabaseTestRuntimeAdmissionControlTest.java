package com.leanowtech.bloge.gateway.testing.persistence;

import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionPolicy.Dimension;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AcquireResult;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AcquireState;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AdmissionConflictException;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.AdmissionRequest;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.ConflictReason;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestRuntimeAdmissionControl.QuotaSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestRuntimeAdmissionControlTest {

    private static final long CONCURRENCY_TIMEOUT_SECONDS = 5L;
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DatabaseTestRuntimeAdmissionControl control;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(dataSource);
        control = newControl();
    }

    @Test
    void acquiresEveryDimensionAtomicallyAndRecoversCapacityOnExactRelease() {
        AdmissionRequest firstRequest = request("first", "intent-first", 1, 2, 1, "operator-a");
        AcquireResult first = control.acquire(firstRequest);

        assertThat(first.state()).isEqualTo(AcquireState.ACQUIRED);
        assertThat(control.acquire(firstRequest).state()).isEqualTo(AcquireState.ALREADY_ACTIVE);

        AcquireResult rejected = control.acquire(
                request("second", "intent-second", 1, 2, 1, "operator-a"));
        assertThat(rejected.state()).isEqualTo(AcquireState.REJECTED);
        assertThat(rejected.rejection().dimension()).isEqualTo(Dimension.OPERATOR);
        assertThat(rejected.retryAfterSeconds()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_leases", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_test_admission_claims", Long.class)).isEqualTo(2);

        String storedTokenFingerprint = jdbc.queryForObject(
                "SELECT token_fingerprint FROM rg_test_admission_leases", String.class);
        assertThat(storedTokenFingerprint).startsWith("sha256:")
                .isNotEqualTo(first.lease().token());

        assertThat(control.release(first.lease())).isTrue();
        AcquireResult admittedAfterRelease = control.acquire(
                request("second", "intent-second", 1, 2, 1, "operator-a"));
        assertThat(admittedAfterRelease.state()).isEqualTo(AcquireState.ACQUIRED);
        assertThat(control.release(first.lease())).isFalse();
        assertThat(control.release(admittedAfterRelease.lease())).isTrue();
    }

    @Test
    void competingReplicasCannotBothConsumeTheLastTenantPermit() throws Exception {
        DatabaseTestRuntimeAdmissionControl competing = newControl();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = concurrencyExecutor();
        try {
            var first = executor.submit(() -> acquireAfterBarrier(control,
                    request("replica-a", "intent-a", 1, 1, 10, "operator-a"), ready, start));
            var second = executor.submit(() -> acquireAfterBarrier(competing,
                    request("replica-b", "intent-b", 1, 1, 10, "operator-b"), ready, start));
            assertThat(ready.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("both admission competitors reached the start barrier")
                    .isTrue();
            start.countDown();

            List<AcquireResult> results = List.of(await(first), await(second));
            assertThat(results).extracting(AcquireResult::state)
                    .containsExactlyInAnyOrder(AcquireState.ACQUIRED, AcquireState.REJECTED);
            AcquireResult rejected = results.stream()
                    .filter(result -> result.state() == AcquireState.REJECTED).findFirst().orElseThrow();
            assertThat(rejected.rejection().dimension()).isEqualTo(Dimension.TENANT);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rg_test_admission_leases", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rg_test_admission_claims", Long.class)).isEqualTo(2);
            AcquireResult acquired = results.stream()
                    .filter(result -> result.state() == AcquireState.ACQUIRED).findFirst().orElseThrow();
            assertThat(control.release(acquired.lease())).isTrue();
        } finally {
            shutdown(executor, start);
        }
    }

    @Test
    void expiredPermitCanBeFencedAndReacquiredWithoutAcceptingTheOldToken() {
        AdmissionRequest request = request("takeover", "same-intent", 1, 1, 1, "operator-a");
        AcquireResult first = control.acquire(request);
        jdbc.update("""
                UPDATE rg_test_admission_leases
                SET lease_expires_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP)
                WHERE admission_id = ?
                """, request.admissionId());

        AcquireResult replacement = control.acquire(request);

        assertThat(replacement.state()).isEqualTo(AcquireState.ACQUIRED);
        assertThat(replacement.lease().leaseEpoch()).isEqualTo(first.lease().leaseEpoch() + 1);
        assertThat(replacement.lease().token()).isNotEqualTo(first.lease().token());
        assertThat(control.release(first.lease())).isFalse();
        assertThat(control.renew(first.lease(), Duration.ofSeconds(30))).isEmpty();
        assertThat(control.release(replacement.lease())).isTrue();
    }

    @Test
    void staleReleaseCannotRemoveClaimsFromAConcurrentReplacement() throws Exception {
        DatabaseTestRuntimeAdmissionControl competing = newControl();
        for (int attempt = 0; attempt < 20; attempt++) {
            AdmissionRequest request = request("release-race-" + attempt, "same-intent",
                    1, 1, 1, "operator-a");
            AcquireResult original = control.acquire(request);
            expire(request);
            CountDownLatch start = new CountDownLatch(1);

            ExecutorService executor = concurrencyExecutor();
            try {
                var staleRelease = executor.submit(() -> {
                    awaitStart(start);
                    return control.release(original.lease());
                });
                var reacquire = executor.submit(() -> {
                    awaitStart(start);
                    return competing.acquire(request);
                });
                start.countDown();

                await(staleRelease);
                AcquireResult replacement = await(reacquire);
                assertThat(replacement.state()).isEqualTo(AcquireState.ACQUIRED);
                assertLivePermitHasEveryClaim(request);
                assertThat(control.release(replacement.lease())).isTrue();
            } finally {
                shutdown(executor, start);
            }
        }
    }

    @Test
    void retentionCannotRemoveClaimsFromAConcurrentReplacement() throws Exception {
        DatabaseTestRuntimeAdmissionControl competing = newControl();
        for (int attempt = 0; attempt < 20; attempt++) {
            AdmissionRequest request = request("purge-race-" + attempt, "same-intent",
                    1, 1, 1, "operator-a");
            control.acquire(request);
            expire(request);
            CountDownLatch start = new CountDownLatch(1);

            ExecutorService executor = concurrencyExecutor();
            try {
                var purge = executor.submit(() -> {
                    awaitStart(start);
                    return control.purgeExpired(100);
                });
                var reacquire = executor.submit(() -> {
                    awaitStart(start);
                    return competing.acquire(request);
                });
                start.countDown();

                await(purge);
                AcquireResult replacement = await(reacquire);
                assertThat(replacement.state()).isEqualTo(AcquireState.ACQUIRED);
                assertLivePermitHasEveryClaim(request);
                assertThat(control.release(replacement.lease())).isTrue();
            } finally {
                shutdown(executor, start);
            }
        }
    }

    @Test
    void requestLocksUseABoundedStableStripeSpace() {
        var stripes = IntStream.range(0, 20_000)
                .map(index -> DatabaseTestRuntimeAdmissionControl.admissionLockStripe(
                        fingerprint("admission:" + index)))
                .boxed().collect(java.util.stream.Collectors.toSet());

        assertThat(stripes).hasSizeLessThanOrEqualTo(4_096).hasSizeGreaterThan(4_000)
                .allMatch(stripe -> stripe >= 0 && stripe < 4_096);
        assertThat(DatabaseTestRuntimeAdmissionControl.admissionLockStripe(
                fingerprint("admission:stable")))
                .isEqualTo(DatabaseTestRuntimeAdmissionControl.admissionLockStripe(
                        fingerprint("admission:stable")));
    }

    @Test
    void concurrencyWaitsFailWithinTheirCallerOwnedDeadline() throws Exception {
        assertThatThrownBy(() -> awaitStart(
                new CountDownLatch(1), 10L, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        ExecutorService executor = concurrencyExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        try {
            Future<Boolean> blocked = executor.submit(() -> {
                taskStarted.countDown();
                new CountDownLatch(1).await();
                return true;
            });
            assertThat(taskStarted.await(1L, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> await(blocked, 10L, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
        } finally {
            shutdown(executor, new CountDownLatch(0));
        }
    }

    @Test
    void policyGenerationAndRequestIdentityDriftFailClosed() {
        AcquireResult generationTwo = control.acquire(
                request("generation-two", "intent-two", 2, 2, 2, "operator-a"));
        assertThat(control.release(generationTwo.lease())).isTrue();

        assertThatThrownBy(() -> control.acquire(
                request("stale", "intent-stale", 1, 2, 2, "operator-a")))
                .isInstanceOfSatisfying(AdmissionConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ConflictReason.STALE_POLICY));
        assertThatThrownBy(() -> control.acquire(
                request("changed-limit", "intent-changed", 2, 2, 3, "operator-a")))
                .isInstanceOfSatisfying(AdmissionConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ConflictReason.POLICY_DRIFT));

        AdmissionRequest stable = request("identity", "original", 2, 2, 2, "operator-b");
        AcquireResult active = control.acquire(stable);
        AdmissionRequest conflicting = new AdmissionRequest(stable.admissionId(), fingerprint("different"),
                stable.policyFingerprint(), stable.policyGeneration(), stable.ownerId(),
                stable.leaseDuration(), stable.subjects());
        assertThatThrownBy(() -> control.acquire(conflicting))
                .isInstanceOfSatisfying(AdmissionConflictException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ConflictReason.IDENTITY_CONFLICT));
        assertThat(control.release(active.lease())).isTrue();
    }

    private DatabaseTestRuntimeAdmissionControl newControl() {
        return new DatabaseTestRuntimeAdmissionControl(jdbc,
                new DataSourceTransactionManager(dataSource));
    }

    private void expire(AdmissionRequest request) {
        jdbc.update("""
                UPDATE rg_test_admission_leases
                SET lease_expires_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP)
                WHERE admission_id = ?
                """, request.admissionId());
    }

    private void assertLivePermitHasEveryClaim(AdmissionRequest request) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_admission_leases
                WHERE admission_id = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, Long.class, request.admissionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_admission_claims
                WHERE admission_id = ?
                """, Long.class, request.admissionId())).isEqualTo(request.subjects().size());
    }

    private static AcquireResult acquireAfterBarrier(
            DatabaseTestRuntimeAdmissionControl authority,
            AdmissionRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException, TimeoutException {
        ready.countDown();
        awaitStart(start);
        return authority.acquire(request);
    }

    private static ExecutorService concurrencyExecutor() {
        return Executors.newFixedThreadPool(2, task -> Thread.ofPlatform()
                .daemon(true)
                .name("resource-gateway-admission-race-" + THREAD_SEQUENCE.incrementAndGet())
                .unstarted(task));
    }

    private static void awaitStart(CountDownLatch start)
            throws InterruptedException, TimeoutException {
        awaitStart(start, CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitStart(CountDownLatch start, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        if (!start.await(timeout, unit)) {
            throw new TimeoutException("Admission race start barrier timed out");
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return await(future, CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static <T> T await(Future<T> future, long timeout, TimeUnit unit)
            throws Exception {
        return future.get(timeout, unit);
    }

    private static void shutdown(ExecutorService executor, CountDownLatch start)
            throws InterruptedException {
        start.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("admission race workers terminated after cancellation")
                .isTrue();
    }

    private static AdmissionRequest request(
            String key,
            String intent,
            long generation,
            long tenantLimit,
            long operatorLimit,
            String operator) {
        return new AdmissionRequest(fingerprint("admission:" + key), fingerprint("intent:" + intent),
                fingerprint("policy:" + generation + ":" + tenantLimit + ":" + operatorLimit),
                generation, "owner-a", Duration.ofSeconds(30), List.of(
                new QuotaSubject(Dimension.TENANT, fingerprint("tenant:test:tenant-a"), tenantLimit),
                new QuotaSubject(Dimension.OPERATOR,
                        fingerprint("operator:test:tenant-a:" + operator), operatorLimit)));
    }

    private static String fingerprint(String value) {
        return ProtocolFingerprint.ofText(value);
    }
}
