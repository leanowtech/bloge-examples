package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Thread-safe in-memory authoritative adapter for the API Resource domain.
 * It intentionally has no Spring, database, registry, HTTP, or projection
 * dependency, making revision and validation behavior directly testable.
 */
public final class InMemoryApiResourceModule implements ApiResourceModule {

    private static final Pattern HEADER_TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "proxy-authenticate", "cookie", "set-cookie",
            "host", "content-length", "connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "forwarded");
    private static final Set<String> SUPPORTED_TYPES = Set.of("string", "integer", "number", "boolean", "object");

    private final ObjectMapper mapper;
    private final Map<String, ApiResourceSpec> resources = new ConcurrentHashMap<>();

    /** Creates an adapter with a fresh JSON mapper. */
    public InMemoryApiResourceModule() {
        this(new ObjectMapper());
    }

    /**
     * @param mapper mapper used only for canonical content fingerprints
     */
    public InMemoryApiResourceModule(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper.copy();
    }

    @Override
    public synchronized ApiResourceSpec save(String resourceId, String resolvedConnectionId,
                                             ApiResourceCommand command, ExpectedRevision expected) {
        requireText(resourceId, "resourceId");
        requireText(resolvedConnectionId, "resolvedConnectionId");
        if (expected == null) invalid("expected revision is required");
        validate(command);

        ApiResourceSpec current = resources.get(resourceId);
        if (expected instanceof ExpectedRevision.Create) {
            if (current != null) {
                throw failure(ApiResourceAuthoringException.Code.ALREADY_EXISTS,
                        "API Resource already exists: " + resourceId);
            }
        } else if (expected instanceof ExpectedRevision.Match match) {
            if (current == null) {
                throw failure(ApiResourceAuthoringException.Code.NOT_FOUND,
                        "API Resource not found: " + resourceId);
            }
            if (current.revision() != match.revision()) {
                throw failure(ApiResourceAuthoringException.Code.CAS_MISMATCH,
                        "API Resource revision does not match: " + resourceId);
            }
        } else {
            invalid("unsupported expected revision");
        }

        int revision = current == null ? 1 : current.revision() + 1;
        String fingerprint = fingerprint(resourceId, resolvedConnectionId, command);
        ApiResourceSpec next = new ApiResourceSpec(resourceId, resolvedConnectionId, revision,
                fingerprint, command);
        resources.put(resourceId, next);
        return copy(next);
    }

    @Override
    public synchronized Optional<ApiResourceSpec> get(String resourceId) {
        requireText(resourceId, "resourceId");
        return Optional.ofNullable(resources.get(resourceId)).map(this::copy);
    }

    private ApiResourceSpec copy(ApiResourceSpec spec) {
        return new ApiResourceSpec(spec.resourceId(), spec.resolvedConnectionId(), spec.revision(),
                spec.fingerprint(), spec.command());
    }

    private void validate(ApiResourceCommand command) {
        if (command == null) invalid("command is required");
        if (blank(command.displayName())) invalid("displayName is required");
        if (command.operation() == null) invalid("operation is required");
        if (command.contract() == null) invalid("contract is required");
        if (command.response() == null) invalid("response is required");
        if (command.effect() == null) invalid("effect is required");
        validateOperation(command.operation());
        validateContract(command.contract(), command.operation().bindings());
        validateResponse(command.response());
        validateEffect(command.operation().method(), command.effect());
        validateExamples(command.examples(), command.contract());
    }

