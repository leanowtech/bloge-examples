package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReadOnlyShadowExecutionGuardTest {
    private static final Instant NOW =
            ReadOnlyShadowJobTestFixtures.NOW;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(NOW);
    private final AtomicInteger tokens =
            new AtomicInteger();

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DatabaseReadOnlyShadowExecutionGuard first;
    private DatabaseReadOnlyShadowExecutionGuard second;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        first = guard();
        second = guard();
        first.init();
        second.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void serializesConcurrentReplicaAcquisitionAgainstOneSharedLimit()
            throws Exception {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(1, 10, 3);
        ReadOnlyShadowJobRequest left =
                request("guard-concurrent-left", 31);
        ReadOnlyShadowJobRequest right =
                request("guard-concurrent-right", 32);
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Callable<Object> leftAcquire = () ->
                    outcome(() -> first.acquire(
                            permit("guard-left", left),
                            admission(left, limits, 1, '0')));
            Callable<Object> rightAcquire = () ->
                    outcome(() -> second.acquire(
                            permit("guard-right", right),
                            admission(right, limits, 1, '0')));
            Future<Object> leftResult =
                    executor.submit(leftAcquire);
            Future<Object> rightResult =
                    executor.submit(rightAcquire);

            List<Object> outcomes =
                    List.of(
                            leftResult.get(),
                            rightResult.get());
            assertThat(outcomes)
                    .filteredOn(
                            ReadOnlyShadowExecutionGuard
                                    .Lease.class::isInstance)
                    .hasSize(1);
            assertThat(outcomes)
                    .filteredOn(value -> value
                            == ReadOnlyShadowDataPlane
                            .FailureReason.BUDGET_EXHAUSTED)
                    .hasSize(1);
            outcomes.stream()
                    .filter(ReadOnlyShadowExecutionGuard
                            .Lease.class::isInstance)
                    .map(ReadOnlyShadowExecutionGuard
                            .Lease.class::cast)
                    .forEach(lease -> {
                        lease.succeeded();
                        lease.close();
                    });
        }
    }

    @Test
    void enforcesAndThenResetsTheSharedFixedStartWindow() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                new ReadOnlyShadowExecutionGuard.Limits(
                        2,
                        2,
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofSeconds(30));
        complete(
                first.acquire(
                        permit(
                                "guard-window-1",
                                request("guard-window-1", 33)),
                        admission(
                                request("guard-window-1", 33),
                                limits,
                                1,
                                '0')));
        complete(
                second.acquire(
                        permit(
                                "guard-window-2",
                                request("guard-window-2", 34)),
                        admission(
                                request("guard-window-2", 34),
                                limits,
                                1,
                                '0')));
        ReadOnlyShadowJobRequest third =
                request("guard-window-3", 35);

        assertFailure(
                () -> first.acquire(
                        permit("guard-window-3", third),
                        admission(
                                third,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BUDGET_EXHAUSTED);

        now.set(NOW.plusSeconds(60));
        complete(
                second.acquire(
                        permit("guard-window-3", third),
                        admission(
                                third,
                                limits,
                                1,
                                '0')));
    }

    @Test
    void reclaimsOneLogicalExecutionWithoutDoubleChargingAndFencesTheOldEpoch() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(1, 1, 3);
        ReadOnlyShadowJobRequest request =
                request("guard-retry", 36);
        ReadOnlyShadowExecutionGuard.Lease stale =
                first.acquire(
                        permit(
                                "guard-retry",
                                request,
                                NOW.plusSeconds(10)),
                        admission(
                                request,
                                limits,
                                1,
                                '0'));

        now.set(NOW.plusSeconds(11));
        ReadOnlyShadowExecutionGuard.Lease replacement =
                second.acquire(
                        permit(
                                "guard-retry",
                                request,
                                NOW.plusSeconds(30)),
                        admission(
                                request,
                                limits,
                                1,
                                '0'));
        assertFailure(
                stale::succeeded,
                ReadOnlyShadowDataPlane.FailureReason
                        .LEASE_LOST);
        complete(replacement);

        ReadOnlyShadowJobRequest other =
                request("guard-other", 37);
        assertFailure(
                () -> first.acquire(
                        permit("guard-other", other),
                        admission(
                                other,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BUDGET_EXHAUSTED);
    }

    @Test
    void opensAfterCountedFailuresAndAdmitsOnlyOneHalfOpenProbe() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(4, 20, 2);
        fail(
                first.acquire(
                        permit(
                                "guard-failure-1",
                                request("guard-failure-1", 38)),
                        admission(
                                request("guard-failure-1", 38),
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BASELINE_SOURCE_UNAVAILABLE);
        fail(
                second.acquire(
                        permit(
                                "guard-failure-2",
                                request("guard-failure-2", 39)),
                        admission(
                                request("guard-failure-2", 39),
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .CANDIDATE_RUNTIME_UNAVAILABLE);
        ReadOnlyShadowJobRequest probeRequest =
                request("guard-probe", 40);
        assertFailure(
                () -> first.acquire(
                        permit("guard-probe", probeRequest),
                        admission(
                                probeRequest,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .CIRCUIT_OPEN);

        now.set(NOW.plusSeconds(30));
        ReadOnlyShadowExecutionGuard.Lease probe =
                first.acquire(
                        permit("guard-probe", probeRequest),
                        admission(
                                probeRequest,
                                limits,
                                1,
                                '0'));
        ReadOnlyShadowJobRequest blocked =
                request("guard-second-probe", 41);
        assertFailure(
                () -> second.acquire(
                        permit(
                                "guard-second-probe",
                                blocked),
                        admission(
                                blocked,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .CIRCUIT_OPEN);

        complete(probe);
        complete(
                second.acquire(
                        permit(
                                "guard-second-probe",
                                blocked),
                        admission(
                                blocked,
                                limits,
                                1,
                                '0')));
    }

    @Test
    void olderInflightSuccessCannotCloseANewerOpenCircuit() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(2, 20, 1);
        ReadOnlyShadowJobRequest older =
                request("guard-older-success", 51);
        ReadOnlyShadowJobRequest failing =
                request("guard-newer-failure", 52);
        ReadOnlyShadowExecutionGuard.Lease olderLease =
                first.acquire(
                        permit(
                                "guard-older-success",
                                older),
                        admission(
                                older,
                                limits,
                                1,
                                '0'));
        ReadOnlyShadowExecutionGuard.Lease failingLease =
                second.acquire(
                        permit(
                                "guard-newer-failure",
                                failing),
                        admission(
                                failing,
                                limits,
                                1,
                                '0'));

        fail(
                failingLease,
                ReadOnlyShadowDataPlane.FailureReason
                        .BASELINE_SOURCE_UNAVAILABLE);
        complete(olderLease);

        ReadOnlyShadowJobRequest blocked =
                request("guard-after-stale-success", 53);
        assertFailure(
                () -> first.acquire(
                        permit(
                                "guard-after-stale-success",
                                blocked),
                        admission(
                                blocked,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .CIRCUIT_OPEN);
    }

    @Test
    void authorityCanShareOnePhysicalBudgetAcrossExecutionProjects() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(1, 20, 3);
        ReadOnlyShadowJobRequest firstProject =
                request("guard-project-a", 54);
        CapabilitySnapshot.Scope secondScope =
                new CapabilitySnapshot.Scope(
                        firstProject.scope().tenantId(),
                        firstProject.scope()
                                .organizationId(),
                        "second-project",
                        firstProject.scope()
                                .environmentId(),
                        firstProject.scope().region());
        ReadOnlyShadowJobRequest secondProject =
                withScope(
                        request("guard-project-b", 55),
                        secondScope);
        ReadOnlyShadowExecutionGuard.Lease admitted =
                first.acquire(
                        permit(
                                "guard-project-a",
                                firstProject),
                        admission(
                                firstProject,
                                limits,
                                1,
                                '0',
                                NOW.plusSeconds(600),
                                firstProject.scope()));

        assertFailure(
                () -> second.acquire(
                        permit(
                                "guard-project-b",
                                secondProject),
                        admission(
                                secondProject,
                                limits,
                                1,
                                '0',
                                NOW.plusSeconds(600),
                                firstProject.scope())),
                ReadOnlyShadowDataPlane.FailureReason
                        .BUDGET_EXHAUSTED);
        complete(admitted);
    }

    @Test
    void failedHalfOpenProbeStartsANewFullCoolDown() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(2, 20, 1);
        fail(
                first.acquire(
                        permit(
                                "guard-open",
                                request("guard-open", 42)),
                        admission(
                                request("guard-open", 42),
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BASELINE_SOURCE_UNAVAILABLE);
        now.set(NOW.plusSeconds(30));
        ReadOnlyShadowJobRequest probeRequest =
                request("guard-failed-probe", 43);
        fail(
                second.acquire(
                        permit(
                                "guard-failed-probe",
                                probeRequest),
                        admission(
                                probeRequest,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .DEADLINE_EXCEEDED);

        now.set(NOW.plusSeconds(59));
        ReadOnlyShadowJobRequest blocked =
                request("guard-cooldown", 44);
        assertFailure(
                () -> first.acquire(
                        permit("guard-cooldown", blocked),
                        admission(
                                blocked,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .CIRCUIT_OPEN);
        now.set(NOW.plusSeconds(60));
        complete(
                first.acquire(
                        permit("guard-cooldown", blocked),
                        admission(
                                blocked,
                                limits,
                                1,
                                '0')));
    }

    @Test
    void migratesOnlyToANewerPolicyGenerationAfterOldLeasesDrain() {
        ReadOnlyShadowExecutionGuard.Limits oldLimits =
                limits(2, 10, 3);
        ReadOnlyShadowExecutionGuard.Limits newLimits =
                limits(1, 5, 2);
        ReadOnlyShadowJobRequest oldRequest =
                request("guard-policy-old", 45);
        ReadOnlyShadowExecutionGuard.Lease oldLease =
                first.acquire(
                        permit(
                                "guard-policy-old",
                                oldRequest),
                        admission(
                                oldRequest,
                                oldLimits,
                                1,
                                '0'));
        ReadOnlyShadowJobRequest newRequest =
                request("guard-policy-new", 46);
        assertFailure(
                () -> second.acquire(
                        permit(
                                "guard-policy-new",
                                newRequest),
                        admission(
                                newRequest,
                                newLimits,
                                2,
                                'a')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BUDGET_EXHAUSTED);

        complete(oldLease);
        complete(
                second.acquire(
                        permit(
                                "guard-policy-new",
                                newRequest),
                        admission(
                                newRequest,
                                newLimits,
                                2,
                                'a')));
        ReadOnlyShadowJobRequest stale =
                request("guard-policy-stale", 47);
        assertFailure(
                () -> first.acquire(
                        permit(
                                "guard-policy-stale",
                                stale),
                        admission(
                                stale,
                                oldLimits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .GRANT_REVOKED);
    }

    @Test
    void rejectsExecutionIdReuseForDifferentImmutableRequestContent() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(2, 10, 3);
        ReadOnlyShadowJobRequest original =
                request("guard-id-original", 48);
        fail(
                first.acquire(
                        permit("guard-stable-id", original),
                        admission(
                                original,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .BASELINE_SOURCE_UNAVAILABLE);
        ReadOnlyShadowJobRequest drift =
                request("guard-id-drift", 49);

        assertFailure(
                () -> second.acquire(
                        permit("guard-stable-id", drift),
                        admission(
                                drift,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .EXECUTION_ID_CONFLICT);
    }

    @Test
    void clampsRenewalToAuthorityExpiryAndStoresNoPayloadColumns() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(2, 10, 3);
        ReadOnlyShadowJobRequest request =
                request("guard-expiry", 50);
        Instant authorityExpiry =
                NOW.plusSeconds(25);
        ReadOnlyShadowExecutionGuard.Lease lease =
                first.acquire(
                        permit(
                                "guard-expiry",
                                request,
                                NOW.plusSeconds(20)),
                        admission(
                                request,
                                limits,
                                1,
                                '0',
                                authorityExpiry));

        lease.renew(NOW.plusSeconds(40));
        TimestampView stored = jdbc.queryForObject(
                """
                SELECT lease_expires_at, maximum_expires_at
                FROM mirror_shadow_execution_guard_leases
                WHERE execution_id = 'guard-expiry'
                """,
                (row, index) -> new TimestampView(
                        row.getTimestamp(
                                "lease_expires_at")
                                .toInstant(),
                        row.getTimestamp(
                                "maximum_expires_at")
                                .toInstant()));
        assertThat(stored).isEqualTo(
                new TimestampView(
                        authorityExpiry,
                        authorityExpiry));

        List<String> columns = jdbc.queryForList(
                """
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME IN (
                    'MIRROR_SHADOW_EXECUTION_GUARD_STATES',
                    'MIRROR_SHADOW_EXECUTION_GUARD_LEASES'
                )
                """,
                String.class);
        assertThat(columns)
                .noneMatch(column -> column.matches(
                        ".*(PAYLOAD|REQUEST_JSON|RESPONSE|SECRET|CREDENTIAL|STACK|EXCEPTION).*"));

        now.set(NOW.plusSeconds(26));
        assertFailure(
                lease::succeeded,
                ReadOnlyShadowDataPlane.FailureReason
                        .LEASE_LOST);
    }

    @Test
    void failsClosedWhenSharedStateIsCorrupted() {
        ReadOnlyShadowExecutionGuard.Limits limits =
                limits(2, 10, 3);
        ReadOnlyShadowJobRequest initial =
                request("guard-corrupt-initial", 56);
        complete(
                first.acquire(
                        permit(
                                "guard-corrupt-initial",
                                initial),
                        admission(
                                initial,
                                limits,
                                1,
                                '0')));
        jdbc.update("""
                UPDATE mirror_shadow_execution_guard_states
                SET circuit_state = 'UNKNOWN'
                """);
        ReadOnlyShadowJobRequest next =
                request("guard-corrupt-next", 57);

        assertFailure(
                () -> second.acquire(
                        permit(
                                "guard-corrupt-next",
                                next),
                        admission(
                                next,
                                limits,
                                1,
                                '0')),
                ReadOnlyShadowDataPlane.FailureReason
                        .ADMISSION_AUTHORITY_UNAVAILABLE);
    }

    private DatabaseReadOnlyShadowExecutionGuard guard() {
        return new DatabaseReadOnlyShadowExecutionGuard(
                jdbc,
                mapper,
                transactions,
                now::get,
                () -> "guard-token-"
                        + String.format(
                        "%08d",
                        tokens.incrementAndGet()));
    }

    private static ReadOnlyShadowExecutionGuard.Limits limits(
            int concurrent,
            int starts,
            int failures) {
        return new ReadOnlyShadowExecutionGuard.Limits(
                concurrent,
                starts,
                Duration.ofMinutes(1),
                failures,
                Duration.ofSeconds(30));
    }

    private static ReadOnlyShadowJobRequest request(
            String requestId,
            long ordinal) {
        return ReadOnlyShadowJobTestFixtures.request(
                requestId,
                ordinal);
    }

    private ReadOnlyShadowDataPlane.Permit permit(
            String executionId,
            ReadOnlyShadowJobRequest request) {
        return permit(
                executionId,
                request,
                now.get().plusSeconds(20));
    }

    private static ReadOnlyShadowDataPlane.Permit permit(
            String executionId,
            ReadOnlyShadowJobRequest request,
            Instant leaseExpiresAt) {
        return new ReadOnlyShadowDataPlane.Permit(
                executionId,
                request,
                1,
                request.deadlineAt(),
                new ReadOnlyShadowDataPlane.ExecutionControl() {
                    @Override
                    public Instant leaseExpiresAt() {
                        return leaseExpiresAt;
                    }

                    @Override
                    public Instant heartbeat() {
                        return leaseExpiresAt;
                    }
                });
    }

    private static ReadOnlyShadowAccessAuthority.Admission
    admission(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowExecutionGuard.Limits limits,
            long policyRevision,
            char policyMaterial) {
        return admission(
                request,
                limits,
                policyRevision,
                policyMaterial,
                NOW.plusSeconds(600),
                request.scope());
    }

    private static ReadOnlyShadowAccessAuthority.Admission
    admission(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowExecutionGuard.Limits limits,
            long policyRevision,
            char policyMaterial,
            Instant validUntil) {
        return admission(
                request,
                limits,
                policyRevision,
                policyMaterial,
                validUntil,
                request.scope());
    }

    private static ReadOnlyShadowAccessAuthority.Admission
    admission(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowExecutionGuard.Limits limits,
            long policyRevision,
            char policyMaterial,
            Instant validUntil,
            CapabilitySnapshot.Scope guardScope) {
        MirrorArtifactRef policyRef =
                new MirrorArtifactRef(
                        "SHADOW_EXECUTION_GUARD_POLICY",
                        "refund-source-pressure",
                        policyRevision,
                        ReadOnlyShadowJobTestFixtures
                                .fingerprint(
                                        policyMaterial));
        ReadOnlyShadowSamplingGrantAuthority.Grant grant =
                new ReadOnlyShadowSamplingGrantAuthority.Grant(
                        request.scope(),
                        guardScope,
                        request.accessGrant()
                                .samplingGrantRef(),
                        request.accessGrant()
                                .maximumSamples(),
                        NOW.minusSeconds(30),
                        validUntil,
                        policyRef,
                        limits,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_SAMPLING_GRANT_ATTESTATION",
                                request.accessGrant()
                                        .samplingGrantRef()
                                        .id(),
                                '1'),
                        new MirrorArtifactRef(
                                "SHADOW_EXECUTION_GUARD_POLICY_ATTESTATION",
                                policyRef.id(),
                                policyRef.revision(),
                                ReadOnlyShadowJobTestFixtures
                                        .fingerprint('2')),
                        NOW);
        ReadOnlyShadowKillSwitchAuthority.State killSwitch =
                new ReadOnlyShadowKillSwitchAuthority.State(
                        request.scope(),
                        request.accessGrant()
                                .killSwitchRef(),
                        true,
                        NOW.minusSeconds(30),
                        validUntil,
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_KILL_SWITCH_ATTESTATION",
                                request.accessGrant()
                                        .killSwitchRef()
                                        .id(),
                                '2'),
                        NOW);
        MirrorDeploymentIsolationRunTrust.Admission egress =
                new MirrorDeploymentIsolationRunTrust.Admission(
                        request.scope(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationBundle
                                        .ARTIFACT_KIND,
                                "guard-egress-decision",
                                '3'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAuthorityKeySetPublication
                                        .ARTIFACT_KIND,
                                "guard-egress-authority",
                                '4'),
                        request.accessGrant()
                                .egressAuthorityRef(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAttestationStatusPublication
                                        .ARTIFACT_KIND,
                                "guard-egress-status",
                                '5'),
                        ReadOnlyShadowJobTestFixtures.ref(
                                MirrorDeploymentIsolationAgentSnapshot
                                        .ARTIFACT_KIND,
                                "guard-egress-snapshot",
                                '6'),
                        NOW,
                        validUntil);
        return new ReadOnlyShadowAccessAuthority.Admission(
                ReadOnlyShadowJobTestFixtures
                        .fingerprint('f'),
                request.accessGrant().zeroWriteProof(),
                limits,
                grant,
                killSwitch,
                egress,
                NOW,
                validUntil);
    }

    private static ReadOnlyShadowJobRequest withScope(
            ReadOnlyShadowJobRequest request,
            CapabilitySnapshot.Scope scope) {
        return new ReadOnlyShadowJobRequest(
                request.schemaVersion(),
                request.requestId(),
                scope,
                request.inventoryRef(),
                request.unitId(),
                request.scenarioCaseRef(),
                request.targetCapabilityRef(),
                request.candidatePlanRef(),
                request.baselineBindingRef(),
                request.comparisonPolicyRef(),
                request.accessGrant(),
                request.deadlineAt());
    }

    private static void complete(
            ReadOnlyShadowExecutionGuard.Lease lease) {
        lease.succeeded();
        lease.close();
    }

    private static void fail(
            ReadOnlyShadowExecutionGuard.Lease lease,
            ReadOnlyShadowDataPlane.FailureReason reason) {
        lease.failed(reason);
        lease.close();
    }

    private static Object outcome(
            LeaseSupplier supplier) {
        try {
            return supplier.acquire();
        } catch (ReadOnlyShadowDataPlane.Failure failure) {
            return failure.reason();
        }
    }

    private static void assertFailure(
            Runnable action,
            ReadOnlyShadowDataPlane.FailureReason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        ReadOnlyShadowDataPlane.Failure.class)
                .extracting("reason")
                .isEqualTo(reason);
    }

    @FunctionalInterface
    private interface LeaseSupplier {
        ReadOnlyShadowExecutionGuard.Lease acquire();
    }

    private record TimestampView(
            Instant leaseExpiresAt,
            Instant maximumExpiresAt) {
    }
}
