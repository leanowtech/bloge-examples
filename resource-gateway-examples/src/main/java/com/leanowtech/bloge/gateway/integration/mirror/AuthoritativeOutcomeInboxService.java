package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Protected application boundary for outcome observation admission, reads, and lifecycle audit.
 *
 * <p>Authentication precedes decoding in the HTTP adapter. This service then enforces workload
 * connector role, exact enterprise scope, non-production environment, stable unsigned request
 * replay, independent business authority, Resource Gateway signing, append-only predecessor
 * fencing, and mandatory operation audit. Full external authority verification occurs before the
 * short transaction that atomically appends the revision and commits its success audit.</p>
 */
public final class AuthoritativeOutcomeInboxService {
    /** Evidence and owner purposes allowed to read payload-free outcome artifacts. */
    public static final Set<String> READ_PURPOSES = Set.of(
            AuthoritativeOutcomeInboxAccessPolicy
                    .INGESTION_PURPOSE,
            DomainFidelityPolicy.GOVERNANCE_PURPOSE,
            "GOVERNANCE_EVIDENCE_INGESTION");
    /** Largest public lifecycle page. */
    public static final int MAXIMUM_LIFECYCLE_PAGE =
            1_000;
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeInboxRepository repository;
    private final AuthoritativeOutcomeObservationIntegrity integrity;
    private final AuthoritativeOutcomeInboxAccessPolicy accessPolicy;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observability;
    private final TransactionTemplate mutations;

