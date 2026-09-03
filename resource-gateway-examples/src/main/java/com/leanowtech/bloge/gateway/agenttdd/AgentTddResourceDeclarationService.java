package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Declares a read-only sandbox resource and its visual contract as one Agent TDD bridge action. */
@Service
public final class AgentTddResourceDeclarationService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private final WritableResourceRegistry resources;
    private final ResourceDesignContractRegistry contracts;
    private final AgentTddStateRepository states;
    private final AgentTddEgressHostPolicy egress;
    private final ObjectMapper mapper;

    /** Creates the declaration bridge over existing runtime and authoring registries. */
    public AgentTddResourceDeclarationService(WritableResourceRegistry resources,
                                              ResourceDesignContractRegistry contracts,
                                              AgentTddStateRepository states,
                                              AgentTddEgressHostPolicy egress,
                                              ObjectMapper mapper) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.states = Objects.requireNonNull(states, "states");
        this.egress = Objects.requireNonNull(egress, "egress");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Registers one read-only ResourceDescriptor and matching visual design contract idempotently.
     *
     * <p>The host must pass the shared exact allowlist before either registry is mutated. URL path
     * placeholders become required string inputs and runtime path mappings. Write methods remain
     * unavailable until a sandbox substitute and reconciliation proof are supplied by a later
     * governance contract.</p>
     *
     * @param arguments strict MCP declaration arguments
     * @param identity authenticated scope used for the idempotency coordinate
     * @return payload-free registration receipt
     */
    public Map<String, Object> declare(JsonNode arguments, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        String idempotencyKey = requiredText(arguments, "idempotencyKey");
        String requestFingerprint = com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint
                .fromCanonicalValue(mapper, arguments, MAX_BYTES);
        AtomicBoolean registered = new AtomicBoolean();
        try {
            JsonNode result = states.executeOnce(
                    AgentTddMutationService.scopeKey(identity), "rg.resource.declare",
                    idempotencyKey, requestFingerprint, () -> mapper.valueToTree(
                            declareOnce(arguments, registered)));
            return mapper.convertValue(result, OBJECT_MAP);
        } catch (RuntimeException failure) {
            if (registered.get()) {
                String resourceId = optionalText(arguments, "resourceId");
                try {
                    contracts.deleteByResourceId(resourceId);
                } finally {
                    resources.deregister(resourceId);
                }
            }
            throw failure;
        }
    }

    private Map<String, Object> declareOnce(JsonNode arguments, AtomicBoolean registered) {
        String resourceId = requiredText(arguments, "resourceId");
        if (!resourceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "resourceId has an unsupported format.");
        }
        String method = requiredText(arguments, "method").toUpperCase(Locale.ROOT);
        if (!READ_METHODS.contains(method)) {
            throw new AgentTddToolException(
                    "WRITE_EFFECT_NOT_ALLOWED", "Agent TDD resource declaration is read-only by default.");
        }
        String urlTemplate = requiredText(arguments, "urlTemplate");
        String host = egress.requireAllowed(urlTemplate);
        if (resources.contains(resourceId) || contracts.findByResourceId(resourceId).isPresent()) {
            throw new AgentTddToolException(
                    "GATE_REJECTED", "The resource or its design contract is already registered.");
        }
        JsonNode rawSchema = arguments.path("payloadSchema");
        if (!rawSchema.isObject()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "payloadSchema must be an object.");
        }
        Map<String, Object> payloadSchema = mapper.convertValue(rawSchema, OBJECT_MAP);
        if (!VisualSchemaValidator.validateSchema(payloadSchema, "/payloadSchema").isEmpty()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", "payloadSchema is not a valid JSON Schema.");
        }
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        Map<String, String> pathExpressions = new LinkedHashMap<>();
        Matcher matcher = PATH_PARAMETER.matcher(urlTemplate);
        while (matcher.find()) {
            String name = matcher.group(1);
            inputProperties.putIfAbsent(name, Map.of("type", "string"));
            pathExpressions.putIfAbsent(name, "ctx.params." + name);
        }
        ResourceDescriptor descriptor = new ResourceDescriptor(
                resourceId, urlTemplate, method, Map.of(), null, Duration.ofSeconds(30),
                new ParameterMapping(pathExpressions, Map.of(), Map.of(), Map.of(), null),
                new ResponseProtocol.HttpStatus(), null);
        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:" + resourceId, resourceId, resourceId,
                "Declared through the Agent TDD sandbox resource bridge.",
                List.of("agent-tdd", "sandbox"),
                SchemaEnvelope.object(inputProperties, List.copyOf(inputProperties.keySet())),
                new SchemaEnvelope("", "", payloadSchema), Map.of(), ResourceDesignContract.STATUS_ACTIVE);
        resources.register(descriptor);
        registered.set(true);
        contracts.upsert(contract);
        return Map.of("resourceId", resourceId, "registered", true, "host", host,
                "method", method, "contractId", contract.contractId());
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = optionalText(arguments, field);
        if (value.isBlank()) {
            throw new AgentTddToolException("SCHEMA_NONCONFORMANT", field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode arguments, String field) {
        return arguments != null && arguments.path(field).isTextual()
                ? arguments.path(field).asText().trim() : "";
    }
}
