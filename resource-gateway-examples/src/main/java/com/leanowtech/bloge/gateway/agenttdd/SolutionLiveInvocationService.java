package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionInvocationService;
import com.leanowtech.bloge.gateway.solution.ops.OperationsInsightService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Executes only a current published Solution through a crash-safe, exact-replay boundary.
 *
 * <p>The caller owns Feature collection but never receives {@code AGENT_TDD_WRITE_EXEC}. This
 * service resolves a publication matching the complete executable closure and durably reserves
 * the exact Feature envelopes. The reservation owner then verifies and freezes those envelopes
 * before receiving an internally derived PLATFORM identity capable of reaching a WRITE
 * Instruction. Rejected envelopes are completed as a payload-free rejected replay and can never
 * strand an ambiguous external-effect marker.
 * Process loss leaves {@code RECOVERY_REQUIRED}; an ambiguous side effect is never retried.</p>
 */
public final class SolutionLiveInvocationService {
    /** Durable idempotency operation name for published runtime invocations. */
    public static final String OPERATION = "SOLUTION_RUNTIME_INVOKE";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AgentTddStateRepository states;
    private final SolutionInvocationService invocation;
    private final SolutionGovernanceService governance;
    private final ObjectMapper mapper;
    private final OperationsInsightService operations;

    /** Creates the live boundary from durable state, token verification and publication governance. */
    public SolutionLiveInvocationService(
            AgentTddStateRepository states,
            SolutionInvocationService invocation,
            SolutionGovernanceService governance,
            ObjectMapper mapper,
            OperationsInsightService operations) {
        this.states = Objects.requireNonNull(states, "states");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * Invokes one immutable publication and returns its exact result on an idempotent replay.
     *
     * @param solutionRef canonical Solution reference
     * @param suppliedInputs exact Feature value envelopes required by the Solution contract
     * @param idempotencyKey caller-stable key for this one intended business action
     * @param identity authenticated runtime Agent; its authority is never forwarded downstream
     * @return result, reasoning and immutable publication coordinates
     */
    public Map<String, Object> invoke(
            String solutionRef,
            JsonNode suppliedInputs,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        requireRuntimeAuthority(identity);
        String scope = AgentTddMutationService.scopeKey(identity);
        SolutionGovernanceService.CurrentPublication publication =
                governance.requireCurrentPublication(solutionRef, identity);
        String normalizedKey = required(idempotencyKey);
        String requestFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "publicationId", publication.publicationId(),
                "solutionRevision", publication.solutionRevision(),
                "solutionContractFingerprint", publication.solutionContractFingerprint(),
                "implementationFingerprint", publication.implementationFingerprint(),
                "featureEnvelopes", suppliedInputs,
                "actorType", identity.actorType(),
                "actorId", identity.actorId()), MAX_BYTES);
        AgentTddStateRepository.ExternalExecutionReservation reservation =
                states.reserveExternalExecution(scope, OPERATION, normalizedKey, requestFingerprint);
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.IN_PROGRESS) {
            throw new AgentTddToolException(
                    "SOLUTION_INVOCATION_RECOVERY_REQUIRED",
                    "The invocation outcome is ambiguous and requires operator recovery.");
        }
        if (reservation.status() == AgentTddStateRepository.ExternalExecutionStatus.COMPLETED) {
            Map<String, Object> replay = completedResponse(reservation.response());
            operations.record(scope, solutionRef, requestFingerprint, replay);
            return replay;
        }
        SolutionInvocationService.PreparedInvocation prepared;
        try {
            prepared = invocation.prepare(scope, solutionRef, suppliedInputs);
        } catch (SolutionContractException failure) {
            JsonNode rejected = mapper.createObjectNode().put("failureCode", failure.code());
            states.completeExternalExecution(
                    scope, OPERATION, normalizedKey, requestFingerprint, rejected);
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
        Map<String, Object> response;
        try {
            SolutionInvocationService.InvocationResult result = invocation.invokePublished(
                    scope, prepared, publication.runtimeSnapshot(), platformIdentity(identity));
            LinkedHashMap<String, Object> values = new LinkedHashMap<>(
                    mapper.convertValue(result, OBJECT_MAP));
            values.put("publicationId", publication.publicationId());
            values.put("implementationFingerprint", publication.implementationFingerprint());
            values.put("executionStatus", "COMPLETED");
            response = Map.copyOf(values);
        } catch (SolutionContractException failure) {
            throw new AgentTddToolException(failure.code(), failure.getMessage());
        }
        JsonNode completed = states.completeExternalExecution(
                scope, OPERATION, normalizedKey, requestFingerprint, mapper.valueToTree(response));
        operations.record(scope, solutionRef, requestFingerprint, response);
        return mapper.convertValue(completed, OBJECT_MAP);
    }

    private Map<String, Object> completedResponse(JsonNode response) {
        String failureCode = response.path("failureCode").asText();
        if (!failureCode.isBlank()) {
            throw new AgentTddToolException(
                    failureCode, "The same rejected Solution invocation was replayed.");
        }
        return mapper.convertValue(response, OBJECT_MAP);
    }

    private static IntegrationRequestContext platformIdentity(IntegrationRequestContext source) {
        return new IntegrationRequestContext(
                source.tenantId(), source.organizationId(), source.projectId(),
                source.environmentId(), source.region(), "PLATFORM", "solution-runtime",
                source.actorId(), "AGENT_TDD_WRITE_EXEC", source.correlationId());
    }

    private static void requireRuntimeAuthority(IntegrationRequestContext identity) {
        if (identity == null || !IntegrationOperation.AGENT_TDD_EXECUTE.accepts(identity.purpose())) {
            throw new AgentTddToolException(
                    "FORBIDDEN_PURPOSE", "Solution invocation requires runtime execution authority.");
        }
        identity.requireComplete();
    }

    private static String required(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AgentTddToolException(
                    "SCHEMA_NONCONFORMANT", "A runtime idempotency key is required.");
        }
        return normalized;
    }
}
