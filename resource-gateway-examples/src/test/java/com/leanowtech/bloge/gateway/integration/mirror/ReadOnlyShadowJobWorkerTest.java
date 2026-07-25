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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowJobWorkerTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    ReadOnlyShadowJobTestFixtures.NOW);

    private EmbeddedDatabase database;
    private DatabaseReadOnlyShadowJobRepository repository;
    private ReadOnlyShadowComparisonIntegrity integrity;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        integrity =
                ReadOnlyShadowJobTestFixtures.integrity(
                        mapper);
        repository =
                new DatabaseReadOnlyShadowJobRepository(
                        jdbc,
                        mapper,
                        integrity,
                        new DataSourceTransactionManager(
                                database),
                        now::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesOnlyAWorkerConstructedSignedV2Comparison() {
        ReadOnlyShadowJobRequest request =
                submit("shadow-worker-success", 1);
        AtomicInteger executions =
                new AtomicInteger();
        ReadOnlyShadowDataPlane dataPlane =
                successful(request, executions);
        ReadOnlyShadowJobWorker worker = worker(
                dataPlane);

        ReadOnlyShadowJobRepository.Claim claim =
                worker.runOne(
                        request.scope().region(),
                        request.scope().environmentId(),
                        "shadow-worker-1");

        assertThat(claim.outcome()).isEqualTo(
                ReadOnlyShadowJobRepository
                        .ClaimOutcome.ACQUIRED);
        assertThat(executions).hasValue(1);
        ReadOnlyShadowJob completed =
                repository.find(
                        request.scope(),
                        claim.job().jobId())
                        .orElseThrow();
        assertThat(completed.status()).isEqualTo(
                ReadOnlyShadowJob.Status.SUCCEEDED);
        ReadOnlyShadowComparison comparison =
                repository.findComparison(
                        request.scope(),
                        completed.jobId())
                        .orElseThrow();
        assertThat(comparison.schemaVersion())
                .isEqualTo(
                        ReadOnlyShadowComparison
                                .SCHEMA_VERSION);
        assertThat(comparison.comparisonPolicyRef())
                .isEqualTo(
                        request.comparisonPolicyRef());
        assertThat(comparison
                .sourceResolutionAttestationRef()
                .kind()).isEqualTo(
                "SHADOW_SOURCE_RESOLUTION_ATTESTATION");
        assertThat(comparison.certifiable()).isTrue();
        assertThat(worker.ready()).isTrue();
    }

    @Test
    void requeuesWithoutExecutingWhenTheDataPlaneIsUnavailable() {
        ReadOnlyShadowJobRequest request =
                submit("shadow-worker-unavailable", 2);
        ReadOnlyShadowJobWorker worker = worker(
                ReadOnlyShadowDataPlane.unavailable());

        ReadOnlyShadowJobRepository.Claim observation =
                worker.runOne(
                request.scope().region(),
                request.scope().environmentId(),
                "shadow-worker-1");

        ReadOnlyShadowJob queued =
                repository.find(
                        request.scope(),
                        jobId(request)).orElseThrow();
        assertThat(queued.status()).isEqualTo(
                ReadOnlyShadowJob.Status.QUEUED);
        assertThat(queued.attemptCount()).isZero();
        assertThat(queued.failureCode()).isBlank();
        assertThat(observation.outcome()).isEqualTo(
                ReadOnlyShadowJobRepository
                        .ClaimOutcome.NO_WORK);
        assertThat(observation.observedAt())
                .isEqualTo(
                        ReadOnlyShadowJobTestFixtures.NOW);
        assertThat(worker.ready()).isFalse();
    }

    @Test
    void refusesADataPlaneProofThatDiffersFromTheReservedGrant() {
        ReadOnlyShadowJobRequest request =
                submit("shadow-worker-forged-proof", 3);
        ReadOnlyShadowDataPlane.ExecutionResult source =
                ReadOnlyShadowJobTestFixtures
                        .executionResult(request);
        ReadOnlyShadowComparison.AccessProof mismatch =
                new ReadOnlyShadowComparison.AccessProof(
                        source.accessProof().accessMode(),
                        source.accessProof().samplingGrantRef(),
                        source.accessProof().egressAuthorityRef(),
                        source.accessProof().killSwitchRef(),
                        4,
                        source.accessProof().maximumSamples(),
                        false,
                        0);
        ReadOnlyShadowDataPlane dataPlane =
                fixed(new ReadOnlyShadowDataPlane.ExecutionResult(
                        mismatch,
                        source.sourceResolutionAttestationRef(),
                        source.baseline(),
                        source.candidate(),
                        source.observedAt(),
                        source.results()));

        worker(dataPlane).runOne(
                request.scope().region(),
                request.scope().environmentId(),
                "shadow-worker-1");

        ReadOnlyShadowJob failed =
                repository.find(
                        request.scope(),
                        jobId(request)).orElseThrow();
        assertThat(failed.status()).isEqualTo(
                ReadOnlyShadowJob.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo(
                "RG.MIRROR.SHADOW.RESULT_INVALID");
        assertThat(repository.findComparison(
                request.scope(),
                failed.jobId())).isEmpty();
    }

    @Test
    void persistsOnlyBoundedFailureCodesAndHonorsRetryClassification() {
        ReadOnlyShadowJobRequest request =
                submit("shadow-worker-failure", 4);
        ReadOnlyShadowDataPlane retryable =
                failing(
                        ReadOnlyShadowDataPlane
                                .FailureReason
                                .BASELINE_SOURCE_UNAVAILABLE);

        worker(retryable).runOne(
                request.scope().region(),
                request.scope().environmentId(),
                "shadow-worker-1");
        ReadOnlyShadowJob queued = repository.find(
                request.scope(),
                jobId(request)).orElseThrow();
        assertThat(queued.status()).isEqualTo(
                ReadOnlyShadowJob.Status.QUEUED);
        assertThat(queued.failureCode()).isEqualTo(
                "RG.MIRROR.SHADOW.BASELINE_SOURCE_UNAVAILABLE");

        now.set(ReadOnlyShadowJobTestFixtures.NOW
                .plusSeconds(5));
        ReadOnlyShadowDataPlane terminal =
                failing(
                        ReadOnlyShadowDataPlane
                                .FailureReason
                                .WRITE_CAPABILITY_DETECTED);
        worker(terminal).runOne(
                request.scope().region(),
                request.scope().environmentId(),
                "shadow-worker-2");
        ReadOnlyShadowJob failed = repository.find(
                request.scope(),
                jobId(request)).orElseThrow();
        assertThat(failed.status()).isEqualTo(
                ReadOnlyShadowJob.Status.FAILED);
        assertThat(failed.failureCode()).isEqualTo(
                "RG.MIRROR.SHADOW.WRITE_CAPABILITY_DETECTED");
        assertThat(failed.toString())
                .doesNotContain("request")
                .doesNotContain("response")
                .doesNotContain("credential");
    }

    private ReadOnlyShadowJobRequest submit(
            String requestId,
            long ordinal) {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        requestId, ordinal);
        repository.submit(
                request,
                ReadOnlyShadowJobTestFixtures.POLICY);
        return request;
    }

    private String jobId(
            ReadOnlyShadowJobRequest request) {
        return ReadOnlyShadowJobIntegrity.jobId(
                ReadOnlyShadowJobIntegrity
                        .requestFingerprint(
                                mapper, request));
    }

    private ReadOnlyShadowJobWorker worker(
            ReadOnlyShadowDataPlane dataPlane) {
        return new ReadOnlyShadowJobWorker(
                repository,
                dataPlane,
                integrity,
                ReadOnlyShadowJobTestFixtures.POLICY);
    }

    private static ReadOnlyShadowDataPlane successful(
            ReadOnlyShadowJobRequest expected,
            AtomicInteger executions) {
        return new ReadOnlyShadowDataPlane() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public ExecutionResult execute(
                    Permit permit) {
                assertThat(permit.request())
                        .isEqualTo(expected);
                assertThat(permit.executionId())
                        .startsWith("shadow-");
                executions.incrementAndGet();
                return ReadOnlyShadowJobTestFixtures
                        .executionResult(expected);
            }
        };
    }

    private static ReadOnlyShadowDataPlane fixed(
            ReadOnlyShadowDataPlane.ExecutionResult result) {
        return new ReadOnlyShadowDataPlane() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public ExecutionResult execute(
                    Permit permit) {
                return result;
            }
        };
    }

    private static ReadOnlyShadowDataPlane failing(
            ReadOnlyShadowDataPlane.FailureReason reason) {
        return new ReadOnlyShadowDataPlane() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public ExecutionResult execute(
                    Permit permit) {
                throw new ReadOnlyShadowDataPlane
                        .Failure(reason);
            }
        };
    }
}