    /**
     * Creates the protected outcome inbox application boundary.
     *
     * @param repository append-only inbox and durable work state
     * @param integrity business-authority verifier and Resource Gateway signer
     * @param accessPolicy server-owned connector role policy
     * @param mapper canonical protocol mapper
     * @param observability mandatory payload-free operation audit
     * @param transactionManager transaction shared by repository append and success audit
     */
    public AuthoritativeOutcomeInboxService(
            AuthoritativeOutcomeInboxRepository repository,
            AuthoritativeOutcomeObservationIntegrity integrity,
            AuthoritativeOutcomeInboxAccessPolicy accessPolicy,
            ObjectMapper mapper,
            MirrorOperationObservability observability,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
        this.accessPolicy = Objects.requireNonNull(
                accessPolicy, "accessPolicy");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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

    /**
     * Verifies, signs, and durably appends one outcome observation revision.
     *
     * <p>An unsigned retry is recognized by a canonical ingestion fingerprint that excludes only
     * Resource Gateway address, attestation time, and seal. Exact replay returns the already signed
     * revision, avoiding a second signing-time-dependent artifact.</p>
     */
    public AuthoritativeOutcomeInboxAdmission ingest(
            AuthoritativeOutcomeObservationAdmissionRequest request,
            IntegrationRequestContext identity) {
        IntegrationRequestContext connector =
                requireIdentity(
                        identity,
                        Set.of(
                                AuthoritativeOutcomeInboxAccessPolicy
                                        .INGESTION_PURPOSE));
        AuthoritativeOutcomeObservationAdmissionRequest command =
                Objects.requireNonNull(request, "request");
        AuthoritativeOutcomeObservation candidate =
                command.observation();
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_OBSERVATION_INGEST,
                        connector,
                        candidate.observationId(),
                        candidate.inventoryRef().id(),
                        "");
        try {
            if (!accessPolicy.mayIngest(connector)) {
                throw forbidden(
                        connector,
                        "RG.MIRROR.OUTCOME.CONNECTOR_FORBIDDEN",
                        "The authenticated workload is not an authorized outcome connector.");
            }
            requireScope(candidate.scope(), connector);
            AuthoritativeOutcomeObservation signed =
                    resolveSignedRetryOrSign(candidate);
            AuthoritativeOutcomeInboxRepository.Admission
                    stored = Objects.requireNonNull(
                    mutations.execute(ignored -> {
                        AuthoritativeOutcomeInboxRepository
                                .Admission admission =
                                repository.appendPreverified(
                                        signed,
                                        command
                                                .expectedPredecessorFingerprint());
                        audit.succeeded(
                                signed.observationFingerprint());
                        return admission;
                    }),
                    "outcome admission transaction returned null");
            return new AuthoritativeOutcomeInboxAdmission(
                    "",
                    signed,
                    stored.entry(),
                    stored.idempotentReplay());
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, connector));
        }
    }

    /** Reads one exact immutable revision after both trust boundaries are reverified. */
    public AuthoritativeOutcomeObservation findObservation(
            String observationId,
            long revision,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, observationId);
        try {
            AuthoritativeOutcomeObservation value =
                    integrity.verify(
                            repository.findObservation(
                                    scope(reader),
                                    observationId,
                                    revision)
                                    .orElseThrow(() ->
                                            notFound(reader)));
            audit.succeeded(
                    value.observationFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads the current immutable revision after both trust boundaries are reverified. */
    public AuthoritativeOutcomeObservation findLatestObservation(
            String observationId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, observationId);
        try {
            AuthoritativeOutcomeObservation value =
                    integrity.verify(
                            repository.findLatestObservation(
                                    scope(reader),
                                    observationId)
                                    .orElseThrow(() ->
                                            notFound(reader)));
            audit.succeeded(
                    value.observationFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads the mutable durable head only after its current observation is fully reverified. */
    public AuthoritativeOutcomeInboxEntry findEntry(
            String observationId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                readAudit(reader, observationId);
        try {
            CapabilitySnapshot.Scope scope =
                    scope(reader);
            AuthoritativeOutcomeInboxEntry entry =
                    repository.findEntry(
                            scope, observationId)
                            .orElseThrow(() ->
                                    notFound(reader));
            AuthoritativeOutcomeObservation current =
                    integrity.verify(
                            repository.findObservation(
                                    scope,
                                    observationId,
                                    entry.currentRevision())
                                    .orElseThrow(() ->
                                            unavailable(reader)));
            if (!current.observationFingerprint().equals(
                    entry.currentObservationFingerprint())) {
                throw unavailable(reader);
            }
            audit.succeeded(
                    entry.currentObservationFingerprint());
            return entry;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    /** Reads one bounded append-ordered lifecycle page after full current-head verification. */
    public AuthoritativeOutcomeInboxLifecyclePage lifecycle(
            String observationId,
            long afterOrdinal,
            int limit,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader =
                requireReadIdentity(identity);
        MirrorOperationObservability.Observation audit =
                observability.start(
                        MirrorOperationAuditEvent.Operation
                                .OUTCOME_LIFECYCLE_READ,
                        reader,
                        observationId,
                        "",
                        "");
        try {
            if (afterOrdinal < 0
                    || limit < 1
                    || limit
                    > MAXIMUM_LIFECYCLE_PAGE) {
                throw new IllegalArgumentException(
                        "lifecycle cursor or limit is invalid");
            }
            findEntryWithoutAudit(
                    observationId, reader);
            List<AuthoritativeOutcomeInboxLifecycleEvent>
                    fetched = repository.lifecycle(
                    scope(reader),
                    observationId,
                    afterOrdinal,
                    limit + 1);
            boolean hasMore = fetched.size() > limit;
            List<AuthoritativeOutcomeInboxLifecycleEvent>
                    events = hasMore
                    ? new ArrayList<>(
                    fetched.subList(0, limit))
                    : List.copyOf(fetched);
            long nextOrdinal = events.isEmpty()
                    ? afterOrdinal
                    : events.getLast().eventOrdinal();
            AuthoritativeOutcomeInboxLifecyclePage page =
                    new AuthoritativeOutcomeInboxLifecyclePage(
                            "",
                            observationId,
                            afterOrdinal,
                            nextOrdinal,
                            hasMore,
                            events);
            audit.succeeded(observationId);
            return page;
        } catch (RuntimeException failure) {
            throw audit.failed(
                    mapFailure(failure, reader));
        }
    }

    private AuthoritativeOutcomeObservation
    resolveSignedRetryOrSign(
            AuthoritativeOutcomeObservation candidate) {
        Optional<AuthoritativeOutcomeObservation> existing =
                repository.findObservation(
                        candidate.scope(),
                        candidate.observationId(),
                        candidate.revision());
        if (existing.isEmpty()) {
            return integrity.sign(candidate);
        }
        AuthoritativeOutcomeObservation stored =
                integrity.verify(
                        existing.orElseThrow());
        if (!stored.ingestionMaterialFingerprint(mapper)
                .equals(candidate
                        .ingestionMaterialFingerprint(mapper))) {
            throw new AuthoritativeOutcomeInboxRepository
                    .Violation(
                    AuthoritativeOutcomeInboxRepository
                            .Reason.CONTENT_CONFLICT);
        }
        return stored;
    }

    private AuthoritativeOutcomeInboxEntry
    findEntryWithoutAudit(
            String observationId,
            IntegrationRequestContext reader) {
        CapabilitySnapshot.Scope scope =
                scope(reader);
        AuthoritativeOutcomeInboxEntry entry =
                repository.findEntry(
                        scope, observationId)
                        .orElseThrow(() ->
                                notFound(reader));
        AuthoritativeOutcomeObservation current =
                integrity.verify(
                        repository.findObservation(
                                scope,
                                observationId,
                                entry.currentRevision())
                                .orElseThrow(() ->
                                        unavailable(reader)));
        if (!current.observationFingerprint().equals(
                entry.currentObservationFingerprint())) {
            throw unavailable(reader);
        }
        return entry;
    }

    private MirrorOperationObservability.Observation readAudit(
            IntegrationRequestContext identity,
            String observationId) {
        return observability.start(
                MirrorOperationAuditEvent.Operation
                        .OUTCOME_OBSERVATION_READ,
                identity,
                observationId,
                "",
                "");
    }

    private static IntegrationRequestContext
    requireReadIdentity(
            IntegrationRequestContext identity) {
        return requireIdentity(identity, READ_PURPOSES);
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
                    "RG.MIRROR.OUTCOME.PURPOSE_FORBIDDEN",
                    "The authenticated purpose cannot perform this outcome inbox operation.");
        }
        if (RESERVED_PRODUCTION_ENVIRONMENTS
                .contains(exact.environmentId()
                        .trim()
                        .toLowerCase(Locale.ROOT))) {
            throw forbidden(
                    exact,
                    "RG.MIRROR.OUTCOME.ENVIRONMENT_FORBIDDEN",
                    "The outcome inbox cannot serve a reserved production scope.");
        }
        return exact;
    }

    private static void requireScope(
            CapabilitySnapshot.Scope requested,
            IntegrationRequestContext identity) {
        if (!scope(identity).equals(requested)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.notFound(
                            "RG.MIRROR.OUTCOME.SCOPE_NOT_FOUND",
                            "The outcome observation scope was not found in the authenticated namespace.",
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

    private static RuntimeException mapFailure(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure
                instanceof AuthoritativeOutcomeObservationIntegrity
                .Violation violation) {
            return switch (violation.reason()) {
                case AUTHORITY_UNAVAILABLE,
                     KEY_UNAVAILABLE ->
                        unavailable(identity);
                case AUTHORITY_REJECTED,
                     UNSIGNED,
                     SIGNATURE_INVALID,
                     SIGNING_TIME_INVALID ->
                        invalid(identity);
            };
        }
        if (failure
                instanceof AuthoritativeOutcomeInboxRepository
                .Violation violation) {
            return switch (violation.reason()) {
                case LINEAGE_CONFLICT,
                     CONTENT_CONFLICT,
                     LEASE_LOST,
                     SUCCESSOR_INVALID ->
                        new IntegrationProblemException(
                                IntegrationProblem.conflict(
                                        "RG.MIRROR.OUTCOME.IMMUTABLE_CONFLICT",
                                        "The outcome revision conflicts with committed lineage.",
                                        identity.correlationId(),
                                        Map.of()));
                case OBSERVATION_NOT_FOUND ->
                        notFound(identity);
                case STORED_STATE_CORRUPT ->
                        unavailable(identity);
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
                        "RG.MIRROR.OUTCOME.INVALID",
                        "The outcome observation violates the governed protocol.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.notFound(
                        "RG.MIRROR.OUTCOME.OBSERVATION_NOT_FOUND",
                        "The outcome observation was not found in the authenticated scope.",
                        identity.correlationId(),
                        Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME.UNAVAILABLE",
                        "The authoritative outcome inbox is temporarily unavailable.",
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
}
