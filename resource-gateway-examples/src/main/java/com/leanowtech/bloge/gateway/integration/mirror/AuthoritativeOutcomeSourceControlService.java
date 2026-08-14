package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authenticated, audited authority boundary for backfill, revocation, and checkpoint reads. */
public final class AuthoritativeOutcomeSourceControlService {
    /** Purpose allowed to submit externally authorized connector commands. */
    public static final String ADMIN_PURPOSE = "MIRROR_OUTCOME_SOURCE_ADMIN";
    /** Read-only purposes allowed to inspect payload-free checkpoint progress. */
    public static final Set<String> READ_PURPOSES = Set.of(
            ADMIN_PURPOSE,
            DomainFidelityPolicy.GOVERNANCE_PURPOSE,
            "GOVERNANCE_EVIDENCE_INGESTION");
    private static final Duration MAXIMUM_CLOCK_SKEW = Duration.ofMinutes(2);
    private static final Set<String> RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeSourceCheckpointRepository repository;
    private final AuthoritativeOutcomeSourceAuthorityVerifier authority;
    private final MirrorOperationObservability observability;
    private final ObjectMapper mapper;
    private final TransactionTemplate mutations;

    /** Creates the connector command and checkpoint application boundary. */
    public AuthoritativeOutcomeSourceControlService(
            AuthoritativeOutcomeSourceCheckpointRepository repository,
            AuthoritativeOutcomeSourceAuthorityVerifier authority,
            MirrorOperationObservability observability,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        mutations = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Verifies external authority and registers an independent historical stream. */
    public AuthoritativeOutcomeSourceCheckpointRepository.Admission registerBackfill(
            AuthoritativeOutcomeConnectorControlCommand command,
            IntegrationRequestContext identity) {
        return mutate(command, identity,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.BACKFILL,
                MirrorOperationAuditEvent.Operation.OUTCOME_SOURCE_BACKFILL_REGISTER,
                repository::registerBackfill);
    }

    /** Verifies external authority and irreversibly fences one connector generation. */
    public AuthoritativeOutcomeSourceCheckpointRepository.Revocation revokeGeneration(
            AuthoritativeOutcomeConnectorControlCommand command,
            IntegrationRequestContext identity) {
        return mutate(command, identity,
                AuthoritativeOutcomeConnectorControlCommand.CommandType.REVOKE_GENERATION,
                MirrorOperationAuditEvent.Operation.OUTCOME_SOURCE_GENERATION_REVOKE,
                repository::revokeGeneration);
    }

    /** Reads one exact payload-free checkpoint in authenticated scope. */
    public AuthoritativeOutcomeSourceCheckpointRepository.Snapshot find(
            String connectorId,
            long connectorGeneration,
            AuthoritativeOutcomeSourcePage.StreamKind streamKind,
            String streamId,
            IntegrationRequestContext identity) {
        IntegrationRequestContext reader = requireCompleteIdentity(identity);
        MirrorOperationObservability.Observation audit = observability.start(
                MirrorOperationAuditEvent.Operation.OUTCOME_SOURCE_CHECKPOINT_READ,
                reader, connectorId, streamId, "");
        try {
            requireAuthorized(reader, READ_PURPOSES);
            AuthoritativeOutcomeSourceCheckpointRepository.StreamKey key =
                    new AuthoritativeOutcomeSourceCheckpointRepository.StreamKey(
                            scope(reader), connectorId, connectorGeneration,
                            streamKind, streamId);
            AuthoritativeOutcomeSourceCheckpointRepository.Snapshot value =
                    repository.find(key).orElseThrow(() -> problem(
                            IntegrationProblem.notFound(
                                    "RG.MIRROR.OUTCOME_SOURCE.CHECKPOINT_NOT_FOUND",
                                    "The outcome source checkpoint was not found in the authenticated scope.",
                                    reader.correlationId(), Map.of())));
            audit.succeeded(value.committedPageFingerprint());
            return value;
        } catch (RuntimeException failure) {
            throw audit.failed(mapFailure(failure, reader));
        }
    }

    /** @return whether command authority and durable checkpoint storage are usable */
    public boolean ready() {
        try {
            return repository.durable() && authority.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private <T> T mutate(
            AuthoritativeOutcomeConnectorControlCommand command,
            IntegrationRequestContext identity,
            AuthoritativeOutcomeConnectorControlCommand.CommandType type,
            MirrorOperationAuditEvent.Operation operation,
            Command<T> mutation) {
        IntegrationRequestContext admin = requireCompleteIdentity(identity);
        AuthoritativeOutcomeConnectorControlCommand exact =
                Objects.requireNonNull(command, "command");
        MirrorOperationObservability.Observation audit = observability.start(
                operation, admin, exact.commandId(), exact.connectorId(), "");
        try {
            requireAuthorized(admin, Set.of(ADMIN_PURPOSE));
            if (exact.commandType() != type || !scope(admin).equals(exact.scope())) {
                throw problem(IntegrationProblem.notFound(
                        "RG.MIRROR.OUTCOME_SOURCE.COMMAND_SCOPE_NOT_FOUND",
                        "The connector command was not found in the authenticated scope.",
                        admin.correlationId(), Map.of()));
            }
            exact.verify(mapper);
            Instant observedAt = repository.observedAt();
            if (exact.requestedAt().isAfter(observedAt.plus(MAXIMUM_CLOCK_SKEW))) {
                throw problem(IntegrationProblem.badRequest(
                        "RG.MIRROR.OUTCOME_SOURCE.COMMAND_TIME_INVALID",
                        "The connector command request time is outside the accepted clock window.",
                        admin.correlationId(), Map.of()));
            }
            if (!observedAt.isBefore(exact.expiresAt())) {
                throw problem(IntegrationProblem.gone(
                        "RG.MIRROR.OUTCOME_SOURCE.COMMAND_EXPIRED",
                        "The connector command authority window has expired.",
                        admin.correlationId(), Map.of()));
            }
            if (!authority.available()) {
                throw problem(IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_UNAVAILABLE",
                        "The external outcome source authority is unavailable.",
                        admin.correlationId(), Map.of()));
            }
            try {
                authority.verifyCommand(exact);
            } catch (RuntimeException rejected) {
                throw problem(IntegrationProblem.forbidden(
                        "RG.MIRROR.OUTCOME_SOURCE.AUTHORITY_REJECTED",
                        "The external outcome source authority rejected the command.",
                        admin.correlationId(), Map.of()));
            }
            T value = Objects.requireNonNull(
                    mutations.execute(ignored -> {
                        T result = mutation.execute(exact);
                        audit.succeeded(exact.commandFingerprint());
                        return result;
                    }),
                    "outcome source command transaction returned null");
            return value;
        } catch (RuntimeException failure) {
            throw audit.failed(mapFailure(failure, admin));
        }
    }

    private static IntegrationRequestContext requireCompleteIdentity(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact = Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        return exact;
    }

    private static void requireAuthorized(
            IntegrationRequestContext exact, Set<String> purposes) {
        if (!purposes.contains(exact.purpose())) {
            throw problem(IntegrationProblem.forbidden(
                    "RG.MIRROR.OUTCOME_SOURCE.PURPOSE_FORBIDDEN",
                    "The authenticated purpose cannot manage outcome source checkpoints.",
                    exact.correlationId(), Map.of()));
        }
        if (RESERVED_PRODUCTION_ENVIRONMENTS.contains(
                exact.environmentId().trim().toLowerCase(Locale.ROOT))) {
            throw problem(IntegrationProblem.forbidden(
                    "RG.MIRROR.OUTCOME_SOURCE.ENVIRONMENT_FORBIDDEN",
                    "Outcome source checkpoints cannot be served in a reserved production scope.",
                    exact.correlationId(), Map.of()));
        }
    }

    private static RuntimeException mapFailure(
            RuntimeException failure, IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException) {
            return failure;
        }
        if (failure instanceof AuthoritativeOutcomeSourceCheckpointRepository.Violation violation) {
            return switch (violation.reason()) {
                case NOT_FOUND -> problem(IntegrationProblem.notFound(
                        "RG.MIRROR.OUTCOME_SOURCE.CHECKPOINT_NOT_FOUND",
                        "The outcome source checkpoint was not found.",
                        identity.correlationId(), Map.of()));
                case GENERATION_REVOKED -> problem(IntegrationProblem.gone(
                        "RG.MIRROR.OUTCOME_SOURCE.GENERATION_REVOKED",
                        "The outcome source connector generation has been revoked.",
                        identity.correlationId(), Map.of()));
                case CONTENT_CONFLICT, PAGE_CONFLICT, LEASE_LOST, TERMINAL_STREAM ->
                        problem(IntegrationProblem.conflict(
                                "RG.MIRROR.OUTCOME_SOURCE.CONFLICT",
                                "The outcome source command conflicts with durable state.",
                                identity.correlationId(), Map.of()));
                case COMMAND_INVALID -> problem(IntegrationProblem.badRequest(
                        "RG.MIRROR.OUTCOME_SOURCE.COMMAND_INVALID",
                        "The outcome source command is not valid.",
                        identity.correlationId(), Map.of()));
                case STORAGE_INVALID -> problem(IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.OUTCOME_SOURCE.STORAGE_INVALID",
                        "The outcome source checkpoint store failed integrity verification.",
                        identity.correlationId(), Map.of()));
            };
        }
        if (failure instanceof IllegalArgumentException) {
            return problem(IntegrationProblem.badRequest(
                    "RG.MIRROR.OUTCOME_SOURCE.COMMAND_INVALID",
                    "The outcome source command is not valid.",
                    identity.correlationId(), Map.of()));
        }
        return problem(IntegrationProblem.serviceUnavailable(
                "RG.MIRROR.OUTCOME_SOURCE.UNAVAILABLE",
                "The outcome source control plane is unavailable.",
                identity.correlationId(), Map.of()));
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static IntegrationProblemException problem(IntegrationProblem value) {
        return new IntegrationProblemException(value);
    }

    @FunctionalInterface
    private interface Command<T> {
        T execute(AuthoritativeOutcomeConnectorControlCommand command);
    }
}
