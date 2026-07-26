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
 * Audited selection-authority boundary for resumable selected-population uploads.
 *
 * <p>Chunk staging never calls an external authority. Finalization first acquires a database
 * fencing lease, then invokes the existing complete-population application service outside the
 * staging transaction. A lost response or crashed finalizer is recovered by lease takeover and
 * the idempotent population admission contract.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationUploadService {
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeSelectedPopulationUploadRepository
            repository;
    private final AuthoritativeOutcomeSelectedPopulationApplicationService
            populationService;
    private final AuthoritativeOutcomeSelectedPopulationAccessPolicy
            accessPolicy;
    private final MirrorOperationObservability observability;
    private final TransactionTemplate mutations;

    /**
     * Creates the protected resumable-upload application boundary.
     *
     * @param repository durable staging and finalizer fencing
     * @param populationService existing complete-population admission boundary
     * @param accessPolicy server-owned selection-authority policy
     * @param observability mandatory payload-free operation audit
     * @param transactionManager transaction shared by staging mutation and success audit
     */
    public AuthoritativeOutcomeSelectedPopulationUploadService(
            AuthoritativeOutcomeSelectedPopulationUploadRepository
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

    /** Creates or exactly replays one immutable upload intent. */
    public AuthoritativeOutcomeSelectedPopulationUploadRepository.Admission
    begin(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireAuthority(identity);
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                command = Objects.requireNonNull(
                request, "request");
        MirrorOperationObservability.Observation audit =
                audit(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_UPLOAD_BEGIN,
                        authority,
                        command.uploadId(),
                        command.manifest()
                                .populationId());
        try {
            requireScope(
                    command.manifest().scope(),
                    authority);
            return required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Admission admission =
                                repository.begin(command);
                        audit.succeeded(
                                admission.status()
                                        .requestFingerprint());
                        return admission;
                    }),
                    "upload begin");
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Stages or exactly replays one manifest-declared chunk. */
    public AuthoritativeOutcomeSelectedPopulationUploadRepository.ChunkAdmission
    stageChunk(
            String uploadId,
            int chunkIndex,
            AuthoritativeOutcomeSelectedPopulationChunk chunk,
            long encodedBytes,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireAuthority(identity);
        MirrorOperationObservability.Observation audit =
                audit(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_UPLOAD_CHUNK,
                        authority,
                        uploadId,
                        "");
        try {
            AuthoritativeOutcomeSelectedPopulationChunk exact =
                    Objects.requireNonNull(
                            chunk, "chunk");
            requireScope(
                    exact.scope(), authority);
            return required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .ChunkAdmission admission =
                                repository.stageChunk(
                                        scope(authority),
                                        uploadId,
                                        chunkIndex,
                                        exact,
                                        encodedBytes);
                        audit.succeeded(
                                admission
                                        .chunkFingerprint());
                        return admission;
                    }),
                    "upload chunk staging");
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Reads one payload-free upload status in the authenticated scope. */
    public AuthoritativeOutcomeSelectedPopulationUploadRepository.Upload
    find(
            String uploadId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireAuthority(identity);
        MirrorOperationObservability.Observation audit =
                audit(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_UPLOAD_READ,
                        authority,
                        uploadId,
                        "");
        try {
            AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .Upload upload = repository.find(
                    scope(authority),
                    uploadId).orElseThrow(() ->
                    notFound(authority));
            succeedOnly(
                    audit,
                    upload.status()
                            .requestFingerprint());
            return upload;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /**
     * Finalizes one complete upload through the existing population admission service.
     *
     * @return first or exactly replayed terminal population admission
     */
    public AuthoritativeOutcomeSelectedPopulationAdmission
    finalizeUpload(
            String uploadId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireAuthority(identity);
        MirrorOperationObservability.Observation audit =
                audit(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_UPLOAD_FINALIZE,
                        authority,
                        uploadId,
                        "");
        try {
            AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .FinalizationClaim claim =
                    repository.claimFinalize(
                            scope(authority),
                            uploadId,
                            authority.actorId());
            if (!claim.requiresExecution()) {
                AuthoritativeOutcomeSelectedPopulationAdmission
                        terminal = claim.upload()
                        .admission().orElseThrow();
                succeedOnly(
                        audit,
                        terminal.population()
                                .manifest()
                                .manifestFingerprint());
                return terminal;
            }
            AuthoritativeOutcomeSelectedPopulationAdmission
                    admitted =
                    populationService.ingestPopulation(
                            claim.command(),
                            authority);
            AuthoritativeOutcomeSelectedPopulationUploadRepository
                    .Upload completed = required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Upload value =
                                repository.completeFinalize(
                                        claim, admitted);
                        audit.succeeded(
                                value.status()
                                        .finalizedPopulationFingerprint());
                        return value;
                    }),
                    "upload finalization");
            return completed.admission()
                    .orElseThrow();
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Aborts one open upload and destroys its staging chunks. */
    public AuthoritativeOutcomeSelectedPopulationUploadRepository.Upload
    abort(
            String uploadId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireAuthority(identity);
        MirrorOperationObservability.Observation audit =
                audit(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_UPLOAD_ABORT,
                        authority,
                        uploadId,
                        "");
        try {
            return required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationUploadRepository
                                .Upload upload =
                                repository.abort(
                                        scope(authority),
                                        uploadId);
                        audit.succeeded(
                                upload.status()
                                        .requestFingerprint());
                        return upload;
                    }),
                    "upload abort");
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Performs one bounded expiry and terminal-retention cleanup turn. */
    public int cleanup(int limit) {
        return repository.expireAndPurge(limit);
    }

    private IntegrationRequestContext requireAuthority(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(
                        identity, "identity");
        exact.requireComplete();
        if (!AuthoritativeOutcomeSelectedPopulationAccessPolicy
                .SELECTION_PURPOSE.equals(
                        exact.purpose())
                || !accessPolicy.mayRegister(exact)) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_AUTHORITY_FORBIDDEN",
                    "The workload is not an authorized outcome selection authority.");
        }
        if (RESERVED_PRODUCTION_ENVIRONMENTS.contains(
                exact.environmentId()
                        .toLowerCase(Locale.ROOT))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_ENVIRONMENT_FORBIDDEN",
                    "Selected-population uploads cannot serve a reserved production scope.");
        }
        return exact;
    }

    private MirrorOperationObservability.Observation audit(
            MirrorOperationAuditEvent.Operation operation,
            IntegrationRequestContext identity,
            String uploadId,
            String populationId) {
        return observability.start(
                operation,
                identity,
                uploadId,
                populationId,
                "");
    }

    private void succeedOnly(
            MirrorOperationObservability.Observation audit,
            String fingerprint) {
        required(
                mutations.execute(ignored -> {
                    audit.succeeded(fingerprint);
                    return Boolean.TRUE;
                }),
                "upload success audit");
    }

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure
                instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure
                instanceof AuthoritativeOutcomeSelectedPopulationUploadRepository
                .Violation violation) {
            return switch (violation.reason()) {
                case UPLOAD_NOT_FOUND ->
                        notFound(identity);
                case UPLOAD_EXPIRED ->
                        new IntegrationProblemException(
                                IntegrationProblem.gone(
                                        "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_EXPIRED",
                                        "The selected-population upload has expired.",
                                        identity.correlationId(),
                                        Map.of()));
                case ACTIVE_UPLOAD_QUOTA_EXCEEDED,
                     UPLOAD_BYTE_QUOTA_EXCEEDED ->
                        new IntegrationProblemException(
                                IntegrationProblem
                                        .tooManyRequests(
                                                "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_CAPACITY",
                                                "Selected-population upload capacity is exhausted.",
                                                identity.correlationId(),
                                                Map.of(
                                                        "retryAfterSeconds",
                                                        60)));
                case FINALIZATION_BUSY ->
                        new IntegrationProblemException(
                                IntegrationProblem
                                        .retryableConflict(
                                                "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_FINALIZING",
                                                "The selected-population upload is already finalizing.",
                                                identity.correlationId(),
                                                Map.of()));
                case STORED_STATE_CORRUPT ->
                        unavailable(identity);
                default ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_CONFLICT",
                                        "The selected-population upload conflicts with immutable staged state.",
                                        identity.correlationId(),
                                        Map.of()));
            };
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_INVALID",
                            "The selected-population upload violates the governed protocol.",
                            identity.correlationId(),
                            Map.of()));
        }
        return unavailable(identity);
    }

    private static void requireScope(
            CapabilitySnapshot.Scope requested,
            IntegrationRequestContext identity) {
        if (!scope(identity).equals(requested)) {
            throw notFound(identity);
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
                        "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_NOT_FOUND",
                        "The selected-population upload was not found in the authenticated scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME.POPULATION_UPLOAD_UNAVAILABLE",
                        "Selected-population upload coordination is temporarily unavailable.",
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
