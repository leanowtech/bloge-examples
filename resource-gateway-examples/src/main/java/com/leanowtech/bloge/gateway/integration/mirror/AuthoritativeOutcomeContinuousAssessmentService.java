package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Audited governance boundary for continuous selected-population assessment.
 *
 * <p>Registration first externally reverifies the exact population through the existing
 * application service, then atomically persists the immutable projection intent and success
 * audit. Reads resolve freshness against database time and keep current authority readiness
 * separate. The service is physically assembled only in test/staging profiles.</p>
 */
public final class AuthoritativeOutcomeContinuousAssessmentService {
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeContinuousAssessmentRepository
            repository;
    private final AuthoritativeOutcomeSelectedPopulationApplicationService
            populationService;
    private final AuthoritativeOutcomeSelectedPopulationAccessPolicy
            accessPolicy;
    private final MirrorOperationObservability observability;
    private final TransactionTemplate mutations;

    /**
     * Creates the protected continuous-assessment boundary.
     *
     * @param repository durable projection and database freshness authority
     * @param populationService selected-population authority and assessment readiness boundary
     * @param accessPolicy server-owned governance projector policy
     * @param observability mandatory payload-free operation audit
     * @param transactionManager transaction shared by registration and success audit
     */
    public AuthoritativeOutcomeContinuousAssessmentService(
            AuthoritativeOutcomeContinuousAssessmentRepository
                    repository,
            AuthoritativeOutcomeSelectedPopulationApplicationService
                    populationService,
            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                    accessPolicy,
            MirrorOperationObservability observability,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.populationService = Objects.requireNonNull(
                populationService, "populationService");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy, "accessPolicy");
        this.observability = Objects.requireNonNull(
                observability, "observability");
        mutations = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager,
                        "transactionManager"));
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Registers or exactly replays one immutable continuous projection intent. */
    public AuthoritativeOutcomeContinuousAssessmentAdmission
    register(
            AuthoritativeOutcomeContinuousAssessmentRequest
                    request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext governance =
                requireProjector(identity);
        AuthoritativeOutcomeContinuousAssessmentRequest command =
                Objects.requireNonNull(request, "request");
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_CONTINUOUS_ASSESSMENT_REGISTER,
                        governance,
                        command.projectionId(),
                        command.populationRef().id(),
                        "");
        try {
            AuthoritativeOutcomeSelectedPopulationBundle population =
                    populationService.findPopulation(
                            command.populationRef().id(),
                            command.populationRef().revision(),
                            governance);
            if (!population.manifest()
                    .artifactRef().equals(
                            command.populationRef())) {
                throw notFound(governance);
            }
            AuthoritativeOutcomeContinuousAssessmentRepository
                    .Admission admitted = required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeContinuousAssessmentRepository
                                .Admission value =
                                repository.register(
                                        scope(governance),
                                        command);
                        audit.succeeded(
                                value.projection()
                                        .recordFingerprint());
                        return value;
                    }),
                    "continuous assessment registration");
            AuthoritativeOutcomeContinuousAssessmentRepository
                    .ObservedProjection observed =
                    new AuthoritativeOutcomeContinuousAssessmentRepository
                            .ObservedProjection(
                            admitted.projection(),
                            admitted.observedAt());
            return new AuthoritativeOutcomeContinuousAssessmentAdmission(
                    "",
                    status(observed),
                    admitted.idempotentReplay());
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, governance));
        }
    }

    /** Reads one effective status in the authenticated enterprise scope. */
    public AuthoritativeOutcomeContinuousAssessmentStatus find(
            String projectionId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReader(identity);
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_CONTINUOUS_ASSESSMENT_READ,
                        reader,
                        projectionId,
                        "",
                        "");
        try {
            AuthoritativeOutcomeContinuousAssessmentRepository
                    .ObservedProjection observed =
                    repository.find(
                            scope(reader),
                            projectionId)
                            .orElseThrow(() ->
                                    notFound(reader));
            succeedOnly(
                    audit,
                    observed.projection()
                            .recordFingerprint());
            return status(observed);
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads one bounded hash-chained lifecycle page in the authenticated enterprise scope. */
    public AuthoritativeOutcomeContinuousAssessmentLifecyclePage
    lifecycle(
            String projectionId,
            long afterOrdinal,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReader(identity);
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_READ,
                        reader,
                        projectionId,
                        "",
                        "");
        try {
            AuthoritativeOutcomeContinuousAssessmentLifecyclePage
                    page = repository.lifecycle(
                    scope(reader),
                    projectionId,
                    afterOrdinal,
                    limit);
            String auditCoordinate = page.events().isEmpty()
                    ? page.predecessorFingerprint()
                    : page.events().getLast()
                    .eventFingerprint();
            audit.succeeded(
                    auditCoordinate.isBlank()
                            ? page.projectionId()
                            : auditCoordinate);
            return page;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** @return whether all authorities and the assessment signer are currently usable */
    public boolean authoritiesReady() {
        try {
            return populationService.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private AuthoritativeOutcomeContinuousAssessmentStatus status(
            AuthoritativeOutcomeContinuousAssessmentRepository
                    .ObservedProjection observed) {
        return AuthoritativeOutcomeContinuousAssessmentStatus
                .from(
                        observed,
                        authoritiesReady());
    }

    private IntegrationRequestContext requireProjector(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                requireIdentity(identity);
        if (!AuthoritativeOutcomeSelectedPopulationAccessPolicy
                .ASSESSMENT_PURPOSE.equals(
                        exact.purpose())
                || !accessPolicy.mayAssess(exact)) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_PROJECTOR_FORBIDDEN",
                    "The workload is not an authorized continuous assessment projector.");
        }
        return exact;
    }

    private static IntegrationRequestContext requireReader(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                requireIdentity(identity);
        if (!AuthoritativeOutcomeSelectedPopulationApplicationService
                .READ_PURPOSES.contains(
                        exact.purpose())) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_READ_FORBIDDEN",
                    "The authenticated purpose cannot read continuous assessment status.");
        }
        return exact;
    }

    private static IntegrationRequestContext requireIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(
                        identity, "identity");
        exact.requireComplete();
        if (RESERVED_PRODUCTION_ENVIRONMENTS.contains(
                exact.environmentId()
                        .trim()
                        .toLowerCase(Locale.ROOT))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_ENVIRONMENT_FORBIDDEN",
                    "Continuous assessment cannot serve a reserved production scope.");
        }
        return exact;
    }

    private void succeedOnly(
            MirrorOperationObservability.Observation audit,
            String fingerprint) {
        required(
                mutations.execute(ignored -> {
                    audit.succeeded(fingerprint);
                    return Boolean.TRUE;
                }),
                "continuous assessment success audit");
    }

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure
                instanceof AuthoritativeOutcomeContinuousAssessmentRepository
                .Violation violation) {
            return switch (violation.reason()) {
                case PROJECTION_NOT_FOUND ->
                        notFound(identity);
                case LIFECYCLE_CURSOR_INVALID ->
                        new IntegrationProblemException(
                                IntegrationProblem.badRequest(
                                        "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_LIFECYCLE_CURSOR_INVALID",
                                        "The continuous assessment lifecycle cursor is invalid.",
                                        identity.correlationId(),
                                        Map.of()));
                case STORED_STATE_CORRUPT ->
                        unavailable(identity);
                default ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_CONFLICT",
                                        "The continuous assessment conflicts with immutable or fenced state.",
                                        identity.correlationId(),
                                        Map.of()));
            };
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_INVALID",
                            "The continuous assessment violates the governed protocol.",
                            identity.correlationId(),
                            Map.of()));
        }
        return unavailable(identity);
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
                        "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_NOT_FOUND",
                        "The continuous assessment was not found in the authenticated scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_UNAVAILABLE",
                        "Continuous assessment coordination is temporarily unavailable.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException forbidden(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.forbidden(
                        code,
                        title,
                        identity.correlationId(),
                        Map.of()));
    }

    private static <T> T required(
            T value, String operation) {
        return Objects.requireNonNull(
                value, operation + " returned null");
    }
}
