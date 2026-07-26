package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Protected audited application boundary for selected-population completeness.
 *
 * <p>Authentication precedes decoding in the HTTP adapter. This service enforces exact scope,
 * non-production isolation, three-role separation of duties, stable unsigned response-loss replay,
 * independent external authorities, short transactional mutations, bounded coherent-cut retry,
 * mandatory audit, and externally reverified reads.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationApplicationService {
    /** Purposes permitted to read payload-free population evidence. */
    public static final Set<String> READ_PURPOSES = Set.of(
            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                    .SELECTION_PURPOSE,
            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                    .DISPOSITION_PURPOSE,
            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                    .ASSESSMENT_PURPOSE,
            "GOVERNANCE_EVIDENCE_INGESTION");
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeSelectedPopulationRepository
            repository;
    private final AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private final
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private final AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private final AuthoritativeOutcomeSelectedPopulationAccessPolicy
            accessPolicy;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observability;
    private final TransactionTemplate mutations;

    /**
     * Creates the protected selected-population application boundary.
     *
     * @param repository append-only registry and coherent-cut coordinator
     * @param populationIntegrity selection-authority trust and signing boundary
     * @param dispositionIntegrity deletion-authority trust and signing boundary
     * @param projector complete business-authority projection boundary
     * @param accessPolicy server-owned three-role access policy
     * @param mapper canonical protocol mapper
     * @param observability mandatory payload-free operation audit
     * @param transactionManager transaction shared by repository mutation and success audit
     */
    public AuthoritativeOutcomeSelectedPopulationApplicationService(
            AuthoritativeOutcomeSelectedPopulationRepository
                    repository,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector,
            AuthoritativeOutcomeSelectedPopulationAccessPolicy
                    accessPolicy,
            ObjectMapper mapper,
            MirrorOperationObservability observability,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.populationIntegrity = Objects.requireNonNull(
                populationIntegrity, "populationIntegrity");
        this.dispositionIntegrity = Objects.requireNonNull(
                dispositionIntegrity, "dispositionIntegrity");
        this.projector = Objects.requireNonNull(
                projector, "projector");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy, "accessPolicy");
        this.mapper = Objects.requireNonNull(
                mapper, "mapper");
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

    /** Verifies, signs, and atomically appends one complete selected population. */
    public AuthoritativeOutcomeSelectedPopulationAdmission
    ingestPopulation(
            AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                    request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireIdentity(
                        identity,
                        Set.of(
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .SELECTION_PURPOSE));
        AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                command = Objects.requireNonNull(
                request, "request");
        AuthoritativeOutcomeSelectedPopulationManifest candidate =
                command.manifest();
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_INGEST,
                        authority,
                        candidate.populationId(),
                        candidate.inventoryRef().id(),
                        "");
        try {
            if (!accessPolicy.mayRegister(authority)) {
                throw forbidden(
                        authority,
                        "RG.MIRROR.OUTCOME.POPULATION_AUTHORITY_FORBIDDEN",
                        "The workload is not an authorized outcome selection authority.");
            }
            requireScope(
                    candidate.scope(), authority);
            Optional<AuthoritativeOutcomeSelectedPopulationRepository
                    .Population> existing =
                    recoverPopulation(command);
            if (existing.isPresent()) {
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Population stored =
                        existing.orElseThrow();
                succeedOnly(
                        audit,
                        stored.manifest()
                                .manifestFingerprint());
                return populationAdmission(
                        stored, true);
            }
            AuthoritativeOutcomeSelectedPopulationManifest
                    signed = populationIntegrity.sign(
                    candidate, command.chunks());
            AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission admitted =
                    appendPopulation(
                            command, signed, audit);
            return populationAdmission(
                    admitted.population(),
                    admitted.idempotentReplay());
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Verifies, signs, and atomically appends one legal selected-member disposition. */
    public
    AuthoritativeOutcomeSelectedPopulationDispositionAdmission
    ingestDisposition(
            String populationId,
            AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                    request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext authority =
                requireIdentity(
                        identity,
                        Set.of(
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .DISPOSITION_PURPOSE));
        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                command = Objects.requireNonNull(
                request, "request");
        AuthoritativeOutcomeSelectedPopulationDisposition
                candidate = command.disposition();
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_DISPOSITION_INGEST,
                        authority,
                        candidate.dispositionId(),
                        populationId,
                        "");
        try {
            if (!accessPolicy.mayDispose(authority)) {
                throw forbidden(
                        authority,
                        "RG.MIRROR.OUTCOME.DELETION_AUTHORITY_FORBIDDEN",
                        "The workload is not an authorized outcome deletion authority.");
            }
            requireScope(
                    candidate.scope(), authority);
            if (!candidate.populationRef().id().equals(
                    populationId)) {
                throw notFound(authority);
            }
            Optional<AuthoritativeOutcomeSelectedPopulationRepository
                    .DispositionAdmission> recovered =
                    recoverDisposition(command);
            if (recovered.isPresent()) {
                AuthoritativeOutcomeSelectedPopulationRepository
                        .DispositionAdmission stored =
                        recovered.orElseThrow();
                succeedOnly(
                        audit,
                        stored.disposition()
                                .dispositionFingerprint());
                return dispositionAdmission(stored);
            }
            AuthoritativeOutcomeSelectedPopulationDisposition
                    signed = dispositionIntegrity.sign(
                    candidate);
            AuthoritativeOutcomeSelectedPopulationRepository
                    .DispositionAdmission admitted =
                    appendDisposition(
                            command, signed, audit);
            return dispositionAdmission(admitted);
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, authority));
        }
    }

    /** Projects and atomically appends one coherent current-head completeness assessment. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
    assess(
            String populationId,
            AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                    request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext governance =
                requireIdentity(
                        identity,
                        Set.of(
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .ASSESSMENT_PURPOSE));
        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                command = Objects.requireNonNull(
                request, "request");
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_POPULATION_ASSESS,
                        governance,
                        command.assessmentId(),
                        populationId,
                        "");
        try {
            if (!accessPolicy.mayAssess(governance)) {
                throw forbidden(
                        governance,
                        "RG.MIRROR.OUTCOME.ASSESSOR_FORBIDDEN",
                        "The workload is not an authorized outcome completeness projector.");
            }
            CapabilitySnapshot.Scope scope =
                    scope(governance);
            Optional<AuthoritativeOutcomeSelectedPopulationRepository
                    .AssessmentAdmission> recovered =
                    recoverAssessment(
                            scope, populationId, command);
            if (recovered.isPresent()) {
                AuthoritativeOutcomeSelectedPopulationRepository
                        .AssessmentAdmission value =
                        recovered.orElseThrow();
                succeedOnly(
                        audit,
                        value.assessment()
                                .assessmentFingerprint());
                return assessmentAdmission(value);
            }
            for (int attempt = 1;
                 attempt
                         <= AuthoritativeOutcomeSelectedPopulationService
                         .MAXIMUM_CUT_ATTEMPTS;
                 attempt++) {
                AuthoritativeOutcomeSelectedPopulationRepository
                        .AssessmentCut cut =
                        repository.prepareAssessment(
                                scope,
                                populationId,
                                command.populationRevision());
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        assessment = projector.assess(
                        command.assessmentId(),
                        command.assessmentRevision(),
                        cut.population().manifest(),
                        cut.population().chunks(),
                        cut.observations(),
                        cut.dispositions());
                if (!assessment
                        .observationSetFingerprint()
                        .equals(
                                cut.observationSetFingerprint())
                        || !assessment
                        .dispositionSetFingerprint()
                        .equals(
                                cut.dispositionSetFingerprint())) {
                    throw new AuthoritativeOutcomeSelectedPopulationRepository
                            .Violation(
                            AuthoritativeOutcomeSelectedPopulationRepository
                                    .Reason.ASSESSMENT_MISMATCH);
                }
                try {
                    AuthoritativeOutcomeSelectedPopulationRepository
                            .AssessmentAdmission admitted =
                            required(
                                    mutations.execute(ignored -> {
                                        AuthoritativeOutcomeSelectedPopulationRepository
                                                .AssessmentAdmission value =
                                                repository
                                                        .appendAssessment(
                                                                cut,
                                                                assessment,
                                                                command
                                                                        .expectedPredecessorFingerprint());
                                        audit.succeeded(
                                                assessment
                                                        .assessmentFingerprint());
                                        return value;
                                    }),
                                    "assessment admission");
                    return assessmentAdmission(admitted);
                } catch (AuthoritativeOutcomeSelectedPopulationRepository
                         .Violation conflict) {
                    if (conflict.reason()
                            == AuthoritativeOutcomeSelectedPopulationRepository
                            .Reason.CONTENT_CONFLICT) {
                        Optional<AuthoritativeOutcomeSelectedPopulationRepository
                                .AssessmentAdmission> concurrent =
                                recoverAssessment(
                                        scope,
                                        populationId,
                                        command);
                        if (concurrent.isPresent()) {
                            AuthoritativeOutcomeSelectedPopulationRepository
                                    .AssessmentAdmission value =
                                    concurrent.orElseThrow();
                            succeedOnly(
                                    audit,
                                    value.assessment()
                                            .assessmentFingerprint());
                            return assessmentAdmission(value);
                        }
                    }
                    if (conflict.reason()
                            != AuthoritativeOutcomeSelectedPopulationRepository
                            .Reason.CUT_STALE
                            || attempt
                            == AuthoritativeOutcomeSelectedPopulationService
                            .MAXIMUM_CUT_ATTEMPTS) {
                        throw conflict;
                    }
                }
            }
            throw new IllegalStateException(
                    "unreachable assessment cut retry state");
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, governance));
        }
    }

    /** Reads and externally reverifies one exact complete population revision. */
    public AuthoritativeOutcomeSelectedPopulationBundle
    findPopulation(
            String populationId,
            long revision,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, populationId);
        try {
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Population population =
                    repository.findPopulation(
                            scope(reader),
                            populationId,
                            revision)
                            .orElseThrow(() ->
                                    notFound(reader));
            populationIntegrity.verify(
                    population.manifest(),
                    population.chunks());
            audit.succeeded(
                    population.manifest()
                            .manifestFingerprint());
            return AuthoritativeOutcomeSelectedPopulationBundle
                    .from(population);
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads and externally reverifies the current complete population revision. */
    public AuthoritativeOutcomeSelectedPopulationBundle
    findLatestPopulation(
            String populationId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, populationId);
        try {
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Population population =
                    repository.findLatestPopulation(
                            scope(reader),
                            populationId)
                            .orElseThrow(() ->
                                    notFound(reader));
            populationIntegrity.verify(
                    population.manifest(),
                    population.chunks());
            audit.succeeded(
                    population.manifest()
                            .manifestFingerprint());
            return AuthoritativeOutcomeSelectedPopulationBundle
                    .from(population);
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads and externally reverifies one exact legal-disposition revision. */
    public AuthoritativeOutcomeSelectedPopulationDisposition
    findDisposition(
            String populationId,
            String dispositionId,
            long revision,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, dispositionId);
        try {
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition = dispositionIntegrity.verify(
                    repository.findDisposition(
                            scope(reader),
                            dispositionId,
                            revision)
                            .orElseThrow(() ->
                                    notFound(reader)));
            if (!disposition.populationRef().id().equals(
                    populationId)) {
                throw notFound(reader);
            }
            audit.succeeded(
                    disposition
                            .dispositionFingerprint());
            return disposition;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads one signed assessment after population and source-index reverification. */
    public AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    findAssessment(
            String populationId,
            String assessmentId,
            long revision,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, assessmentId);
        try {
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment = repository.findAssessment(
                    scope(reader),
                    assessmentId,
                    revision)
                    .orElseThrow(() ->
                            notFound(reader));
            if (!assessment.populationRef().id().equals(
                    populationId)) {
                throw notFound(reader);
            }
            verifyAssessmentPopulation(assessment);
            audit.succeeded(
                    assessment
                            .assessmentFingerprint());
            return assessment;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads one content-addressed historical assessment-source suffix. */
    public AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
    assessmentSources(
            String populationId,
            String assessmentId,
            long revision,
            long afterGlobalOrdinal,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, assessmentId);
        try {
            AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                    page = repository.assessmentSources(
                    scope(reader),
                    assessmentId,
                    revision,
                    afterGlobalOrdinal,
                    limit);
            if (!page.populationRef().id().equals(
                    populationId)) {
                throw notFound(reader);
            }
            page.verify(mapper);
            audit.succeeded(
                    page.pageFingerprint());
            return page;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** @return whether all independent authorities, signer, and projector are currently usable */
    public boolean available() {
        return populationIntegrity.available()
                && dispositionIntegrity.available()
                && projector.available();
    }

    private AuthoritativeOutcomeSelectedPopulationRepository
            .PopulationAdmission appendPopulation(
            AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                    command,
            AuthoritativeOutcomeSelectedPopulationManifest signed,
            MirrorOperationObservability.Observation audit) {
        try {
            return required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationRepository
                                .PopulationAdmission value =
                                repository.registerPreverified(
                                        signed,
                                        command.chunks(),
                                        command
                                                .expectedPredecessorFingerprint());
                        audit.succeeded(
                                signed.manifestFingerprint());
                        return value;
                    }),
                    "population admission");
        } catch (AuthoritativeOutcomeSelectedPopulationRepository
                 .Violation conflict) {
            if (conflict.reason()
                    != AuthoritativeOutcomeSelectedPopulationRepository
                    .Reason.CONTENT_CONFLICT) {
                throw conflict;
            }
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Population stored =
                    recoverPopulation(command)
                            .orElseThrow(() -> conflict);
            succeedOnly(
                    audit,
                    stored.manifest()
                            .manifestFingerprint());
            return new AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission(stored, true);
        }
    }

    private Optional<AuthoritativeOutcomeSelectedPopulationRepository
            .Population> recoverPopulation(
            AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                    command) {
        AuthoritativeOutcomeSelectedPopulationManifest candidate =
                command.manifest();
        return repository.findPopulation(
                        candidate.scope(),
                        candidate.populationId(),
                        candidate.revision())
                .map(stored -> {
                    populationIntegrity.verify(
                            stored.manifest(),
                            stored.chunks());
                    if (!stored.predecessorFingerprint()
                            .equals(command
                                    .expectedPredecessorFingerprint())
                            || !stored.chunks().equals(
                            command.chunks())
                            || !stored.manifest()
                            .ingestionMaterialFingerprint(
                                    mapper)
                            .equals(candidate
                                    .ingestionMaterialFingerprint(
                                            mapper))) {
                        throw contentConflict();
                    }
                    return stored;
                });
    }

    private AuthoritativeOutcomeSelectedPopulationRepository
            .DispositionAdmission appendDisposition(
            AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                    command,
            AuthoritativeOutcomeSelectedPopulationDisposition
                    signed,
            MirrorOperationObservability.Observation audit) {
        try {
            return required(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeSelectedPopulationRepository
                                .DispositionAdmission value =
                                repository
                                        .appendDispositionPreverified(
                                                signed,
                                                command
                                                        .expectedPredecessorFingerprint());
                        audit.succeeded(
                                signed.dispositionFingerprint());
                        return value;
                    }),
                    "disposition admission");
        } catch (AuthoritativeOutcomeSelectedPopulationRepository
                 .Violation conflict) {
            if (conflict.reason()
                    != AuthoritativeOutcomeSelectedPopulationRepository
                    .Reason.CONTENT_CONFLICT) {
                throw conflict;
            }
            AuthoritativeOutcomeSelectedPopulationRepository
                    .DispositionAdmission recovered =
                    recoverDisposition(command)
                            .orElseThrow(() -> conflict);
            succeedOnly(
                    audit,
                    recovered.disposition()
                            .dispositionFingerprint());
            return recovered;
        }
    }

    private Optional<AuthoritativeOutcomeSelectedPopulationRepository
            .DispositionAdmission> recoverDisposition(
            AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                    command) {
        AuthoritativeOutcomeSelectedPopulationDisposition candidate =
                command.disposition();
        return repository.recoverDisposition(
                        candidate.scope(),
                        candidate.dispositionId(),
                        candidate.revision(),
                        command
                                .expectedPredecessorFingerprint())
                .map(stored -> {
                    AuthoritativeOutcomeSelectedPopulationDisposition
                            exact = dispositionIntegrity.verify(
                            stored.disposition());
                    if (!exact.ingestionMaterialFingerprint(
                            mapper).equals(
                            candidate
                                    .ingestionMaterialFingerprint(
                                            mapper))) {
                        throw contentConflict();
                    }
                    return stored;
                });
    }

    private Optional<AuthoritativeOutcomeSelectedPopulationRepository
            .AssessmentAdmission> recoverAssessment(
            CapabilitySnapshot.Scope scope,
            String populationId,
            AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                    command) {
        return repository.recoverAssessment(
                        scope,
                        command.assessmentId(),
                        command.assessmentRevision(),
                        command
                                .expectedPredecessorFingerprint())
                .map(value -> {
                    if (!value.assessment()
                            .populationRef().id().equals(
                                    populationId)
                            || value.assessment()
                            .populationRef().revision()
                            != command.populationRevision()) {
                        throw contentConflict();
                    }
                    verifyAssessmentPopulation(
                            value.assessment());
                    return value;
                });
    }

    private static AuthoritativeOutcomeSelectedPopulationRepository
            .Violation contentConflict() {
        return new AuthoritativeOutcomeSelectedPopulationRepository
                .Violation(
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.CONTENT_CONFLICT);
    }

    private void verifyAssessmentPopulation(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment) {
        projector.verifyAssessment(assessment);
        AuthoritativeOutcomeSelectedPopulationRepository
                .Population population =
                repository.findPopulation(
                        assessment.scope(),
                        assessment.populationRef().id(),
                        assessment.populationRef().revision())
                        .filter(value ->
                                value.manifest()
                                        .artifactRef()
                                        .equals(
                                                assessment
                                                        .populationRef()))
                        .orElseThrow(() ->
                                new AuthoritativeOutcomeSelectedPopulationRepository
                                        .Violation(
                                        AuthoritativeOutcomeSelectedPopulationRepository
                                                .Reason
                                                .STORED_STATE_CORRUPT));
        populationIntegrity.verify(
                population.manifest(),
                population.chunks());
    }

    private void succeedOnly(
            MirrorOperationObservability.Observation audit,
            String fingerprint) {
        required(
                mutations.execute(ignored -> {
                    audit.succeeded(fingerprint);
                    return Boolean.TRUE;
                }),
                "success audit");
    }

    private MirrorOperationObservability.Observation readAudit(
            IntegrationRequestContext identity,
            String id) {
        return observability.start(
                MirrorOperationAuditEvent.Operation
                        .OUTCOME_POPULATION_READ,
                identity,
                id,
                "",
                "");
    }

    private static AuthoritativeOutcomeSelectedPopulationAdmission
    populationAdmission(
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Population population,
            boolean replay) {
        return new
                AuthoritativeOutcomeSelectedPopulationAdmission(
                AuthoritativeOutcomeSelectedPopulationAdmission
                        .SCHEMA_VERSION,
                AuthoritativeOutcomeSelectedPopulationBundle
                        .from(population),
                replay);
    }

    private static
    AuthoritativeOutcomeSelectedPopulationDispositionAdmission
    dispositionAdmission(
            AuthoritativeOutcomeSelectedPopulationRepository
                    .DispositionAdmission admission) {
        return new
                AuthoritativeOutcomeSelectedPopulationDispositionAdmission(
                AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                        .SCHEMA_VERSION,
                admission.disposition(),
                admission.predecessorFingerprint(),
                admission.idempotentReplay());
    }

    private static
    AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
    assessmentAdmission(
            AuthoritativeOutcomeSelectedPopulationRepository
                    .AssessmentAdmission admission) {
        return new
                AuthoritativeOutcomeSelectedPopulationAssessmentAdmission(
                AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                        .SCHEMA_VERSION,
                admission.assessment(),
                admission.predecessorFingerprint(),
                admission.idempotentReplay());
    }

    private static IntegrationRequestContext
    requireReadIdentity(
            IntegrationRequestContext identity) {
        return requireIdentity(
                identity, READ_PURPOSES);
    }

    private static IntegrationRequestContext requireIdentity(
            IntegrationRequestContext identity,
            Set<String> purposes) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(
                        identity, "identity");
        exact.requireComplete();
        if (!purposes.contains(exact.purpose())) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.POPULATION_PURPOSE_FORBIDDEN",
                    "The authenticated purpose cannot perform this selected-population operation.");
        }
        if (RESERVED_PRODUCTION_ENVIRONMENTS
                .contains(
                        exact.environmentId()
                                .trim()
                                .toLowerCase(
                                        Locale.ROOT))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.POPULATION_ENVIRONMENT_FORBIDDEN",
                    "Selected-population completeness cannot serve a reserved production scope.");
        }
        return exact;
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

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure
                instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure
                instanceof AuthoritativeOutcomeSelectedPopulationIntegrity
                .Violation violation) {
            return switch (violation.reason()) {
                case AUTHORITY_UNAVAILABLE,
                     KEY_UNAVAILABLE ->
                        unavailable(identity);
                case AUTHORITY_REJECTED,
                     UNSIGNED,
                     SIGNATURE_INVALID,
                     SIGNING_TIME_INVALID,
                     STRUCTURE_INVALID,
                     CHUNK_CLOSURE_INVALID,
                     MEMBER_EQUIVOCATION,
                     STRATUM_DENOMINATOR_INVALID ->
                        invalid(identity);
            };
        }
        if (failure
                instanceof AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                .Violation violation) {
            return switch (violation.reason()) {
                case AUTHORITY_UNAVAILABLE,
                     KEY_UNAVAILABLE ->
                        unavailable(identity);
                case AUTHORITY_REJECTED,
                     UNSIGNED,
                     SIGNATURE_INVALID,
                     SIGNING_TIME_INVALID,
                     STRUCTURE_INVALID ->
                        invalid(identity);
            };
        }
        if (failure
                instanceof AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                .Violation violation) {
            return switch (violation.reason()) {
                case POPULATION_UNAVAILABLE,
                     OUTCOME_UNAVAILABLE,
                     DISPOSITION_UNAVAILABLE,
                     SIGNER_UNAVAILABLE ->
                        unavailable(identity);
                default -> invalid(identity);
            };
        }
        if (failure
                instanceof AuthoritativeOutcomeSelectedPopulationRepository
                .Violation violation) {
            return switch (violation.reason()) {
                case POPULATION_NOT_FOUND,
                     ASSESSMENT_NOT_FOUND ->
                        notFound(identity);
                case STORED_STATE_CORRUPT ->
                        unavailable(identity);
                case CUT_STALE ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.OUTCOME.POPULATION_CUT_BUSY",
                                        "The selected-population source cut changed too frequently.",
                                        identity.correlationId(),
                                        Map.of()));
                default ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.OUTCOME.POPULATION_IMMUTABLE_CONFLICT",
                                        "The selected-population operation conflicts with committed lineage.",
                                        identity.correlationId(),
                                        Map.of()));
            };
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException) {
            return invalid(identity);
        }
        return unavailable(identity);
    }

    private static IntegrationProblemException invalid(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.badRequest(
                        "RG.MIRROR.OUTCOME.POPULATION_INVALID",
                        "The selected-population operation violates the governed protocol.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.notFound(
                        "RG.MIRROR.OUTCOME.POPULATION_NOT_FOUND",
                        "The selected-population artifact was not found in the authenticated scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME.POPULATION_UNAVAILABLE",
                        "Selected-population completeness is temporarily unavailable.",
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
