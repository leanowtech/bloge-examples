package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Canonical, domain-aware JSON codec for the generic governed catalog.
 *
 * <p>{@link #encode(TrustedTenant, GovernedCatalogKind, String, long, Object)} accepts only
 * domain objects whose invariants have already been proven. Persistence restoration is a separate
 * trusted path: the catalog verifies the database record seal and row identity before decoding.</p>
 */
public final class GovernedCatalogCodec {
    public static final String ENVELOPE_VERSION = "rg.governed-resource.v1";
    private static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "envelopeVersion", "domainVersionIndependent", "tenantId", "kind", "id",
            "revision", "fingerprint", "payload");
    private static final Set<String> BINDING_FIELDS = Set.of(
            "provider", "apiVersion", "resourceId", "descriptorFingerprint",
            "providerOutputFingerprint", "contractId", "contractFingerprint");

    private final ObjectMapper mapper;

    public GovernedCatalogCodec(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_MAPPER");
        }
        this.mapper = mapper.copy()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.mapper.setConfig(this.mapper.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
    }

    public String encode(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                         long revision, Object value) {
        if (tenant == null || kind == null || value == null || !kind.accepts(value)
                || id == null || id.isBlank() || revision <= 0) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_ASSET");
        }
        String fingerprint = fingerprint(value);
        validateIdentity(kind, tenant.value(), id.trim(), revision, value, fingerprint);
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("envelopeVersion", ENVELOPE_VERSION);
        envelope.put("domainVersionIndependent", kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT);
        envelope.put("tenantId", tenant.value());
        envelope.put("kind", kind.name());
        envelope.put("id", id.trim());
        envelope.put("revision", revision);
        envelope.put("fingerprint", fingerprint);
        envelope.set("payload", payload(kind, value));
        return canonicalJson(envelope);
    }

    /** Verifies only the sealed envelope and its authoritative top-level identity. */
    public void preflight(String json, String tenant, GovernedCatalogKind kind, String id,
                          long revision, String fingerprint) {
        try {
            JsonNode parsed = mapper.readTree(json);
            if (!(parsed instanceof ObjectNode envelope)
                    || !hasExactlyFields(envelope, ENVELOPE_FIELDS)
                    || !canonicalJson(envelope).equals(json)
                    || !text(envelope, "envelopeVersion").equals(ENVELOPE_VERSION)
                    || !text(envelope, "tenantId").equals(tenant)
                    || !text(envelope, "kind").equals(kind.name())
                    || !text(envelope, "id").equals(id)
                    || !envelope.get("domainVersionIndependent").isBoolean()
                    || envelope.get("revision") == null || !envelope.get("revision").isIntegralNumber()
                    || envelope.get("revision").asLong(Long.MIN_VALUE) != revision
                    || !text(envelope, "fingerprint").equals(fingerprint)
                    || envelope.get("domainVersionIndependent").booleanValue() !=
                    (kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT)) {
                throw new GovernedCatalogIntegrityException();
            }
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    public Object decode(String json, TrustedTenant tenant, GovernedCatalogKind kind, String id,
                         long revision, String fingerprint,
                         Function<GovernedResourceRef, ResourceWorldModel> worldResolver) {
        try {
            JsonNode parsed = mapper.readTree(json);
            if (!(parsed instanceof ObjectNode envelope)
                    || !hasExactlyFields(envelope, ENVELOPE_FIELDS)
                    || !canonicalJson(envelope).equals(json)
                    || !text(envelope, "envelopeVersion").equals(ENVELOPE_VERSION)
                    || !text(envelope, "tenantId").equals(tenant.value())
                    || !text(envelope, "kind").equals(kind.name())
                    || !text(envelope, "id").equals(id)
                    || !envelope.get("domainVersionIndependent").isBoolean()
                    || envelope.get("revision") == null || !envelope.get("revision").isIntegralNumber()
                    || envelope.get("revision").asLong(Long.MIN_VALUE) != revision
                    || !text(envelope, "fingerprint").equals(fingerprint)
                    || envelope.get("domainVersionIndependent").booleanValue() !=
                    (kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT)) {
                throw new GovernedCatalogIntegrityException();
            }
            JsonNode payload = envelope.get("payload");
            Object value = decodePayload(kind, payload, tenant, worldResolver);
            String recomputed = fingerprint(value);
            validateIdentity(kind, tenant.value(), id, revision, value, recomputed);
            if (!recomputed.equals(fingerprint) || !payloadFingerprint(kind, payload).equals(recomputed)) {
                throw new GovernedCatalogIntegrityException();
            }
            return value;
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new GovernedCatalogIntegrityException();
        } catch (GovernedCatalogDependencyAbortException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    public String fingerprint(Object value) {
        if (value instanceof LogicalResourceContract contract) {
            return contract.contractFingerprint();
        }
        if (value instanceof ResourceWorldModel world) {
            return world.fingerprint();
        }
        if (value instanceof Scenario scenario) {
            return scenario.fingerprint();
        }
        throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_ASSET");
    }

    /** Returns the canonical record seal for the complete stored envelope JSON. */
    public String recordFingerprint(String json) {
        String canonical = canonicalize(json);
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    /** Canonicalizes a JSON envelope without decoding its domain payload. */
    public String canonicalize(String json) {
        try {
            JsonNode parsed = mapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new GovernedCatalogIntegrityException();
            }
            return canonicalJson(parsed);
        } catch (GovernedCatalogIntegrityException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private ObjectNode payload(GovernedCatalogKind kind, Object value) {
        if (kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT) {
            return contractPayload((LogicalResourceContract) value);
        }
        if (kind == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
            return worldPayload((ResourceWorldModel) value);
        }
        return scenarioPayload((Scenario) value);
    }

    private Object decodePayload(GovernedCatalogKind kind, JsonNode payload, TrustedTenant tenant,
                                 Function<GovernedResourceRef, ResourceWorldModel> worldResolver) {
        if (!(payload instanceof ObjectNode)) {
            throw new GovernedCatalogIntegrityException();
        }
        if (kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT) {
            return decodeContract(payload);
        }
        if (kind == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
            return decodeWorld(payload);
        }
        return decodeScenario(payload, tenant, worldResolver);
    }

    private ObjectNode contractPayload(LogicalResourceContract value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("contractId", value.contractId());
        node.put("contractFingerprint", value.contractFingerprint());
        node.set("inputShape", schema(value.inputShape()));
        node.set("outputShape", schema(value.outputShape()));
        node.set("semantics", semantics(value.semantics()));
        return node;
    }

    private ObjectNode worldPayload(ResourceWorldModel value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("worldModelId", value.worldModelId());
        node.put("tenantId", value.tenantId());
        node.put("revision", value.revision());
        node.put("fingerprint", value.fingerprint());
        ArrayNode slices = node.putArray("slices");
        for (WorldSlice slice : value.slices()) {
            ObjectNode item = slices.addObject();
            item.put("tenantId", slice.tenantId());
            item.put("provider", slice.provider());
            item.put("apiVersion", slice.apiVersion());
            item.put("logicalContractId", slice.logicalContractId());
            item.put("contractFingerprint", slice.contractFingerprint());
            item.put("bindingFingerprint", slice.bindingFingerprint());
            item.put("bindingValid", slice.bindingValid());
            item.set("contract", contractPayload(slice.contract()));
            item.set("binding", bindingPayload(slice.binding()));
            item.set("behavior", fragmentPayload(slice.behavior()));
            item.put("fingerprint", slice.fingerprint());
            item.put("state", "EMPTY");
        }
        return node;
    }

    private ObjectNode scenarioPayload(Scenario value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("scenarioId", value.scenarioId());
        node.put("tenantId", value.tenantId());
        node.put("revision", value.revision());
        node.put("fingerprint", value.fingerprint());
        ObjectNode target = node.putObject("target");
        target.put("kind", value.target().kind());
        target.put("id", value.target().id());
        target.put("fingerprint", value.target().fingerprint());
        ObjectNode world = node.putObject("world");
        world.put("worldModelId", value.world().worldModelId());
        world.put("revision", value.world().revision());
        world.put("fingerprint", value.world().fingerprint());
        node.set("context", mapper.valueToTree(value.context()));
        node.put("stateInit", value.stateInit().name());
        ArrayNode expectations = node.putArray("expect");
        for (Scenario.Expectation expectation : value.expect()) {
            ObjectNode item = expectations.addObject();
            item.put("scope", expectation.scope());
            item.put("nodeId", expectation.nodeId());
            item.put("path", expectation.path());
            item.put("operator", expectation.operator());
            item.set("expected", mapper.valueToTree(expectation.expected()));
            if (expectation.numericTolerance() == null) {
                item.putNull("numericTolerance");
            } else {
                item.put("numericTolerance", expectation.numericTolerance());
            }
        }
        ArrayNode dependencies = node.putArray("contractDependencies");
        for (Scenario.ContractDependency dependency : value.contractDependencies()) {
            dependencies.addObject()
                    .put("contractId", dependency.contractId())
                    .put("baselineFingerprint", dependency.baselineFingerprint());
        }
        return node;
    }

    private ObjectNode schema(SchemaEnvelope value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("format", value.format());
        node.put("version", value.version());
        node.set("schema", mapper.valueToTree(value.schema()));
        return node;
    }

    private ObjectNode semantics(ResponseSemantics value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putObject("successCondition")
                .put("knowledge", value.successCondition().knowledge().name())
                .put("expression", value.successCondition().expression());
        ObjectNode errors = node.putObject("errorClassification");
        errors.put("knowledge", value.errorClassification().knowledge().name());
        errors.set("categories", mapper.valueToTree(value.errorClassification().categories()));
        node.put("idempotency", value.idempotency().name());
        node.put("retryability", value.retryability().name());
        return node;
    }

    private ObjectNode bindingPayload(LogicalResourceBinding value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("provider", value.provider());
        node.put("apiVersion", value.apiVersion());
        node.put("resourceId", value.resourceId());
        node.put("descriptorFingerprint", value.descriptorFingerprint());
        node.put("providerOutputFingerprint", value.providerOutputFingerprint());
        node.put("contractId", value.contractId());
        node.put("contractFingerprint", value.contractFingerprint());
        return node;
    }

    private ObjectNode fragmentPayload(BlogeFragmentRef value) {
        return JsonNodeFactory.instance.objectNode()
                .put("artifactId", value.artifactId())
                .put("revision", value.revision())
                .put("source", value.source())
                .put("outputNodeId", value.outputNodeId())
                .put("fingerprint", value.fingerprint());
    }

    private LogicalResourceContract decodeContract(JsonNode node) {
        LogicalResourceContract value = new LogicalResourceContract(
                text(node, "contractId"), decodeSchema(node.get("inputShape")),
                decodeSchema(node.get("outputShape")), decodeSemantics(node.get("semantics")));
        if (!text(node, "contractFingerprint").equals(value.contractFingerprint())) {
            throw new GovernedCatalogIntegrityException();
        }
        return value;
    }

    private ResourceWorldModel decodeWorld(JsonNode node) {
        String tenantId = text(node, "tenantId");
        long revision = longValue(node, "revision");
        List<WorldSlice> slices = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "slices")) {
            LogicalResourceContract contract = decodeContract(item.get("contract"));
            LogicalResourceBinding binding = decodeBinding(item.get("binding"), contract);
            BlogeFragmentRef behavior = BlogeFragmentRef.frozen(
                    text(item.get("behavior"), "artifactId"),
                    longValue(item.get("behavior"), "revision"),
                    text(item.get("behavior"), "source"),
                    optionalText(item.get("behavior"), "outputNodeId"));
            ObjectNode behaviorNode = object(item.get("behavior"));
            if (!text(behaviorNode, "fingerprint").equals(behavior.fingerprint())) {
                throw new GovernedCatalogIntegrityException();
            }
            if (!text(item, "tenantId").equals(tenantId)
                    || !text(item, "logicalContractId").equals(contract.contractId())
                    || !text(item, "contractFingerprint").equals(contract.contractFingerprint())
                    || !text(item, "bindingFingerprint").equals(binding.descriptorFingerprint())
                    || !text(item, "provider").equals(binding.provider())
                    || !text(item, "apiVersion").equals(binding.apiVersion())
                    || !item.path("bindingValid").asBoolean()
                    || !"EMPTY".equals(text(item, "state"))) {
                throw new GovernedCatalogIntegrityException();
            }
            WorldSlice slice = WorldSlice.register(
                    new WorldSlice.Registration(tenantId, text(item, "provider"), text(item, "apiVersion"),
                            contract.contractId(), contract.contractFingerprint(),
                            binding.descriptorFingerprint(), true),
                    contract, binding, behavior, StateSpec.empty());
            if (!text(item, "fingerprint").equals(slice.fingerprint())) {
                throw new GovernedCatalogIntegrityException();
            }
            slices.add(slice);
        }
        ResourceWorldModel value = new ResourceWorldModel(text(node, "worldModelId"), tenantId,
                revision, slices);
        if (!text(node, "fingerprint").equals(value.fingerprint())) {
            throw new GovernedCatalogIntegrityException();
        }
        return value;
    }

    private Scenario decodeScenario(JsonNode node, TrustedTenant tenant,
                                    Function<GovernedResourceRef, ResourceWorldModel> worldResolver) {
        String worldId = text(node.get("world"), "worldModelId");
        long worldRevision = longValue(node.get("world"), "revision");
        String worldFingerprint = text(node.get("world"), "fingerprint");
        ResourceWorldModel worldModel = worldResolver.apply(new GovernedResourceRef(
                tenant, GovernedCatalogKind.RESOURCE_WORLD_MODEL, worldId, worldRevision, worldFingerprint));
        List<Scenario.Expectation> expectations = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "expect")) {
            Double tolerance = item.get("numericTolerance") == null || item.get("numericTolerance").isNull()
                    ? null : item.get("numericTolerance").doubleValue();
            expectations.add(new Scenario.Expectation(text(item, "scope"), optionalText(item, "nodeId"),
                    optionalText(item, "path"), text(item, "operator"),
                    mapper.convertValue(item.get("expected"), Object.class), tolerance));
        }
        List<Scenario.ContractDependency> dependencies = new ArrayList<>();
        for (JsonNode item : requiredArray(node, "contractDependencies")) {
            dependencies.add(new Scenario.ContractDependency(text(item, "contractId"),
                    text(item, "baselineFingerprint")));
        }
        JsonNode target = node.get("target");
        Scenario value = new Scenario(text(node, "scenarioId"), tenant.value(), longValue(node, "revision"),
                new Scenario.TargetRef(text(target, "kind"), text(target, "id"), text(target, "fingerprint")),
                new Scenario.WorldModelRef(worldId, worldRevision, worldFingerprint), worldModel,
                mapper.convertValue(node.get("context"), new TypeReference<Map<String, Object>>() {}),
                Scenario.WorldStateInit.valueOf(text(node, "stateInit")), expectations, dependencies);
        if (!text(node, "tenantId").equals(value.tenantId())
                || !text(node, "fingerprint").equals(value.fingerprint())) {
            throw new GovernedCatalogIntegrityException();
        }
        return value;
    }

    private LogicalResourceBinding decodeBinding(JsonNode node, LogicalResourceContract exactContract) {
        if (!(node instanceof ObjectNode binding) || !hasExactlyFields(binding, BINDING_FIELDS)) {
            throw new GovernedCatalogIntegrityException();
        }
        try {
            return LogicalResourceBinding.restorePersisted(text(node, "provider"), text(node, "apiVersion"),
                    text(node, "resourceId"), text(node, "descriptorFingerprint"),
                    text(node, "providerOutputFingerprint"), text(node, "contractId"),
                    text(node, "contractFingerprint"), exactContract);
        } catch (RuntimeException exception) {
            throw new GovernedCatalogIntegrityException();
        }
    }

    private SchemaEnvelope decodeSchema(JsonNode node) {
        return new SchemaEnvelope(text(node, "format"), text(node, "version"),
                mapper.convertValue(node.get("schema"), new TypeReference<Map<String, Object>>() {}));
    }

    private ResponseSemantics decodeSemantics(JsonNode node) {
        JsonNode success = node.get("successCondition");
        JsonNode errors = node.get("errorClassification");
        Map<String, List<String>> categories = mapper.convertValue(errors.get("categories"),
                new TypeReference<Map<String, List<String>>>() {});
        return new ResponseSemantics(
                new ResponseSemantics.SuccessCondition(
                        ResponseSemantics.Knowledge.valueOf(text(success, "knowledge")),
                        optionalText(success, "expression")),
                new ResponseSemantics.ErrorClassification(
                        ResponseSemantics.Knowledge.valueOf(text(errors, "knowledge")), categories),
                ResponseSemantics.Idempotency.valueOf(text(node, "idempotency")),
                ResponseSemantics.Retryability.valueOf(text(node, "retryability")));
    }

    private String payloadFingerprint(GovernedCatalogKind kind, JsonNode payload) {
        return text(payload, kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT
                ? "contractFingerprint" : "fingerprint");
    }

    private void validateIdentity(GovernedCatalogKind kind, String tenant, String id, long revision,
                                  Object value, String fingerprint) {
        if (!kind.accepts(value)) {
            throw new GovernedCatalogIntegrityException();
        }
        if (kind == GovernedCatalogKind.LOGICAL_RESOURCE_CONTRACT
                && !id.equals(((LogicalResourceContract) value).contractId())) {
            throw new GovernedCatalogIntegrityException();
        }
        if (kind == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
            ResourceWorldModel world = (ResourceWorldModel) value;
            if (!id.equals(world.worldModelId()) || !tenant.equals(world.tenantId())
                    || world.revision() != revision || !fingerprint.equals(world.fingerprint())) {
                throw new GovernedCatalogIntegrityException();
            }
        }
        if (kind == GovernedCatalogKind.SCENARIO) {
            Scenario scenario = (Scenario) value;
            if (!id.equals(scenario.scenarioId()) || !tenant.equals(scenario.tenantId())
                    || scenario.revision() != revision || !fingerprint.equals(scenario.fingerprint())) {
                throw new GovernedCatalogIntegrityException();
            }
        }
    }

    private String canonicalJson(JsonNode value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(sorted(value));
            if (bytes.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("RG.WORLD.CATALOG.SIZE_LIMIT");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_JSON", exception);
        }
    }

    private JsonNode sorted(JsonNode value) {
        if (value == null || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> array.add(sorted(item)));
            return array;
        }
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        List<String> names = new ArrayList<>();
        value.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            object.set(name, sorted(value.get(name)));
        }
        return object;
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode node)) {
            throw new GovernedCatalogIntegrityException();
        }
        return node;
    }

    private static boolean hasExactlyFields(ObjectNode object, Set<String> expected) {
        java.util.Set<String> actual = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static List<JsonNode> requiredArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (!(value instanceof ArrayNode)) {
            throw new GovernedCatalogIntegrityException();
        }
        List<JsonNode> values = new ArrayList<>();
        value.forEach(values::add);
        return values;
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new GovernedCatalogIntegrityException();
        }
        return value.asLong(Long.MIN_VALUE);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new GovernedCatalogIntegrityException();
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new GovernedCatalogIntegrityException();
        }
        return value.textValue();
    }
}
