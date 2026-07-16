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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestRuntimeAdmissionControlTest {

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

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acquireAfterBarrier(control,
                    request("replica-a", "intent-a", 1, 1, 10, "operator-a"), ready, start));
            var second = executor.submit(() -> acquireAfterBarrier(competing,
                    request("replica-b", "intent-b", 1, 1, 10, "operator-b"), ready, start));
            ready.await();
            start.countDown();

            List<AcquireResult> results = List.of(first.get(), second.get());
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

    private static AcquireResult acquireAfterBarrier(
            DatabaseTestRuntimeAdmissionControl authority,
            AdmissionRequest request,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return authority.acquire(request);
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
