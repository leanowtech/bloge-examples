package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestCreationRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Authenticated orchestrator for one caller-idempotent durable graph-test creation.
 *
 * <p>The service resolves a committed command before rereading mutable dependency authorities.
 * Fresh work then freezes the exact graph, fixture, replay, identity-authority, and execution plan,
 * acquires a database-time preparation fence, executes in the isolated staged engine, and commits
 * the initial checkpoint, four-store aggregate, immutable command result, and semantic audit as one
 * local transaction. Business context never enters command, response, or audit records.</p>
 */
public final class DurableTestExecutionCreationService {

    private static final int MAX_CONTEXT_BYTES = 1_048_576;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> CONTROL_CONTEXT_KEYS = Set.of(
            "controlplan", "fixturebundle", "fixturebundleref", "testmode", "executionpurpose");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final DurableTestRecoveryAuthorizer authorizer;
    private final DurableTestCreationRuntime runtime;
    private final DurableTestExecutionCheckpointIntegrity integrity;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final DurableTestCreationLeaseCoordinator leases;

    /**
     * Creates the public durable creation application boundary.
     *
     * @param checkpoints command reservation and atomic checkpoint authority
     * @param authorizer exact target, fixture, replay, plan, and authority freezer
     * @param runtime isolated staged fresh-execution runtime
     * @param integrity durable checkpoint sealing authority
     * @param securityEvents mandatory transaction-bound semantic audit sink
     * @param objectMapper canonical request fingerprint mapper
     * @param leases database-fenced preparation heartbeat coordinator
     */
    public DurableTestExecutionCreationService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            DurableTestCreationRuntime runtime,
            DurableTestExecutionCheckpointIntegrity integrity,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            DurableTestCreationLeaseCoordinator leases) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    /**
     * Creates or replays one durable graph test at its first unambiguous signal suspension.
     *
     * @param request exact public creation intent
     * @param identity verified test-execution identity
     * @return payload-free initial suspended execution view
     */
    public DurableTestExecutionCreateResponse create(
            DurableTestExecutionCreateRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(request, identity);
        String requestFingerprint = requestFingerprint(request, identity);
        DurableTestExecutionCheckpointRepository.InitialCreationReservationResult prior =
                findPrior(request.clientRequestId(), requestFingerprint, identity);
        if (prior != null) {
            return terminalResponse(prior, identity);
        }

        DurableTestRecoveryAuthorizer.AuthorizedCreation authorized =
                authorizer.authorizeCreation(request, identity);
        var command = new DurableTestExecutionCheckpointRepository.InitialCreationCommand(
                request.clientRequestId(), requestFingerprint,
                authorized.authorizationFingerprint(), scope(identity),
                UUID.randomUUID().toString(), "engine-" + UUID.randomUUID(),
                leases.ownerId(), leases.leaseDuration());
        DurableTestExecutionCheckpointRepository.InitialCreationReservationResult reserved =
                reserve(command, identity);
        if (reserved.reservation().state()
                != DurableTestExecutionCheckpointRepository.InitialCreationState.PENDING) {
            return terminalResponse(reserved, identity);
        }
        if (!reserved.acquired()) {
            throw conflict(identity, "RG.TEST.DURABLE_CREATE_IN_PROGRESS",
                    "The same durable creation command is already being prepared.", Map.of(
                            "runId", reserved.reservation().runId(),
                            "leaseExpiresAt", reserved.reservation().leaseExpiresAt().toString()));
        }
        return executeAcquired(request, authorized, reserved.reservation(), identity);
    }

    private DurableTestExecutionCreateResponse executeAcquired(
            DurableTestExecutionCreateRequest request,
            DurableTestRecoveryAuthorizer.AuthorizedCreation authorized,
            DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation,
            IntegrationRequestContext identity) {
        try (DurableTestCreationLeaseCoordinator.LeaseGuard guard = leases.monitor(reservation)) {
            try (DurableTestCreationRuntime.PreparedCreation prepared = runtime.prepare(
                    reservation.engineExecutionId(), authorized, request.context(),
                    "initial-" + reservation.runId())) {
                if (!prepared.executionServiceState().restorable()) {
                    return reject(guard.freeze(),
                            "INITIAL_PROVIDER_STATE_NOT_RESTORABLE", identity);
                }
                DurableTestExecutionCheckpointRepository.InitialCreationReservation current =
                        guard.freeze();
                DurableTestExecutionCheckpoint checkpoint = integrity.seal(
                        new DurableTestExecutionCheckpoint(
                                DurableTestExecutionCheckpoint.SCHEMA_VERSION, current.scope(),
                                current.runId(), current.engineExecutionId(),
                                authorized.dependencies(), prepared.fixtureConsumptionState(),
                                prepared.executionServiceState(),
                                prepared.engineStateMutation().engineState(),
                                new DurableTestExecutionCheckpoint.Lifecycle(
                                        DurableTestExecutionCheckpoint.Status.SUSPENDED,
                                        current.ownerId(), current.leaseEpoch(), 0,
                                        current.createdAt(), current.updatedAt(),
                                        current.leaseExpiresAt()), ""));
                TestRuntimeTransactionMutation audit = boundAudit(identity, current,
                        "ALLOWED", "RG.TEST.DURABLE_CREATE_AUTHORIZED");
                var committed = checkpoints.commitInitialCreation(
                        current, checkpoint, prepared.engineStateMutation(), audit);
                return terminalResponse(committed, identity);
            } catch (IndependentDurableTestEngineFactory.InitialBoundaryRejectedException rejected) {
                return reject(guard.freeze(), rejected.reasonCode(), identity);
            }
        } catch (DurableTestCreationLeaseCoordinator.LeaseLostException lost) {
            throw conflict(identity, "RG.TEST.DURABLE_CREATE_LEASE_LOST",
                    "Durable creation preparation ownership became uncertain.",
                    Map.of("runId", reservation.runId()));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw persistenceConflict(identity, conflict);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_UNAVAILABLE",
                    "Durable test creation could not reach an atomic committed boundary.");
        }
    }

    private DurableTestExecutionCreateResponse reject(
            DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation,
            String rejectionCode,
            IntegrationRequestContext identity) {
        try {
            var rejected = checkpoints.rejectInitialCreation(
                    reservation, rejectionCode, boundAudit(identity, reservation,
                            "REJECTED", "RG.TEST.DURABLE_CREATE_" + rejectionCode));
            return terminalResponse(rejected, identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw persistenceConflict(identity, conflict);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "Durable creation rejection could not be committed.");
        }
    }

    private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult findPrior(
            String clientRequestId,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.findInitialCreationResult(
                    identity.tenantId(), identity.environmentId(), clientRequestId,
                    requestFingerprint).orElse(null);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw persistenceConflict(identity, conflict);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "The durable creation command store is unavailable.");
        }
    }

    private DurableTestExecutionCheckpointRepository.InitialCreationReservationResult reserve(
            DurableTestExecutionCheckpointRepository.InitialCreationCommand command,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.reserveInitialCreation(command);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw persistenceConflict(identity, conflict);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "The durable creation command could not be reserved.");
        }
    }

    private DurableTestExecutionCreateResponse terminalResponse(
            DurableTestExecutionCheckpointRepository.InitialCreationReservationResult result,
            IntegrationRequestContext identity) {
        var reservation = result.reservation();
        if (!identity.organizationId().equals(reservation.scope().organizationId())
                || !identity.projectId().equals(reservation.scope().projectId())) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "The durable creation command result failed scope verification.");
        }
        if (reservation.state()
                == DurableTestExecutionCheckpointRepository.InitialCreationState.REJECTED) {
            throw conflict(identity, "RG.TEST.DURABLE_" + reservation.rejectionCode(),
                    "The exact durable request reached a boundary unsupported by creation v1.",
                    Map.of("runId", reservation.runId(),
                            "reasonCode", reservation.rejectionCode()));
        }
        if (reservation.state()
                != DurableTestExecutionCheckpointRepository.InitialCreationState.COMMITTED
                || result.checkpoint() == null) {
            throw unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "The durable creation command result is incomplete.");
        }
        return new DurableTestExecutionCreateResponse(
                "", DurableTestExecutionQueryService.project(result.checkpoint()),
                result.idempotentReplay());
    }

    private TestRuntimeTransactionMutation boundAudit(
            IntegrationRequestContext identity,
            DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation,
            String outcome,
            String reasonCode) {
        try {
            return securityEvents.boundAppend(new TestSecurityEvent(
                    0, Instant.now(), identity.correlationId(), identity.tenantId(),
                    identity.environmentId(), identity.actorId(),
                    "DURABLE_EXECUTION_CREATE", outcome, reasonCode, Map.of(
                            "runId", reservation.runId(),
                            "requestFingerprint", reservation.requestFingerprint(),
                            "authorizationFingerprint",
                            reservation.authorizationFingerprint())));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable creation requires a transaction-bound security audit sink.");
        }
    }

    private String requestFingerprint(
            DurableTestExecutionCreateRequest request,
            IntegrationRequestContext identity) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableTestCreationAuthenticatedIntent.v1",
                "request", request,
                "principalFingerprint", DurableTestRecoveryPrincipal.fingerprint(
                        objectMapper, identity)));
    }

    private void validateRequest(
            DurableTestExecutionCreateRequest request,
            IntegrationRequestContext identity) {
        if (request == null || !DurableTestExecutionCreateRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())) {
            throw badRequest(identity, "RG.TEST.DURABLE_CREATE_SCHEMA_VERSION_INVALID",
                    "Unsupported durable creation request schemaVersion.", Map.of());
        }
        if (!IDENTIFIER.matcher(request.clientRequestId()).matches()) {
            throw badRequest(identity, "RG.TEST.DURABLE_CREATE_REQUEST_ID_INVALID",
                    "clientRequestId must be a bounded stable identifier.", Map.of());
        }
        if (!TestExecutionApiService.AUTHORIZED_PURPOSE.equals(request.executionPurpose())) {
            throw badRequest(identity, "RG.TEST.DURABLE_CREATE_PURPOSE_INVALID",
                    "executionPurpose must explicitly be GRAPH_CONTRACT_TEST.", Map.of());
        }
        if (request.target() == null || !"GRAPH".equals(request.target().kind())
                || !IDENTIFIER.matcher(request.target().id()).matches()
                || !FINGERPRINT.matcher(request.target().fingerprint()).matches()) {
            throw badRequest(identity, "RG.TEST.DURABLE_CREATE_TARGET_INVALID",
                    "An exact GRAPH target id and fingerprint are required.", Map.of());
        }
        var fixture = request.fixtureBundleRef();
        if (fixture == null || !IDENTIFIER.matcher(fixture.fixtureBundleId()).matches()
                || fixture.revision() <= 0
                || !FINGERPRINT.matcher(fixture.fingerprint()).matches()) {
            throw badRequest(identity, "RG.TEST.DURABLE_CREATE_FIXTURE_INVALID",
                    "An exact stored fixture id, positive revision, and fingerprint are required.",
                    Map.of());
        }
        request.context().keySet().stream().map(DurableTestExecutionCreationService::compactKey)
                .filter(CONTROL_CONTEXT_KEYS::contains).findFirst().ifPresent(key -> {
                    throw badRequest(identity, "RG.TEST.CONTROL_IN_BUSINESS_CONTEXT",
                            "Execution controls must never enter business context.",
                            Map.of("field", key));
                });
        try {
            if (objectMapper.writeValueAsBytes(request.context()).length > MAX_CONTEXT_BYTES) {
                throw badRequest(identity, "RG.TEST.REQUEST_FIELD_TOO_LARGE",
                        "context exceeds the bounded durable creation protocol size.",
                        Map.of("field", "context", "maximumBytes", MAX_CONTEXT_BYTES));
            }
        } catch (JsonProcessingException invalid) {
            throw badRequest(identity, "RG.TEST.REQUEST_FIELD_INVALID",
                    "context cannot be serialized as protocol JSON.", Map.of("field", "context"));
        }
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN",
                    "Durable test creation is unavailable in this environment.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static DurableTestExecutionCheckpoint.Scope scope(
            IntegrationRequestContext identity) {
        return new DurableTestExecutionCheckpoint.Scope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.actorId());
    }

    private static IntegrationProblemException persistenceConflict(
            IntegrationRequestContext identity,
            DurableTestExecutionCheckpointConflictException conflict) {
        return switch (conflict.reason()) {
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.DURABLE_CREATE_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies different durable creation intent.",
                    Map.of());
            case LEASE_EXPIRED, STALE_FENCE, LEASE_ACTIVE -> conflict(identity,
                    "RG.TEST.DURABLE_CREATE_FENCE_CONFLICT",
                    "Durable creation ownership changed before the command could commit.",
                    Map.of("reason", conflict.reason().name()));
            default -> unavailable(identity, "RG.TEST.DURABLE_CREATE_STORE_UNAVAILABLE",
                    "Durable creation persistence rejected an invalid transition.");
        };
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String compactKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
