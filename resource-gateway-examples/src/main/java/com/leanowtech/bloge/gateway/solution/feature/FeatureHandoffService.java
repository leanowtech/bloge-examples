package com.leanowtech.bloge.gateway.solution.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddMutationService;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.solution.FeatureContract;
import com.leanowtech.bloge.gateway.solution.FeatureEvaluationBackend;
import com.leanowtech.bloge.gateway.solution.SolutionContractException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;
import com.leanowtech.bloge.gateway.solution.SolutionValueSchemaValidator;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the scoped lifecycle that turns a design-only Feature into a verified runtime binding.
 *
 * <p>Agents can only submit a contract-first ticket. The separately authenticated feature
 * engineering boundary binds an evaluator and proves its output against a controlled fixture.
 * Failed verification deliberately leaves the ticket at {@code IMPLEMENTED} so the engineer can
 * repair the implementation without losing the original business contract.</p>
 */
@Service
public final class FeatureHandoffService {
    /** Durable asset kind shared by MCP submission and the engineering fulfillment endpoint. */
    public static final String FEATURE_HANDOFF = "SOLUTION_FEATURE_HANDOFF";
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final AgentTddStateRepository states;
    private final SolutionEntityRegistry registry;
    private final FeatureEvaluationBackend backend;
    private final ObjectMapper mapper;

    /** Creates the lifecycle boundary over durable state, canonical Features and one evaluator. */
    public FeatureHandoffService(AgentTddStateRepository states,
                                 SolutionEntityRegistry registry,
                                 FeatureEvaluationBackend backend,
                                 ObjectMapper mapper) {
        this.states = Objects.requireNonNull(states, "states");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Creates an {@code OPEN} ticket from the exact design-only Feature contract without granting
     * any implementation or execution authority.
     */
    public Map<String, Object> submit(String featureRef, IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_PROPOSE,
                "Authoring purpose is required.");
        String scope = AgentTddMutationService.scopeKey(identity);
        FeatureContract feature = requireFeature(scope, featureRef);
        if (!feature.speccing()) {
            throw new AgentTddToolException("GATE_REJECTED", "Feature is already bound.");
        }
        ObjectNode data = mapper.createObjectNode();
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, Map.of("scope", scope, "contract", feature.contractIdentity()), MAX_BYTES);
        data.put("ticketId", "feature-handoff:" + shortHash(fingerprint));
        data.put("featureName", feature.featureRef());
        data.set("requiredOutput", feature.output());
        data.set("requiredInputs", feature.inputs());
        data.put("evaluationKind", feature.evaluationKind().name());
        data.put("businessSemantics", feature.businessSemantics());
        data.put("status", "OPEN");
        data.put("acceptanceRef", "feature-acceptance:" + feature.featureRef());
        data.put("createdAt", Instant.now().toString());
        AgentTddStoredAsset stored = states.save(scope, FEATURE_HANDOFF, feature.featureRef(), data);
        return ticketProjection(stored);
    }

    /**
     * Binds an evaluator, records {@code IMPLEMENTED}, and promotes to {@code VERIFIED} only when
     * the controlled fixture result satisfies the declared Feature output contract.
     */
    public Map<String, Object> fulfil(String featureRef,
                                     String evaluationRef,
                                     JsonNode fixtureInputs,
                                     IntegrationRequestContext identity) {
        requirePurpose(identity, IntegrationOperation.AGENT_TDD_FEATURE_ENG,
                "Feature engineering purpose is required.");
        if (!"USER".equals(identity.actorType()) && !"HUMAN".equals(identity.actorType())) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "Feature fulfillment requires an accountable engineer.");
        }
        if (evaluationRef == null || evaluationRef.isBlank() || fixtureInputs == null
                || !fixtureInputs.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT",
                    "Evaluation reference and fixture inputs are required.");
        }
        String scope = AgentTddMutationService.scopeKey(identity);
        AgentTddStoredAsset current = states.find(scope, FEATURE_HANDOFF, featureRef)
                .orElseThrow(() -> new AgentTddToolException(
                        "REFERENCE_UNRESOLVED", "A Feature handoff is unavailable."));
        FeatureContract original = requireFeature(scope, featureRef);
        FeatureContract bound = new FeatureContract(original.featureRef(), original.output(),
                original.evaluationKind(), original.determinism(), original.inputs(), evaluationRef,
                original.componentRef(), original.promptRef(), original.businessSemantics(),
                original.businessDefinition(), original.display());

        AgentTddStoredAsset implemented = states.executeAtomically(() -> {
            states.lockRevision(scope, FEATURE_HANDOFF, featureRef, current.revision());
            registry.upsertFeature(scope, bound);
            ObjectNode data = current.data().deepCopy();
            data.put("status", "IMPLEMENTED");
            data.put("evaluationRef", evaluationRef.trim());
            data.put("implementedBy", identity.actorId());
            data.put("implementedAt", Instant.now().toString());
            return states.saveIfRevision(scope, FEATURE_HANDOFF, featureRef,
                    current.revision(), data);
        });

        JsonNode output;
        try {
            output = backend.evaluate(bound, fixtureInputs.deepCopy(), identity);
        } catch (SolutionContractException expected) {
            throw new AgentTddToolException(expected.code(), "Feature verification failed.");
        } catch (RuntimeException failure) {
            throw new AgentTddToolException("FEATURE_EVALUATION_FAILED", "Feature verification failed.");
        }
        if (!SolutionValueSchemaValidator.featureValueMatches(bound.output(), output)) {
            throw new AgentTddToolException("FEATURE_OUTPUT_INVALID",
                    "Feature verification output does not match the contract.");
        }
        ObjectNode verified = implemented.data().deepCopy();
        verified.put("status", "VERIFIED");
        verified.put("verified", true);
        verified.put("verifiedAt", Instant.now().toString());
        AgentTddStoredAsset stored = states.saveIfRevision(scope, FEATURE_HANDOFF, featureRef,
                implemented.revision(), verified);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(ticketProjection(stored));
        result.put("state", "READY");
        result.put("verified", true);
        return Map.copyOf(result);
    }

    private FeatureContract requireFeature(String scope, String featureRef) {
        try {
            return registry.requireFeature(scope, featureRef);
        } catch (SolutionEntityRegistry.EntityUnavailableException failure) {
            throw new AgentTddToolException("REFERENCE_UNRESOLVED", "A Feature is unavailable.");
        }
    }

    private Map<String, Object> ticketProjection(AgentTddStoredAsset stored) {
        JsonNode data = stored.data();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", data.path("ticketId").asText());
        result.put("featureName", data.path("featureName").asText());
        result.put("requiredOutput", data.path("requiredOutput"));
        result.put("requiredInputs", data.path("requiredInputs"));
        result.put("evaluationKind", data.path("evaluationKind").asText());
        result.put("businessSemantics", data.path("businessSemantics").asText());
        result.put("status", data.path("status").asText());
        result.put("acceptanceRef", data.path("acceptanceRef").asText());
        result.put("revision", stored.revision());
        return Map.copyOf(result);
    }

    private static void requirePurpose(IntegrationRequestContext identity,
                                       IntegrationOperation operation,
                                       String message) {
        if (identity == null || !operation.accepts(identity.purpose())) {
            throw new AgentTddToolException("FORBIDDEN_PURPOSE", message);
        }
        identity.requireComplete();
    }

    private static String shortHash(String fingerprint) {
        String hash = fingerprint.startsWith("sha256:") ? fingerprint.substring(7) : fingerprint;
        return hash.substring(0, Math.min(24, hash.length()));
    }
}
