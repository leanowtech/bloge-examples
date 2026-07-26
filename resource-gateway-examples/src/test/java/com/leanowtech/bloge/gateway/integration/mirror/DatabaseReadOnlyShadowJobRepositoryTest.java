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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseReadOnlyShadowJobRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    ReadOnlyShadowJobTestFixtures.NOW);

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private ReadOnlyShadowComparisonIntegrity comparisonIntegrity;
    private DatabaseReadOnlyShadowJobRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        comparisonIntegrity =
                ReadOnlyShadowJobTestFixtures.integrity(
                        mapper);
        repository =
                new DatabaseReadOnlyShadowJobRepository(
                        jdbc,
                        mapper,
                        comparisonIntegrity,
                        transactions,
                        now::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void survivesRestartAndRecoversOnlyAnExactRequestRetry() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-request-1", 1);
        ReadOnlyShadowJobRepository.Submission admitted =
                repository.submit(
                        request,
                        ReadOnlyShadowJobTestFixtures.POLICY);
        DatabaseReadOnlyShadowJobRepository restarted =
                new DatabaseReadOnlyShadowJobRepository(
                        jdbc,
                        mapper,
                        comparisonIntegrity,
                        transactions,
                        now::get);
        restarted.init();

        assertThat(admitted.idempotentReplay())
                .isFalse();
        assertThat(restarted.find(
                request.scope(),
                admitted.job().jobId()))
                .contains(admitted.job());
        assertThat(restarted.findRequest(
                request.scope(),
                admitted.job().jobId()))
                .contains(request);
        assertThat(restarted.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY)
                .idempotentReplay()).isTrue();

        ReadOnlyShadowJobRequest drift =
                copy(
                        request,
                        request.requestId(),
                        2,
                        request.deadlineAt());
        assertReason(
                () -> restarted.submit(
                        drift,
                        ReadOnlyShadowJobTestFixtures.POLICY),
                ReadOnlyShadowJobRepository
                        .Reason.REQUEST_CONFLICT);
    }

    @Test
    void rejectsATransactionManagerWithoutNestedSavepoints() {
        transactions.setNestedTransactionAllowed(false);

        assertThatThrownBy(() ->
                new DatabaseReadOnlyShadowJobRepository(
                        jdbc,
                        mapper,
                        comparisonIntegrity,
                        transactions,
                        now::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "nested-savepoint DataSourceTransactionManager");
    }

    @Test
    void reservesOneGrantOrdinalAcrossConcurrentRequestIds()
            throws Exception {
        ReadOnlyShadowJobRequest left =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-request-left", 7);
        ReadOnlyShadowJobRequest right =
                copy(
                        left,
                        "shadow-request-right",
                        7,
                        left.deadlineAt());
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Callable<Object> submitLeft = () ->
                    outcome(() -> repository.submit(
                            left,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY));
            Callable<Object> submitRight = () ->
                    outcome(() -> repository.submit(
                            right,
                            ReadOnlyShadowJobTestFixtures
                                    .POLICY));
            Future<Object> first =
                    executor.submit(submitLeft);
            Future<Object> second =
                    executor.submit(submitRight);

            List<Object> outcomes =
                    List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(
                    value -> value instanceof
                            ReadOnlyShadowJobRepository
                                    .Submission)
                    .hasSize(1);
            assertThat(outcomes).filteredOn(
                    value -> value
                            == ReadOnlyShadowJobRepository
                            .Reason.SAMPLE_ORDINAL_CONFLICT)
                    .hasSize(1);
        }
    }

    @Test
    void fencesAStaleWorkerAndRecoversItsExpiredLease() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-lease-recovery", 3);
        ReadOnlyShadowJob job = repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY).job();
        ReadOnlyShadowJobRepository.Claim first =
                claim("worker-a");

        now.set(ReadOnlyShadowJobTestFixtures.NOW
                .plusSeconds(61));
        ReadOnlyShadowJobRepository.Claim second =
                claim("worker-b");
        assertThat(second.job().attemptCount())
                .isEqualTo(2);
        assertThat(second.lease().epoch())
                .isGreaterThan(first.lease().epoch());

        ReadOnlyShadowComparison signed =
                comparisonIntegrity.sign(
                        ReadOnlyShadowJobTestFixtures
                                .unsignedComparison(
                                        job.jobId(),
                                        request));
        assertReason(
                () -> repository.complete(
                        first.lease(), signed),
                ReadOnlyShadowJobRepository
                        .Reason.LEASE_LOST);

        ReadOnlyShadowJob completed =
                repository.complete(
                        second.lease(), signed);
        assertThat(completed.status()).isEqualTo(
                ReadOnlyShadowJob.Status.SUCCEEDED);
        assertThat(repository.findComparison(
                request.scope(),
                job.jobId())).contains(signed);
        assertThat(repository.complete(
                second.lease(), signed))
                .isEqualTo(completed);

        List<ReadOnlyShadowJobLifecycleEvent>
                lifecycle = repository.lifecycle(
                request.scope(),
                job.jobId(),
                0,
                10);
        assertThat(lifecycle)
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .containsExactly(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.ADMITTED,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.CLAIMED,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.TAKEN_OVER,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.SUCCEEDED);
        assertThat(lifecycle)
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::ownerFingerprint)
                .doesNotContain("worker-a", "worker-b");
        assertThat(lifecycle.get(2)
                .ownerFingerprint())
                .matches("sha256:[a-f0-9]{64}");
    }

    @Test
    void renewsTheLeaseAndRejectsTheSupersededFence() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-heartbeat", 4);
        repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY);
        ReadOnlyShadowJobRepository.Claim claim =
                claim("worker-a");
        now.set(ReadOnlyShadowJobTestFixtures.NOW
                .plusSeconds(20));

        ReadOnlyShadowJobRepository.Heartbeat heartbeat =
                repository.heartbeat(
                        claim.lease(),
                        ReadOnlyShadowJobTestFixtures.POLICY);
        assertThat(heartbeat.lease().expiresAt())
                .isAfter(claim.lease().expiresAt());
        assertReason(
                () -> repository.fail(
                        claim.lease(),
                        "RG.MIRROR.SHADOW.OLD_FENCE",
                        false,
                        ReadOnlyShadowJobTestFixtures.POLICY),
                ReadOnlyShadowJobRepository
                        .Reason.LEASE_LOST);

        assertThat(repository.fail(
                heartbeat.lease(),
                "RG.MIRROR.SHADOW.STOPPED",
                false,
                ReadOnlyShadowJobTestFixtures.POLICY)
                .status()).isEqualTo(
                ReadOnlyShadowJob.Status.FAILED);
    }

    @Test
    void boundsRetryAndExpiresWorkWhenAFullLeaseNoLongerFits() {
        ReadOnlyShadowJobRequest retry =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-retry", 5);
        repository.submit(
                retry,
                ReadOnlyShadowJobTestFixtures.POLICY);
        ReadOnlyShadowJobRepository.Claim first =
                claim("worker-a");
        ReadOnlyShadowJob queued = repository.fail(
                first.lease(),
                "RG.MIRROR.SHADOW.BASELINE_SOURCE_UNAVAILABLE",
                true,
                ReadOnlyShadowJobTestFixtures.POLICY);
        assertThat(queued.status()).isEqualTo(
                ReadOnlyShadowJob.Status.QUEUED);
        assertThat(claim("worker-a").outcome())
                .isEqualTo(
                        ReadOnlyShadowJobRepository
                                .ClaimOutcome.NO_WORK);

        now.set(ReadOnlyShadowJobTestFixtures.NOW
                .plusSeconds(5));
        ReadOnlyShadowJobRepository.Claim second =
                claim("worker-b");
        assertThat(second.job().attemptCount())
                .isEqualTo(2);
        assertThat(repository.fail(
                second.lease(),
                "RG.MIRROR.SHADOW.GRANT_REVOKED",
                false,
                ReadOnlyShadowJobTestFixtures.POLICY)
                .status()).isEqualTo(
                ReadOnlyShadowJob.Status.FAILED);

        ReadOnlyShadowJobRequest expires =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-expires", 6);
        ReadOnlyShadowJob expiring = repository.submit(
                expires,
                ReadOnlyShadowJobTestFixtures.POLICY).job();
        now.set(ReadOnlyShadowJobTestFixtures.NOW
                .plus(Duration.ofMinutes(29))
                .plusSeconds(1));
        assertThat(claim("worker-c").outcome())
                .isEqualTo(
                        ReadOnlyShadowJobRepository
                                .ClaimOutcome.NO_WORK);
        assertThat(repository.find(
                expires.scope(),
                expiring.jobId()).orElseThrow()
                .status()).isEqualTo(
                ReadOnlyShadowJob.Status.EXPIRED);
    }

    @Test
    void rejectsAResignedComparisonThatDriftsFromTheJobClosure() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-comparison-drift", 8);
        ReadOnlyShadowJob job = repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY).job();
        ReadOnlyShadowJobRepository.Claim claim =
                claim("worker-a");
        ReadOnlyShadowComparison source =
                ReadOnlyShadowJobTestFixtures
                        .unsignedComparison(
                                job.jobId(), request);
        ReadOnlyShadowComparison drift =
                new ReadOnlyShadowComparison(
                        source.schemaVersion(),
                        source.comparisonId(),
                        source.revision(),
                        "",
                        source.scope(),
                        source.inventoryRef(),
                        source.unitId(),
                        source.scenarioCaseRef(),
                        source.targetCapabilityRef(),
                        ReadOnlyShadowJobTestFixtures.ref(
                                "SHADOW_COMPARISON_POLICY",
                                "forged-policy",
                        'f'),
                        source.sourceResolutionAttestationRef(),
                        source.authorityProof(),
                        source.accessProof(),
                        source.baseline(),
                        source.candidate(),
                        source.observedAt(),
                        source.results(),
                        null);

        assertReason(
                () -> repository.complete(
                        claim.lease(),
                        comparisonIntegrity.sign(drift)),
                ReadOnlyShadowJobRepository
                        .Reason.COMPARISON_MISMATCH);
    }

    @Test
    void detectsIndexCorruptionAndStoresNoBusinessPayloadColumns() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-corruption", 9);
        ReadOnlyShadowJob job = repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY).job();
        jdbc.update("""
                UPDATE mirror_shadow_jobs
                SET sample_ordinal = sample_ordinal + 1
                WHERE job_id = ?
                """, job.jobId());

        assertReason(
                () -> repository.find(
                        request.scope(),
                        job.jobId()),
                ReadOnlyShadowJobRepository
                        .Reason.STORED_STATE_CORRUPT);

        List<String> columns = jdbc.queryForList("""
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_NAME = 'MIRROR_SHADOW_JOBS'
                ORDER BY ORDINAL_POSITION
                """, String.class);
        assertThat(columns).noneMatch(column ->
                column.equals("REQUEST_PAYLOAD")
                        || column.equals("RESPONSE_PAYLOAD")
                        || column.contains("CREDENTIAL")
                        || column.contains("SECRET")
                        || column.contains("STACK_TRACE")
                        || column.contains("EXCEPTION_MESSAGE"));
        assertThat(columns).contains(
                "SAMPLING_GRANT_FINGERPRINT",
                "SAMPLE_ORDINAL",
                "LEASE_EPOCH",
                "COMPARISON_FINGERPRINT");

        List<String> lifecycleColumns =
                jdbc.queryForList("""
                        SELECT COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = 'MIRROR_SHADOW_JOB_LIFECYCLE'
                        ORDER BY ORDINAL_POSITION
                        """, String.class);
        assertThat(lifecycleColumns)
                .contains(
                        "SEQUENCE",
                        "TRANSITION",
                        "OWNER_FINGERPRINT",
                        "RECORD_FINGERPRINT")
                .noneMatch(column ->
                        column.contains("PAYLOAD")
                                || column.contains(
                                "CREDENTIAL")
                                || column.contains("SECRET")
                                || column.contains(
                                "STACK_TRACE")
                                || column.contains(
                                "EXCEPTION_MESSAGE"));
    }

    @Test
    void lifecycleUsesAnExclusiveCursorAndExactEnterpriseScope() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-lifecycle-page", 10);
        ReadOnlyShadowJob job = repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures
                        .POLICY).job();
        ReadOnlyShadowJobRepository.Claim claim =
                claim("worker-a");
        repository.fail(
                claim.lease(),
                "RG.MIRROR.SHADOW.BASELINE_SOURCE_UNAVAILABLE",
                true,
                ReadOnlyShadowJobTestFixtures.POLICY);

        List<ReadOnlyShadowJobLifecycleEvent> first =
                repository.lifecycle(
                        request.scope(),
                        job.jobId(),
                        0,
                        1);
        List<ReadOnlyShadowJobLifecycleEvent> rest =
                repository.lifecycle(
                        request.scope(),
                        job.jobId(),
                        first.getFirst().sequence(),
                        10);

        assertThat(first)
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .containsExactly(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.ADMITTED);
        assertThat(rest)
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .containsExactly(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.CLAIMED,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.RETRY_SCHEDULED);
        assertThat(rest.getFirst().sequence())
                .isGreaterThan(
                        first.getFirst().sequence());
        assertThat(repository.lifecycle(
                ReadOnlyShadowJobTestFixtures
                        .scope("other"),
                job.jobId(),
                0,
                10)).isEmpty();
    }

    private ReadOnlyShadowJobRepository.Claim claim(
            String owner) {
        CapabilitySnapshot.Scope scope =
                ReadOnlyShadowJobTestFixtures
                        .scope("support");
        return repository.claimNext(
                scope.region(),
                scope.environmentId(),
                owner,
                ReadOnlyShadowJobTestFixtures.POLICY);
    }

    private static Object outcome(
            Supplier<?> action) {
        try {
            return action.get();
        } catch (ReadOnlyShadowJobRepository.Violation failure) {
            return failure.reason();
        }
    }

    private static ReadOnlyShadowJobRequest copy(
            ReadOnlyShadowJobRequest source,
            String requestId,
            long ordinal,
            Instant deadlineAt) {
        ReadOnlyShadowJobRequest.AccessGrant grant =
                source.accessGrant();
        return new ReadOnlyShadowJobRequest(
                source.schemaVersion(),
                requestId,
                source.scope(),
                source.inventoryRef(),
                source.unitId(),
                source.scenarioCaseRef(),
                source.targetCapabilityRef(),
                source.candidatePlanRef(),
                source.baselineBindingRef(),
                source.comparisonPolicyRef(),
                new ReadOnlyShadowJobRequest.AccessGrant(
                        grant.accessMode(),
                        grant.samplingGrantRef(),
                        grant.egressAuthorityRef(),
                        grant.killSwitchRef(),
                        ordinal,
                        grant.maximumSamples()),
                deadlineAt);
    }

    private static void assertReason(
            Runnable action,
            ReadOnlyShadowJobRepository.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        ReadOnlyShadowJobRepository
                                .Violation.class)
                .extracting(failure ->
                        ((ReadOnlyShadowJobRepository
                                .Violation) failure)
                                .reason())
                .isEqualTo(reason);
    }
}