    private void validateOperation(ApiResourceCommand.Operation operation) {
        String method = operation.method();
        if (method == null || !Set.of("GET", "POST", "PUT", "DELETE").contains(method)) {
            invalid("method must be GET, POST, PUT, or DELETE");
        }
        String path = operation.path();
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains(" ") || path.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            invalid("path must be relative");
        }
        if (operation.bindings() == null) invalid("bindings are required");
        for (ApiResourceCommand.Binding binding : operation.bindings()) {
            if (binding == null || binding.from() == null || !binding.from().matches("^\\$\\.[A-Za-z0-9_-]+$")) {
                invalid("binding input path must be a first-level JSON path");
            }
            if (binding.to() == null || binding.to().location() == null || binding.to().name() == null) {
                invalid("binding target is required");
            }
            String location = binding.to().location();
            if (!Set.of("PATH", "QUERY", "HEADER", "BODY").contains(location)) {
                invalid("binding location is unsupported");
            }
            if (!binding.to().name().matches("^[A-Za-z0-9._~-]+$") && !"HEADER".equals(location)) {
                invalid("binding name is invalid");
            }
            if ("HEADER".equals(location)) validateHeader(binding.to().name());
        }
    }

    private void validateContract(ApiResourceCommand.Contract contract, List<ApiResourceCommand.Binding> bindings) {
        validateSchema(contract.input(), "input");
        validateSchema(contract.output(), "output");
        Set<String> inputNames = schemaProperties(contract.input());
        for (ApiResourceCommand.Binding binding : bindings) {
            String name = binding.from().substring(2);
            if (!inputNames.contains(name)) invalid("binding input path does not exist: " + binding.from());
        }
    }

    private void validateSchema(SchemaEnvelope envelope, String label) {
        if (envelope == null || !SchemaEnvelope.JSON_SCHEMA.equals(envelope.format())
                || !"2020-12".equals(envelope.version())) invalid(label + " schema envelope is unsupported");
        Map<String, Object> schema = envelope.schema();
        if (!"object".equals(schema.get("type")) || !(schema.get("properties") instanceof Map<?, ?>)
                || !(schema.get("required") instanceof List<?>)) {
            invalid(label + " schema must be a first-level object with properties and required");
        }
        if (schema.containsKey("additionalProperties") && !Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            invalid(label + " schema additionalProperties must be false");
        }
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            if (!(property.getKey() instanceof String) || !(property.getValue() instanceof Map<?, ?> definition)) {
                invalid(label + " schema property definition is unsupported");
            }
            Object type = ((Map<?, ?>) property.getValue()).get("type");
            if (!(type instanceof String) || !SUPPORTED_TYPES.contains(type)
                    || ((Map<?, ?>) property.getValue()).keySet().stream()
                    .anyMatch(key -> !"type".equals(key))) {
                invalid(label + " schema property shape is unsupported");
            }
        }
        Set<String> names = new HashSet<>();
        for (Object required : (List<?>) schema.get("required")) {
            if (!(required instanceof String) || !names.add((String) required) || !properties.containsKey(required)) {
                invalid(label + " schema required entry is invalid");
            }
        }
    }

    private Set<String> schemaProperties(SchemaEnvelope envelope) {
        Map<?, ?> properties = (Map<?, ?>) envelope.schema().get("properties");
        Set<String> names = new HashSet<>();
        properties.keySet().forEach(key -> names.add(String.valueOf(key)));
        return names;
    }

    private void validateResponse(ApiResourceCommand.Response response) {
        if (response.success() == null) invalid("response success is required");
        if (response.outputPath() != null && !response.outputPath().matches("^\\$\\.[A-Za-z0-9._~-]+$|^\\$$")) {
            invalid("response outputPath is invalid");
        }
        if (response.success() instanceof ApiResourceCommand.HttpStatus status) {
            if (status.codes().isEmpty() || status.codes().stream().anyMatch(code -> code == null || code < 100 || code > 599)) {
                invalid("HTTP success codes are invalid");
            }
        } else if (response.success() instanceof ApiResourceCommand.BodyMatch bodyMatch) {
            if (bodyMatch.path() == null || !bodyMatch.path().startsWith("$.") || bodyMatch.values().isEmpty()) {
                invalid("body match response is invalid");
            }
        } else {
            invalid("response success shape is unsupported");
        }
    }

    private void validateEffect(String method, ApiResourceCommand.Effect effect) {
        if ("GET".equals(method) && effect != ApiResourceCommand.Effect.READ_ONLY) {
            invalid("GET resources must be READ_ONLY");
        }
        if (!"GET".equals(method) && effect == ApiResourceCommand.Effect.READ_ONLY) {
            invalid("write methods must use FIXTURE_ONLY_WRITE or MANAGED_WRITE");
        }
    }

    private void validateExamples(List<ApiResourceCommand.Example> examples, ApiResourceCommand.Contract contract) {
        if (examples == null || examples.isEmpty()) invalid("at least one example is required");
        Set<String> names = new HashSet<>();
        for (ApiResourceCommand.Example example : examples) {
            if (example == null || blank(example.name()) || !names.add(example.name())) invalid("example names must be unique");
            validateExampleValue(example.input(), contract.input(), "input");
            validateExampleValue(example.output(), contract.output(), "output");
        }
    }

    private void validateExampleValue(JsonNode value, SchemaEnvelope envelope, String label) {
        if (value == null || !value.isObject()) invalid(label + " example must be an object");
        Map<?, ?> properties = (Map<?, ?>) envelope.schema().get("properties");
        List<?> required = (List<?>) envelope.schema().get("required");
        for (Object name : required) if (!value.has(String.valueOf(name))) invalid(label + " example misses required property");
        boolean additionalPropertiesAllowed = Boolean.TRUE.equals(envelope.schema().get("additionalProperties"));
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            Object definition = properties.get(field.getKey());
            if (definition == null) {
                if (!additionalPropertiesAllowed) invalid(label + " example contains unknown property");
                continue;
            }
            String type = String.valueOf(((Map<?, ?>) definition).get("type"));
            if (!matchesType(field.getValue(), type)) invalid(label + " example property type is invalid");
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    private void validateHeader(String header) {
        if (!HEADER_TOKEN.matcher(header).matches() || isReserved(header)) invalid("header is reserved or invalid");
    }

    private boolean isReserved(String header) {
        String lower = header.toLowerCase(java.util.Locale.ROOT);
        return RESERVED_HEADERS.contains(lower) || lower.startsWith("x-forwarded-");
    }

    private String fingerprint(String resourceId, String connectionId, ApiResourceCommand command) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("resourceId", resourceId);
        payload.put("resolvedConnectionId", connectionId);
        payload.set("command", mapper.valueToTree(command));
        try {
            byte[] bytes = canonicalize(payload).toString().getBytes(StandardCharsets.UTF_8);
            return "sha256:" + Hex.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw failure(ApiResourceAuthoringException.Code.VALIDATION, "unable to fingerprint resource content");
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) result.set(key, canonicalize(value.get(key)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (blank(value)) throw failure(ApiResourceAuthoringException.Code.VALIDATION, name + " is required");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void invalid(String message) {
        throw failure(ApiResourceAuthoringException.Code.VALIDATION, message);
    }

    private static ApiResourceAuthoringException failure(ApiResourceAuthoringException.Code code, String message) {
        return new ApiResourceAuthoringException(code, message);
    }

    private static final class Hex {
        private static final char[] DIGITS = "0123456789abcdef".toCharArray();

        private static String hex(byte[] bytes) {
            char[] output = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                output[i * 2] = DIGITS[value >>> 4];
                output[i * 2 + 1] = DIGITS[value & 0xf];
            }
            return new String(output);
        }
    }
}
