package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Protected application boundary for durable multi-plan Scenario rehearsal batches.
 *
 * <p>Submission resolves every mutable plan dependency before queue admission and persists only
 * the sealed payload-free manifest plus a credential-free principal snapshot. Scheduling,
 * priority, deadline, retries, and capacity remain server-owned policy and cannot be supplied by
 * callers.</p>
 */
public final class ScenarioRehearsalBatchService {
    private static final Set<String> READ_PURPOSES =
            Set.of(
                    "MIRROR_REHEARSAL",
                    "GOVERNANCE_EVIDENCE_INGESTION");
    private static final int MAXIMUM_REQUEST_BYTES =
            2 * 1024 * 1024;

    private final ScenarioRehearsalBatchCompiler compiler;
    private final ScenarioRehearsalBatchRepository repository;
    private final ScenarioRehearsalBatchPolicy policy;
    private final ObjectMapper mapper;

    /** Creates the protected batch application service under one server-owned policy. */
    public ScenarioRehearsalBatchService(
            ScenarioRehearsalBatchCompiler compiler,
            ScenarioRehearsalBatchRepository repository,
            ScenarioRehearsalBatchPolicy policy,
            ObjectMapper mapper) {
        this.compiler = Objects.requireNonNull(
                compiler, "compiler");
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves, seals, and durably admits one exact batch.
     *
     * @param request strict payload-free ordered plan request
     * @param identity authenticated complete enterprise identity
     * @return newly admitted or exact idempotent replay projection
     */
    public ScenarioRehearsalBatchRepository.SubmissionResult submit(
            ScenarioRehearsalBatchRequest request,
            IntegrationRequestContext identity) {
        requirePurpose(identity, Set.of("MIRROR_REHEARSAL"));
        ScenarioRehearsalBatchManifest manifest =
                compiler.compile(
                        Objects.requireNonNull(request, "request"),
                        identity);
        ScenarioRehearsalBatchManifestIntegrity.verify(
                mapper, manifest);
        String requestFingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        request,
                        MAXIMUM_REQUEST_BYTES);
        try {
            return repository.submit(
                    new ScenarioRehearsalBatchRepository.Submission(
                            request,
                            requestFingerprint,
                            manifest,
                            principal(identity, manifest.scope())),
                    policy);
        } catch (ScenarioRehearsalBatchConflictException conflict) {
            throw problem(conflict, identity);
        }
    }

    /** Finds one job only inside the exact authenticated enterprise scope. */
    public Optional<ScenarioRehearsalBatchJob> find(
            String jobId,
            IntegrationRequestContext identity) {
        requirePurpose(identity, READ_PURPOSES);
        try {
            return repository.find(
                    MirrorPlanIntegrationService
                            .requireMirrorIdentity(identity),
                    jobId,
                    policy);
        } catch (ScenarioRehearsalBatchConflictException conflict) {
            throw problem(conflict, identity);
        }
    }

    /** Reads one stable bounded manifest-index page inside the exact scope. */
    public ScenarioRehearsalBatchItemPage page(
            String jobId,
            int startIndex,
            int limit,
            IntegrationRequestContext identity) {
        requirePurpose(identity, READ_PURPOSES);
        try {
            return repository.page(
                    MirrorPlanIntegrationService
                            .requireMirrorIdentity(identity),
                    jobId,
                    startIndex,
                    limit,
                    policy);
        } catch (ScenarioRehearsalBatchConflictException conflict) {
            throw problem(conflict, identity);
        }
    }

    /** Applies one exactly replayable cooperative cancellation intent. */
    public ScenarioRehearsalBatchRepository.SubmissionResult cancel(
            String jobId,
            String commandId,
            String reasonCode,
            IntegrationRequestContext identity) {
        requirePurpose(identity, Set.of("MIRROR_REHEARSAL"));
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService
                        .requireMirrorIdentity(identity);
        try {
            return repository.cancel(
                    new ScenarioRehearsalBatchRepository.Cancellation(
                            scope,
                            jobId,
                            commandId,
                            reasonCode),
                    policy);
        } catch (ScenarioRehearsalBatchConflictException conflict) {
            throw problem(conflict, identity);
        }
    }

    private static ScenarioRehearsalBatchPrincipal principal(
            IntegrationRequestContext identity,
            CapabilitySnapshot.Scope scope) {
        return new ScenarioRehearsalBatchPrincipal(
                scope,
                identity.actorType(),
                identity.actorId(),
                identity.delegatedBy(),
                identity.groups(),
                identity.clearance(),
                identity.delegationGrantId());
    }

    private static void requirePurpose(
            IntegrationRequestContext identity,
            Set<String> allowed) {
        if (identity == null
                || !allowed.contains(identity.purpose())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.REHEARSAL_BATCH.PURPOSE_REQUIRED",
                            "Scenario batch operation requires an authorized rehearsal purpose.",
                            identity == null
                                    ? ""
                                    : identity.correlationId(),
                            Map.of()));
        }
        MirrorPlanIntegrationService.requireMirrorIdentity(
                identity);
    }

    private static IntegrationProblemException problem(
            ScenarioRehearsalBatchConflictException conflict,
            IntegrationRequestContext identity) {
        String code = "RG.MIRROR.REHEARSAL_BATCH."
                + conflict.reason().name();
        IntegrationProblem problem = switch (conflict.reason()) {
            case GLOBAL_QUEUE_FULL, TENANT_QUEUE_FULL ->
                    IntegrationProblem.tooManyRequests(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of("retryAfterSeconds", 1));
            case JOB_NOT_FOUND ->
                    IntegrationProblem.notFound(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            case POLICY_MISMATCH, LEASE_LOST ->
                    IntegrationProblem.serviceUnavailable(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
            default ->
                    IntegrationProblem.conflict(
                            code,
                            conflict.getMessage(),
                            identity.correlationId(),
                            Map.of());
        };
        return new IntegrationProblemException(problem);
    }
}
