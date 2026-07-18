package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Authenticated application boundary for durable asynchronous suite-stability jobs.
 *
 * <p>Retained replay is resolved before the mutable suite registry is reread. Fresh submission is
 * admitted only while the background runtime is explicitly enabled, but query and cancellation
 * remain available during drain or maintenance. Every repository lookup is scoped by verified
 * tenant and environment and then checked against organization and project before a public view is
 * produced.</p>
 */
public final class TestSuiteStabilityJobService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> EXECUTION_PURPOSES = Set.of("TEST_EXECUTION", "TEST_REPLAY");
    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final int MAX_RETRY_AFTER_SECONDS = 3_600;

    private final TestSuiteStabilityJobRepository jobs;
    private final TestSuiteStabilityExecutionService executions;
    private final TestSuiteStabilityQueuePolicy policy;
    private final ObjectMapper objectMapper;
    private final TestSecurityEventRepository securityEvents;
    private final boolean submissionEnabled;
    private final BooleanSupplier submissionReady;
    private final long retryAfterSeconds;

    /**
     * Creates the public asynchronous job boundary.
     *
     * @param jobs database-authoritative queue and lifecycle store
     * @param executions shared suite/current-authority validator
     * @param policy exact cross-replica queue policy
     * @param objectMapper canonical protocol fingerprint mapper
     * @param securityEvents transaction-bindable semantic security-event store
     * @param submissionEnabled whether a worker runtime is assembled for fresh work
     * @param retryAfter bounded caller retry hint for capacity or disabled submission
     */
    public TestSuiteStabilityJobService(
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityExecutionService executions,
            TestSuiteStabilityQueuePolicy policy,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            boolean submissionEnabled,
            Duration retryAfter) {
        this(jobs, executions, policy, objectMapper, securityEvents, submissionEnabled,
                () -> submissionEnabled, retryAfter);
    }

    /**
     * Creates the public boundary with a dynamic local current-authority readiness guard.
     *
     * <p>The supplier is evaluated only for a fresh request after retained replay lookup. It must
     * not call the external PDP; it reports local provider/trust readiness such as key expiry.</p>
     *
     * @param jobs database-authoritative queue and lifecycle store
     * @param executions shared suite/current-authority validator
     * @param policy exact cross-replica queue policy
     * @param objectMapper canonical protocol fingerprint mapper
     * @param securityEvents transaction-bindable semantic security-event store
     * @param submissionEnabled whether a worker runtime is assembled for fresh work
     * @param submissionReady local non-network readiness of the exact current-authority provider
     * @param retryAfter bounded caller retry hint for capacity or disabled submission
     */
    public TestSuiteStabilityJobService(
            TestSuiteStabilityJobRepository jobs,
            TestSuiteStabilityExecutionService executions,
            TestSuiteStabilityQueuePolicy policy,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            boolean submissionEnabled,
            BooleanSupplier submissionReady,
            Duration retryAfter) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.submissionEnabled = submissionEnabled;
        this.submissionReady = Objects.requireNonNull(submissionReady, "submissionReady");
        Duration boundedRetry = Objects.requireNonNull(retryAfter, "retryAfter");
        if (boundedRetry.toMillis() % 1_000 != 0 || boundedRetry.isZero()
                || boundedRetry.isNegative()
                || boundedRetry.toSeconds() > MAX_RETRY_AFTER_SECONDS) {
            throw new IllegalArgumentException(
                    "stability-job retryAfter must be whole seconds between 1 and 3600");
        }
        this.retryAfterSeconds = boundedRetry.toSeconds();
    }

    /**
     * Admits or exactly replays one non-blocking stability job.
     *
     * @param suiteId path-bound immutable suite identity
     * @param request versioned queue command
     * @param identity verified non-production workload identity
     * @return durable payload-free job and replay disposition
     */
    public TestSuiteStabilityJobSubmitResponse submit(
            String suiteId,
            TestSuiteStabilityJobSubmitRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateSubmissionEnvelope(suiteId, request, identity);
        TestSuiteStabilityExecutionRequest execution = request.execution();
        String requestFingerprint = fingerprint(execution, identity,
                "RG.TEST.STABILITY_JOB_REQUEST_INVALID");
        String jobId = jobId(identity, execution.clientRequestId());
        Optional<TestSuiteStabilityJobRecord> retained = findRetained(jobId, identity);
        if (retained.isPresent()) {
            TestSuiteStabilityJobRecord existing = requireVisible(retained.get(), identity);
            requireSameIntent(existing, jobId, request, requestFingerprint, identity);
            return response(existing, true, identity);
        }
        if (!submissionEnabled || !currentAuthorityReady()) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_SUBMISSION_UNAVAILABLE",
                    "Asynchronous stability-job submission is disabled on this deployment.",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        }

        TestSuiteStabilityExecutionDescriptor authorized =
                executions.authorizeSubmission(suiteId, execution, identity);
        if (!requestFingerprint.equals(authorized.requestFingerprint())) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_AUTHORITY_CONFLICT",
                    "The suite-stability authority returned a contradictory request identity.",
                    Map.of());
        }
        TestSuiteStabilityJobSubmission submission = new TestSuiteStabilityJobSubmission(
                jobId, execution, requestFingerprint, authorized.classification(),
                TestSuiteStabilityJobPrincipal.from(identity), request.priority(),
                request.deadlineAt());
        try {
            TestSuiteStabilityJobRepository.SubmissionResult admitted =
                    jobs.submitDetailed(submission, policy);
            TestSuiteStabilityJobRecord job = requireVisible(admitted.job(), identity);
            requireSameIntent(job, jobId, request, requestFingerprint, identity);
            return response(job, admitted.idempotentReplay(), identity);
        } catch (TestSuiteStabilityJobConflictException conflict) {
            throw mapConflict(conflict, identity);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_STORE_UNAVAILABLE",
                    "The durable suite-stability job store is unavailable.", Map.of());
        }
    }

    private boolean currentAuthorityReady() {
        try {
            return submissionReady.getAsBoolean();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Resolves one retained job inside the authenticated organization and project scope.
     *
     * @param jobId deterministic queue identity
     * @param identity verified non-production workload identity
     * @return payload-free job lifecycle
     */
    public TestSuiteStabilityJobView find(
            String jobId,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String exactJobId = requireJobId(jobId, identity);
        TestSuiteStabilityJobRecord job = findRetained(exactJobId, identity)
                .map(value -> requireVisible(value, identity))
                .orElseThrow(() -> notFound(identity));
        return view(job, identity);
    }

    /**
     * Requests immediate queued cancellation or cooperative running cancellation.
     *
     * <p>Cancellation after {@code COMMITTING} is deliberately too late and returns that retained
     * state. Replaying the same actor-bound command is idempotent; reusing its id for another
     * command fails with a stable conflict.</p>
     *
     * @param jobId deterministic queue identity
     * @param request versioned cancellation command
     * @param identity verified non-production workload identity
     * @return resulting payload-free job lifecycle
     */
    public TestSuiteStabilityJobView cancel(
            String jobId,
            TestSuiteStabilityJobCancelRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String exactJobId = requireJobId(jobId, identity);
        validateCancellation(request, identity);
        TestSuiteStabilityJobRecord existing = findRetained(exactJobId, identity)
                .map(value -> requireVisible(value, identity))
                .orElseThrow(() -> notFound(identity));
        String commandFingerprint = fingerprint(Map.of(
                "schemaVersion", TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION,
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "jobId", exactJobId,
                "clientRequestId", request.clientRequestId(),
                "actorId", identity.actorId(),
                "delegatedBy", identity.delegatedBy(),
                "delegationGrantId", identity.delegationGrantId(),
                "purpose", identity.purpose()), identity,
                "RG.TEST.STABILITY_JOB_CANCELLATION_INVALID");
        try {
            TestSuiteStabilityJobCancellationCommand command =
                    new TestSuiteStabilityJobCancellationCommand(
                            identity.tenantId(), identity.environmentId(), exactJobId,
                            request.clientRequestId(), commandFingerprint,
                            TestSuiteStabilityJobPrincipal.from(identity));
            TestSuiteStabilityJobRecord cancelled = jobs.cancel(
                    command, policy,
                    receipt -> securityEvents.boundAppend(
                            receipt.toSecurityEvent(objectMapper))).job();
            requireSameStoredIdentity(existing, cancelled, identity);
            return view(requireVisible(cancelled, identity), identity);
        } catch (TestSuiteStabilityJobConflictException conflict) {
            throw mapConflict(conflict, identity);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_STORE_UNAVAILABLE",
                    "The durable suite-stability job store is unavailable.", Map.of());
        }
    }

    private void validateSubmissionEnvelope(
            String suiteId,
            TestSuiteStabilityJobSubmitRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestSuiteStabilityJobSubmitRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || request.execution() == null
                || request.execution().suiteRef() == null
                || normalized(suiteId).isBlank()
                || !normalized(suiteId).equals(request.execution().suiteRef().suiteId())
                || request.priority() == null
                || request.deadlineAt() == null
                || request.deadlineAt().getNano() != 0) {
            throw badRequest(identity, "RG.TEST.STABILITY_JOB_REQUEST_INVALID",
                    "A versioned exact execution, priority, and whole-second deadline are required.");
        }
        if (!IDENTIFIER.matcher(request.execution().clientRequestId()).matches()) {
            throw badRequest(identity, "RG.TEST.STABILITY_JOB_REQUEST_INVALID",
                    "The stability execution requires a bounded clientRequestId.");
        }
    }

    private static void validateCancellation(
            TestSuiteStabilityJobCancelRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestSuiteStabilityJobCancelRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !IDENTIFIER.matcher(request.clientRequestId()).matches()) {
            throw badRequest(identity, "RG.TEST.STABILITY_JOB_CANCELLATION_INVALID",
                    "A versioned bounded cancellation clientRequestId is required.");
        }
    }

    private Optional<TestSuiteStabilityJobRecord> findRetained(
            String jobId,
            IntegrationRequestContext identity) {
        try {
            return jobs.find(identity.tenantId(), identity.environmentId(), jobId);
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_STORE_UNAVAILABLE",
                    "The durable suite-stability job store is unavailable.", Map.of());
        }
    }

    private static TestSuiteStabilityJobRecord requireVisible(
            TestSuiteStabilityJobRecord job,
            IntegrationRequestContext identity) {
        TestSuiteStabilityJobPrincipal principal = job.principal();
        if (!principal.tenantId().equals(identity.tenantId())
                || !principal.environmentId().equals(identity.environmentId())
                || !principal.organizationId().equals(identity.organizationId())
                || !principal.projectId().equals(identity.projectId())) {
            throw notFound(identity);
        }
        if (!identity.hasClearanceAtLeast(job.classification())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_JOB_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot access this stability job.",
                    identity.correlationId(), Map.of()));
        }
        return job;
    }

    private static void requireSameIntent(
            TestSuiteStabilityJobRecord existing,
            String expectedJobId,
            TestSuiteStabilityJobSubmitRequest request,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        if (!existing.jobId().equals(expectedJobId)
                || !existing.requestFingerprint().equals(requestFingerprint)
                || !existing.request().equals(request.execution())
                || existing.priority() != request.priority()
                || !existing.deadlineAt().equals(request.deadlineAt())
                || !sameStableAuthority(existing.principal(), identity)) {
            throw conflict(identity, "RG.TEST.STABILITY_JOB_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies another stability-job intent.");
        }
    }

    private static boolean sameStableAuthority(
            TestSuiteStabilityJobPrincipal retained,
            IntegrationRequestContext identity) {
        TestSuiteStabilityJobPrincipal current = TestSuiteStabilityJobPrincipal.from(identity);
        return retained.tenantId().equals(current.tenantId())
                && retained.organizationId().equals(current.organizationId())
                && retained.projectId().equals(current.projectId())
                && retained.environmentId().equals(current.environmentId())
                && retained.region().equals(current.region())
                && retained.actorType().equals(current.actorType())
                && retained.actorId().equals(current.actorId())
                && retained.delegatedBy().equals(current.delegatedBy())
                && retained.purpose().equals(current.purpose())
                && retained.groups().equals(current.groups())
                && retained.clearance().equals(current.clearance())
                && retained.delegationGrantId().equals(current.delegationGrantId());
    }

    private static void requireSameStoredIdentity(
            TestSuiteStabilityJobRecord before,
            TestSuiteStabilityJobRecord after,
            IntegrationRequestContext identity) {
        if (!before.jobId().equals(after.jobId())
                || !before.requestFingerprint().equals(after.requestFingerprint())
                || !before.principal().equals(after.principal())) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_STORE_CONFLICT",
                    "The stability-job store returned a contradictory lifecycle identity.",
                    Map.of());
        }
    }

    private String jobId(IntegrationRequestContext identity, String clientRequestId) {
        String fingerprint = fingerprint(Map.of(
                "schemaVersion", "bloge.testSuiteStabilityJobIdentity.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "clientRequestId", clientRequestId), identity,
                "RG.TEST.STABILITY_JOB_REQUEST_INVALID");
        return "stability-job-" + fingerprint.substring("sha256:".length());
    }

    private String fingerprint(
            Object value,
            IntegrationRequestContext identity,
            String failureCode) {
        try {
            return ProtocolFingerprint.of(objectMapper, value);
        } catch (RuntimeException invalid) {
            throw badRequest(identity, failureCode,
                    "The stability-job command cannot be canonicalized.");
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!EXECUTION_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_JOB_PURPOSE_FORBIDDEN",
                    "Stability jobs require TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Stability jobs are restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static String requireJobId(
            String jobId,
            IntegrationRequestContext identity) {
        String normalized = normalized(jobId);
        if (!JOB_ID.matcher(normalized).matches()) {
            throw badRequest(identity, "RG.TEST.STABILITY_JOB_ID_INVALID",
                    "A deterministic suite-stability job id is required.");
        }
        return normalized;
    }

    private static TestSuiteStabilityJobSubmitResponse response(
            TestSuiteStabilityJobRecord job,
            boolean replay,
            IntegrationRequestContext identity) {
        return new TestSuiteStabilityJobSubmitResponse("", view(job, identity), replay);
    }

    private static TestSuiteStabilityJobView view(
            TestSuiteStabilityJobRecord job,
            IntegrationRequestContext identity) {
        try {
            return TestSuiteStabilityJobView.from(job);
        } catch (RuntimeException invalid) {
            throw unavailable(identity, "RG.TEST.STABILITY_JOB_STORE_CONFLICT",
                    "The retained stability-job lifecycle is not protocol-valid.", Map.of());
        }
    }

    private IntegrationProblemException mapConflict(
            TestSuiteStabilityJobConflictException conflict,
            IntegrationRequestContext identity) {
        return switch (conflict.reason()) {
            case GLOBAL_QUEUE_FULL -> capacity(identity,
                    "RG.TEST.STABILITY_JOB_QUEUE_FULL",
                    "The stability-job queue has reached its configured capacity.");
            case TENANT_QUEUE_FULL -> capacity(identity,
                    "RG.TEST.STABILITY_JOB_TENANT_QUEUE_FULL",
                    "The authorized tenant stability-job queue is at capacity.");
            case DEADLINE_INVALID -> badRequest(identity,
                    "RG.TEST.STABILITY_JOB_DEADLINE_INVALID",
                    "The deadline is outside the server-owned accepted horizon.");
            case REPLAY_WINDOW_EXPIRED -> new IntegrationProblemException(
                    IntegrationProblem.gone(
                            "RG.TEST.STABILITY_JOB_REPLAY_WINDOW_EXPIRED",
                            "The retained detail for this idempotency identity has expired.",
                            identity.correlationId(), Map.of()));
            case POLICY_DRIFT -> unavailable(identity,
                    "RG.TEST.STABILITY_JOB_POLICY_DRIFT",
                    "Queue replicas have not converged on one active policy generation.",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
            case NOT_FOUND -> notFound(identity);
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.STABILITY_JOB_IDEMPOTENCY_CONFLICT",
                    "The idempotency identity already represents another job intent.");
            case CANCELLATION_CONFLICT -> conflict(identity,
                    "RG.TEST.STABILITY_JOB_CANCELLATION_CONFLICT",
                    "The cancellation identity already represents another command.");
            case TERMINAL_CONFLICT -> conflict(identity,
                    "RG.TEST.STABILITY_JOB_TERMINAL_CONFLICT",
                    "The requested transition contradicts the retained terminal lifecycle.");
            case LEASE_LOST -> unavailable(identity,
                    "RG.TEST.STABILITY_JOB_STORE_CONFLICT",
                    "The stability-job lifecycle changed during the requested operation.",
                    Map.of("retryAfterSeconds", retryAfterSeconds));
        };
    }

    private IntegrationProblemException capacity(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.tooManyRequests(
                code, title, identity.correlationId(),
                Map.of("retryAfterSeconds", retryAfterSeconds)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.TEST.STABILITY_JOB_NOT_FOUND",
                "Stability job was not found in the authorized scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), details));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
