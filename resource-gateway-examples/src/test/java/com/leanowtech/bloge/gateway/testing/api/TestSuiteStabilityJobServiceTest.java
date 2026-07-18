package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobServiceTest {

    private static final Instant DEADLINE = Instant.parse("2026-07-19T00:00:00Z");
    private static final TestSuiteExecutionRequest.SuiteRef SUITE_REF =
            new TestSuiteExecutionRequest.SuiteRef("suite-a", 7, sha('a'));

    private ObjectMapper mapper;
    private TestSuiteStabilityJobRepository jobs;
    private TestSuiteStabilityExecutionService executions;
    private TestSuiteStabilityQueuePolicy policy;
    private TestSuiteStabilityJobService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        jobs = mock(TestSuiteStabilityJobRepository.class);
        executions = mock(TestSuiteStabilityExecutionService.class);
        policy = new TestSuiteStabilityQueuePolicy(
                1, 100, 10, 4, 2, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3,
                Duration.ofDays(7), Duration.ofDays(30));
        service = service(true);
    }

    @Test
    void submitsDeterministicPayloadFreeJobAndPreservesRepositoryDisposition() {
        TestSuiteStabilityJobSubmitRequest request = request();
        String requestFingerprint = ProtocolFingerprint.of(mapper, request.execution());
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.empty());
        when(executions.authorizeSubmission("suite-a", request.execution(), identity()))
                .thenReturn(descriptor(requestFingerprint));
        when(jobs.submitDetailed(any(), eq(policy))).thenAnswer(invocation -> {
            TestSuiteStabilityJobSubmission submission = invocation.getArgument(0);
            return new TestSuiteStabilityJobRepository.SubmissionResult(
                    queued(submission), false);
        });

        TestSuiteStabilityJobSubmitResponse response =
                service.submit("suite-a", request, identity());

        assertThat(response.schemaVersion())
                .isEqualTo(TestSuiteStabilityJobSubmitResponse.SCHEMA_VERSION);
        assertThat(response.idempotentReplay()).isFalse();
        assertThat(response.job().jobId()).matches("stability-job-[a-f0-9]{64}");
        assertThat(response.job().requestFingerprint()).isEqualTo(requestFingerprint);
        assertThat(response.job().suiteRef()).isEqualTo(SUITE_REF);
        assertThat(response.job().status())
                .isEqualTo(TestSuiteStabilityJobRecord.Status.QUEUED);
        assertThat(response.job().terminal()).isFalse();
        assertThat(response.job().stabilityRunId()).isEmpty();
        verify(jobs).submitDetailed(
                org.mockito.ArgumentMatchers.argThat(submission ->
                        submission.jobId().equals(response.job().jobId())
                                && submission.requestFingerprint().equals(requestFingerprint)
                                && submission.classification().equals("INTERNAL")
                                && submission.principal().actorId().equals("runner")
                                && submission.principal().correlationId().equals("correlation-a")
                                && submission.deadlineAt().equals(DEADLINE)),
                eq(policy));
    }

    @Test
    void exactReplaySkipsMutableSuiteAuthorityAndSurvivesCorrelationRotation() {
        TestSuiteStabilityJobSubmitRequest request = request();
        AtomicReference<TestSuiteStabilityJobRecord> retained = new AtomicReference<>();
        String requestFingerprint = ProtocolFingerprint.of(mapper, request.execution());
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(retained.get()));
        when(executions.authorizeSubmission("suite-a", request.execution(), identity()))
                .thenReturn(descriptor(requestFingerprint));
        when(jobs.submitDetailed(any(), eq(policy))).thenAnswer(invocation -> {
            TestSuiteStabilityJobRecord job = queued(invocation.getArgument(0));
            retained.set(job);
            return new TestSuiteStabilityJobRepository.SubmissionResult(job, false);
        });
        service.submit("suite-a", request, identity());
        clearInvocations(executions, jobs);

        TestSuiteStabilityJobSubmitResponse replay = service.submit(
                "suite-a", request, identity("org-a", "project-a", "CONFIDENTIAL",
                        "runner", "correlation-b"));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.job().jobId()).isEqualTo(retained.get().jobId());
        verifyNoInteractions(executions);
        verify(jobs, never()).submitDetailed(any(), any());
    }

    @Test
    void replayRejectsChangedIntentBeforeMutableAuthorityRead() {
        TestSuiteStabilityJobSubmitRequest original = request();
        String requestFingerprint = ProtocolFingerprint.of(mapper, original.execution());
        TestSuiteStabilityJobSubmission submission = submission(
                jobId(original.execution(), identity()), original, requestFingerprint,
                identity());
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.of(queued(submission)));
        TestSuiteStabilityJobSubmitRequest changed = new TestSuiteStabilityJobSubmitRequest(
                TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION, original.execution(),
                TestSuiteStabilityJobSubmission.Priority.HIGH, DEADLINE);

        assertProblem(() -> service.submit("suite-a", changed, identity()), 409,
                "RG.TEST.STABILITY_JOB_IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(executions);
        verify(jobs, never()).submitDetailed(any(), any());
    }

    @Test
    void replayRejectsDifferentStableAuthorityBeforeMutableAuthorityRead() {
        TestSuiteStabilityJobSubmitRequest request = request();
        String requestFingerprint = ProtocolFingerprint.of(mapper, request.execution());
        TestSuiteStabilityJobSubmission submission = submission(
                jobId(request.execution(), identity()), request, requestFingerprint,
                identity());
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.of(queued(submission)));

        assertProblem(() -> service.submit("suite-a", request,
                        identity("org-a", "project-a", "CONFIDENTIAL", "other-runner", "c-b")),
                409, "RG.TEST.STABILITY_JOB_IDEMPOTENCY_CONFLICT");
        verifyNoInteractions(executions);
        verify(jobs, never()).submitDetailed(any(), any());
    }

    @Test
    void disabledRuntimeAllowsRetainedReplayButRejectsFreshSubmission() {
        TestSuiteStabilityJobSubmitRequest request = request();
        String requestFingerprint = ProtocolFingerprint.of(mapper, request.execution());
        TestSuiteStabilityJobRecord existing = queued(submission(
                jobId(request.execution(), identity()), request, requestFingerprint,
                identity()));
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.of(existing));
        TestSuiteStabilityJobService disabled = service(false);

        assertThat(disabled.submit("suite-a", request, identity()).idempotentReplay())
                .isTrue();
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.empty());
        TestSuiteStabilityExecutionRequest freshExecution =
                new TestSuiteStabilityExecutionRequest("", SUITE_REF, "stability-request-2",
                        3, Map.of());
        TestSuiteStabilityJobSubmitRequest fresh = new TestSuiteStabilityJobSubmitRequest(
                TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION, freshExecution,
                TestSuiteStabilityJobSubmission.Priority.NORMAL, DEADLINE);

        assertProblem(() -> disabled.submit("suite-a", fresh, identity()), 503,
                "RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE");
        verifyNoInteractions(executions);
    }

    @Test
    void queryHidesOrganizationAndProjectMismatchAndEnforcesClearance() {
        TestSuiteStabilityJobSubmitRequest request = request();
        TestSuiteStabilityJobRecord existing = queued(submission(
                "stability-job-" + "3".repeat(64), request,
                ProtocolFingerprint.of(mapper, request.execution()), identity()));
        when(jobs.find("tenant-a", "test", existing.jobId()))
                .thenReturn(Optional.of(existing));

        assertProblem(() -> service.find(existing.jobId(),
                        identity("org-b", "project-a", "CONFIDENTIAL", "runner", "c-b")),
                404, "RG.TEST.STABILITY_JOB_NOT_FOUND");
        assertProblem(() -> service.find(existing.jobId(),
                        identity("org-a", "project-b", "CONFIDENTIAL", "runner", "c-c")),
                404, "RG.TEST.STABILITY_JOB_NOT_FOUND");
        assertProblem(() -> service.find(existing.jobId(),
                        identity("org-a", "project-a", "PUBLIC", "runner", "c-d")),
                403, "RG.TEST.STABILITY_JOB_CLEARANCE_FORBIDDEN");
    }

    @Test
    void malformedRetainedProjectionFailsAsStoreConflictInsteadOfClientError() {
        TestSuiteStabilityJobSubmitRequest request = request();
        TestSuiteStabilityJobRecord malformed = new TestSuiteStabilityJobRecord(
                "legacy-job", request.execution(),
                ProtocolFingerprint.of(mapper, request.execution()), "INTERNAL",
                TestSuiteStabilityJobPrincipal.from(identity()), request.priority(),
                TestSuiteStabilityJobRecord.Status.QUEUED, 0,
                DEADLINE.minus(Duration.ofHours(1)), DEADLINE,
                DEADLINE.minus(Duration.ofHours(1)), DEADLINE.minus(Duration.ofHours(1)),
                DEADLINE.plus(Duration.ofDays(30)), "", "", "", "", "", sha('f'));
        when(jobs.find("tenant-a", "test", "stability-job-" + "5".repeat(64)))
                .thenReturn(Optional.of(malformed));

        assertProblem(() -> service.find(
                        "stability-job-" + "5".repeat(64), identity()),
                503, "RG.TEST.STABILITY_JOB_STORE_CONFLICT");
    }

    @Test
    void cancellationIsActorBoundButIndependentOfTransientCorrelation() {
        TestSuiteStabilityJobSubmitRequest submit = request();
        TestSuiteStabilityJobRecord existing = queued(submission(
                "stability-job-" + "4".repeat(64), submit,
                ProtocolFingerprint.of(mapper, submit.execution()), identity()));
        when(jobs.find("tenant-a", "test", existing.jobId()))
                .thenReturn(Optional.of(existing));
        AtomicReference<String> firstFingerprint = new AtomicReference<>();
        when(jobs.cancel(eq("tenant-a"), eq("test"), eq(existing.jobId()),
                eq("cancel-1"), anyString(), eq(policy))).thenAnswer(invocation -> {
            String fingerprint = invocation.getArgument(4);
            if (firstFingerprint.get() == null) {
                firstFingerprint.set(fingerprint);
            } else {
                assertThat(fingerprint).isEqualTo(firstFingerprint.get());
            }
            return cancelled(existing, fingerprint);
        });
        TestSuiteStabilityJobCancelRequest cancellation =
                new TestSuiteStabilityJobCancelRequest(
                        TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION, "cancel-1");

        TestSuiteStabilityJobView first = service.cancel(
                existing.jobId(), cancellation, identity());
        TestSuiteStabilityJobView replay = service.cancel(existing.jobId(), cancellation,
                identity("org-a", "project-a", "CONFIDENTIAL", "runner", "correlation-b"));

        assertThat(first.status()).isEqualTo(TestSuiteStabilityJobRecord.Status.CANCELLED);
        assertThat(first.terminal()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(firstFingerprint.get()).matches("sha256:[a-f0-9]{64}");
    }

    @ParameterizedTest
    @MethodSource("mappedConflicts")
    void mapsQueueConflictsToStablePayloadFreeProblems(
            TestSuiteStabilityJobConflictException.Reason reason,
            int status,
            String code) {
        TestSuiteStabilityJobSubmitRequest request = request();
        String requestFingerprint = ProtocolFingerprint.of(mapper, request.execution());
        when(jobs.find(eq("tenant-a"), eq("test"), anyString()))
                .thenReturn(Optional.empty());
        when(executions.authorizeSubmission("suite-a", request.execution(), identity()))
                .thenReturn(descriptor(requestFingerprint));
        when(jobs.submitDetailed(any(), eq(policy))).thenThrow(
                new TestSuiteStabilityJobConflictException(reason, "private store detail"));

        assertProblem(() -> service.submit("suite-a", request, identity()), status, code);
    }

    private static Stream<Arguments> mappedConflicts() {
        return Stream.of(
                Arguments.of(TestSuiteStabilityJobConflictException.Reason.GLOBAL_QUEUE_FULL,
                        429, "RG.TEST.STABILITY_JOB_QUEUE_FULL"),
                Arguments.of(TestSuiteStabilityJobConflictException.Reason.TENANT_QUEUE_FULL,
                        429, "RG.TEST.STABILITY_JOB_TENANT_QUEUE_FULL"),
                Arguments.of(TestSuiteStabilityJobConflictException.Reason.DEADLINE_INVALID,
                        400, "RG.TEST.STABILITY_JOB_DEADLINE_INVALID"),
                Arguments.of(TestSuiteStabilityJobConflictException.Reason.POLICY_DRIFT,
                        503, "RG.TEST.STABILITY_JOB_POLICY_DRIFT"),
                Arguments.of(TestSuiteStabilityJobConflictException.Reason.REPLAY_WINDOW_EXPIRED,
                        410, "RG.TEST.STABILITY_JOB_REPLAY_WINDOW_EXPIRED"));
    }

    private TestSuiteStabilityJobService service(boolean enabled) {
        return new TestSuiteStabilityJobService(
                jobs, executions, policy, mapper, enabled, Duration.ofSeconds(7));
    }

    private String jobId(
            TestSuiteStabilityExecutionRequest execution,
            IntegrationRequestContext identity) {
        String fingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityJobIdentity.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "clientRequestId", execution.clientRequestId()));
        return "stability-job-" + fingerprint.substring("sha256:".length());
    }

    private static TestSuiteStabilityJobSubmitRequest request() {
        return new TestSuiteStabilityJobSubmitRequest(
                TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION,
                new TestSuiteStabilityExecutionRequest(
                        "", SUITE_REF, "stability-request-1", 3,
                        Map.of("pipeline", "nightly")),
                TestSuiteStabilityJobSubmission.Priority.NORMAL, DEADLINE);
    }

    private static TestSuiteStabilityExecutionDescriptor descriptor(
            String requestFingerprint) {
        return new TestSuiteStabilityExecutionDescriptor(
                "stability-" + "a".repeat(64), "tenant-a", "test",
                "stability-request-1", requestFingerprint, "INTERNAL");
    }

    private static TestSuiteStabilityJobSubmission submission(
            String jobId,
            TestSuiteStabilityJobSubmitRequest request,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        return new TestSuiteStabilityJobSubmission(
                jobId, request.execution(), requestFingerprint, "INTERNAL",
                TestSuiteStabilityJobPrincipal.from(identity), request.priority(),
                request.deadlineAt());
    }

    private static TestSuiteStabilityJobRecord queued(
            TestSuiteStabilityJobSubmission submission) {
        Instant createdAt = DEADLINE.minus(Duration.ofHours(1));
        return new TestSuiteStabilityJobRecord(
                submission.jobId(), submission.request(), submission.requestFingerprint(),
                submission.classification(), submission.principal(), submission.priority(),
                TestSuiteStabilityJobRecord.Status.QUEUED, 0, createdAt,
                submission.deadlineAt(), createdAt, createdAt,
                DEADLINE.plus(Duration.ofDays(30)), "", "", "", "", "", sha('f'));
    }

    private static TestSuiteStabilityJobRecord cancelled(
            TestSuiteStabilityJobRecord source,
            String cancellationFingerprint) {
        return new TestSuiteStabilityJobRecord(
                source.jobId(), source.request(), source.requestFingerprint(),
                source.classification(), source.principal(), source.priority(),
                TestSuiteStabilityJobRecord.Status.CANCELLED, source.retryCount(),
                source.nextEligibleAt(), source.deadlineAt(), source.createdAt(),
                source.updatedAt().plusSeconds(1), source.expiresAt(), "", "",
                "RG.TEST.STABILITY_JOB_CANCELLED", "cancel-1", cancellationFingerprint,
                sha('e'));
    }

    private static IntegrationRequestContext identity() {
        return identity("org-a", "project-a", "CONFIDENTIAL", "runner", "correlation-a");
    }

    private static IntegrationRequestContext identity(
            String organization,
            String project,
            String clearance,
            String actor,
            String correlation) {
        return new IntegrationRequestContext(
                "tenant-a", organization, project, "test", "sg-1", "WORKLOAD", actor,
                "", "TEST_EXECUTION", correlation, Set.of("quality"), clearance, "");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().title())
                            .doesNotContain("private store detail")
                            .doesNotContain("org-b")
                            .doesNotContain("project-b");
                    if (status == 429 || code.endsWith("POLICY_DRIFT")
                            || code.endsWith("SUBMISSION_UNAVAILABLE")) {
                        assertThat(failure.problem().details().get("retryAfterSeconds"))
                                .isEqualTo(7L);
                    }
                });
    }

    private static String sha(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
