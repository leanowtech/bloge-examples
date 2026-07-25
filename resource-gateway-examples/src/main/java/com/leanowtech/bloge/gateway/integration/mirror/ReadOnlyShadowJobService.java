package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Protected application boundary for durable read-only Shadow admission and evidence reads.
 *
 * <p>Submission scope is minted by authenticated identity rather than trusted from JSON. The
 * queue admission, first lifecycle fact, and mandatory successful operation audit share one
 * transaction. Reads stay exact-scope and reverify every stored projection before publication.</p>
 */
public class ReadOnlyShadowJobService {
    /** Dedicated purpose required to reserve samples and execute Shadow work. */
    public static final String EXECUTION_PURPOSE =
            "MIRROR_SHADOW";
    /** Cross-system purpose permitted to read payload-free evidence and lifecycle facts. */
    public static final String EVIDENCE_PURPOSE =
            "GOVERNANCE_EVIDENCE_INGESTION";
    /** Largest public lifecycle page. */
    public static final int MAXIMUM_LIFECYCLE_PAGE =
            1_000;
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final ReadOnlyShadowJobRepository repository;
    private final ReadOnlyShadowJobPolicy policy;
    private final MirrorOperationObservability observability;

    /**
     * Creates the protected Shadow application boundary.
     *
     * @param repository database-authoritative queue and evidence store
     * @param policy server-owned admission, lease, retry, and deadline policy
     * @param observability mandatory operation audit and fixed-cardinality telemetry
     */
    public ReadOnlyShadowJobService(
            ReadOnlyShadowJobRepository repository,
            ReadOnlyShadowJobPolicy policy,
            MirrorOperationObservability observability) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.policy = Objects.requireNonNull(
                policy, "policy");
        this.observability = Objects.requireNonNull(
                observability, "observability");
    }

    /**
     * Reserves one exact sampling ordinal and durably admits its immutable command.
     *
     * @param request strict payload-free command
     * @param identity authenticated execution identity
     * @return new admission or exact idempotent replay
     */
    @Transactional
    public ReadOnlyShadowJobRepository.Submission submit(
            ReadOnlyShadowJobRequest request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireIdentity(
                        identity,
                        Set.of(EXECUTION_PURPOSE));
        ReadOnlyShadowJobRequest command =
                Objects.requireNonNull(
                        request, "request");
        MirrorOperationObservability.Observation observation =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .SHADOW_JOB_CREATE,
                        exactIdentity,
                        command.requestId(),
                        command.candidatePlanRef().id(),
                        "");
        try {
            requireScope(
                    command.scope(),
                    exactIdentity);
            ReadOnlyShadowJobRepository.Submission
                    admitted = repository.submit(
                    command, policy);
            observation.succeeded(
                    admitted.job().jobId());
            return admitted;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(
                            failure, exactIdentity));
        }
    }

    /** Reads one integrity-verified public job projection in exact authenticated scope. */
    @Transactional
    public ReadOnlyShadowJob find(
            String jobId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SHADOW_JOB_READ,
                        exactIdentity,
                        jobId);
        try {
            ReadOnlyShadowJob value =
                    repository.find(
                            scope(exactIdentity),
                            jobId)
                            .orElseThrow(() ->
                                    notFound(
                                            exactIdentity));
            observation.succeeded(value.jobId());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(
                            failure, exactIdentity));
        }
    }

    /** Reads the immutable request required for independent job verification. */
    @Transactional
    public ReadOnlyShadowJobRequest findRequest(
            String jobId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SHADOW_JOB_READ,
                        exactIdentity,
                        jobId);
        try {
            ReadOnlyShadowJobRequest value =
                    repository.findRequest(
                            scope(exactIdentity),
                            jobId)
                            .orElseThrow(() ->
                                    notFound(
                                            exactIdentity));
            observation.succeeded(jobId);
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(
                            failure, exactIdentity));
        }
    }

    /** Reads one independently reverified terminal signed comparison. */
    @Transactional
    public ReadOnlyShadowComparison findComparison(
            String jobId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SHADOW_COMPARISON_READ,
                        exactIdentity,
                        jobId);
        try {
            ReadOnlyShadowComparison value =
                    repository.findComparison(
                            scope(exactIdentity),
                            jobId)
                            .orElseThrow(() ->
                                    new IntegrationProblemException(
                                            IntegrationProblem
                                                    .notFound(
                                                            "RG.MIRROR.SHADOW.COMPARISON_NOT_FOUND",
                                                            "The signed read-only Shadow comparison was not found.",
                                                            exactIdentity.correlationId(),
                                                            Map.of())));
            observation.succeeded(
                    value.comparisonFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(
                            failure, exactIdentity));
        }
    }

    /** Reads one bounded append-ordered lifecycle page in exact authenticated scope. */
    @Transactional
    public ReadOnlyShadowJobLifecyclePage lifecycle(
            String jobId,
            long afterSequence,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext exactIdentity =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation observation =
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SHADOW_LIFECYCLE_READ,
                        exactIdentity,
                        jobId);
        try {
            if (afterSequence < 0
                    || limit < 1
                    || limit
                    > MAXIMUM_LIFECYCLE_PAGE) {
                throw new IllegalArgumentException(
                        "lifecycle cursor or limit is invalid");
            }
            CapabilitySnapshot.Scope scope =
                    scope(exactIdentity);
            if (repository.find(scope, jobId)
                    .isEmpty()) {
                throw notFound(exactIdentity);
            }
            List<ReadOnlyShadowJobLifecycleEvent>
                    fetched = repository.lifecycle(
                    scope,
                    jobId,
                    afterSequence,
                    limit + 1);
            boolean hasMore = fetched.size() > limit;
            List<ReadOnlyShadowJobLifecycleEvent>
                    events = hasMore
                    ? new ArrayList<>(
                    fetched.subList(0, limit))
                    : List.copyOf(fetched);
            long nextSequence = events.isEmpty()
                    ? afterSequence
                    : events.getLast().sequence();
            ReadOnlyShadowJobLifecyclePage page =
                    new ReadOnlyShadowJobLifecyclePage(
                            ReadOnlyShadowJobLifecyclePage
                                    .SCHEMA_VERSION,
                            jobId,
                            afterSequence,
                            nextSequence,
                            hasMore,
                            events);
            observation.succeeded(jobId);
            return page;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    mapFailure(
                            failure, exactIdentity));
        }
    }

    private MirrorOperationObservability.Observation
    observation(
            MirrorOperationAuditEvent.Operation operation,
            IntegrationRequestContext identity,
            String jobId) {
        return observability.start(
                operation,
                identity,
                "",
                "",
                jobId);
    }

    private static IntegrationRequestContext
    requireReadIdentity(
            IntegrationRequestContext identity) {
        return requireIdentity(
                identity,
                Set.of(
                        EXECUTION_PURPOSE,
                        EVIDENCE_PURPOSE));
    }

    private static IntegrationRequestContext requireIdentity(
            IntegrationRequestContext identity,
            Set<String> purposes) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(
                        identity, "identity");
        exact.requireComplete();
        if (!purposes.contains(exact.purpose())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.SHADOW.PURPOSE_FORBIDDEN",
                            "The authenticated purpose cannot perform this read-only Shadow operation.",
                            exact.correlationId(),
                            Map.of()));
        }
        if (RESERVED_PRODUCTION_ENVIRONMENTS
                .contains(
                        exact.environmentId()
                                .trim()
                                .toLowerCase(
                                        java.util.Locale.ROOT))) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.SHADOW.ENVIRONMENT_FORBIDDEN",
                            "The read-only Shadow control plane cannot serve a reserved production scope.",
                            exact.correlationId(),
                            Map.of()));
        }
        return exact;
    }

    private static void requireScope(
            CapabilitySnapshot.Scope requested,
            IntegrationRequestContext identity) {
        if (!scope(identity).equals(requested)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.notFound(
                            "RG.MIRROR.SHADOW.SCOPE_NOT_FOUND",
                            "The read-only Shadow scope was not found in the authenticated namespace.",
                            identity.correlationId(),
                            Map.of()));
        }
    }

    private static CapabilitySnapshot.Scope scope(
            IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region());
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.notFound(
                        "RG.MIRROR.SHADOW.JOB_NOT_FOUND",
                        "The read-only Shadow job was not found in the authenticated scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure
                instanceof ReadOnlyShadowJobRepository
                .Violation violation) {
            return switch (violation.reason()) {
                case REQUEST_CONFLICT,
                     SAMPLE_ORDINAL_CONFLICT,
                     LEASE_LOST ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.SHADOW.IMMUTABLE_CONFLICT",
                                        "The Shadow request, sample ordinal, or lease conflicts with committed state.",
                                        identity.correlationId(),
                                        Map.of()));
                case DEADLINE_INVALID,
                     COMPARISON_MISMATCH ->
                        new IntegrationProblemException(
                                IntegrationProblem.badRequest(
                                        "RG.MIRROR.SHADOW.INVALID",
                                        "The read-only Shadow command violates governed admission policy.",
                                        identity.correlationId(),
                                        Map.of()));
                case JOB_NOT_FOUND ->
                        notFound(identity);
                case STORED_STATE_CORRUPT ->
                        unavailable(identity);
            };
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.SHADOW.INVALID",
                            "The read-only Shadow request violates the governed protocol.",
                            identity.correlationId(),
                            Map.of()));
        }
        return unavailable(identity);
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.SHADOW.UNAVAILABLE",
                        "The read-only Shadow control plane is temporarily unavailable.",
                        identity.correlationId(),
                        Map.of()));
    }
}
