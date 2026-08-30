package com.leanowtech.bloge.gateway.visual.authoring.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Stateless, injectable API Resource decision engine: validation, CAS, revision and fingerprint. */
public final class ApiResourceDecisions {
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    private static final Pattern HEADER_TOKEN = Pattern.compile("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$");
    private static final Pattern OPERATION_PATH = Pattern.compile("^/[A-Za-z0-9._~:/{}-]*$");
    private static final Pattern JSON_PATH = Pattern.compile("^\\$\\.[A-Za-z0-9_-]+$");
    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> RESERVED_HEADERS = Set.of("authorization", "proxy-authorization", "proxy-authenticate", "cookie", "set-cookie", "host", "content-length", "connection", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade", "forwarded");
    private static final Set<String> SUPPORTED_TYPES = Set.of("string", "integer", "number", "boolean", "object");
    private final ObjectMapper mapper;

    /** Uses a fresh mapper for canonical fingerprints. */
    public ApiResourceDecisions() { this(new ObjectMapper()); }
    /** @param mapper mapper used for canonical command fingerprints */
    public ApiResourceDecisions(ObjectMapper mapper) { this.mapper = mapper == null ? new ObjectMapper() : mapper.copy(); }

    /** Applies all authoritative resource decisions without mutating the current value. */
    public ApiResourceSpec next(Optional<ApiResourceSpec> currentValue, String resourceId, String connectionId,
                         ApiResourceCommand command, ExpectedRevision expected) {
        requireIdentifier(resourceId, "resourceId"); requireIdentifier(connectionId, "connectionId");
        if (expected == null) invalid("expected revision is required"); validate(command);
        ApiResourceSpec current = currentValue == null ? null : currentValue.orElse(null);
        if (expected instanceof ExpectedRevision.Create) {
            if (current != null) throw failure(ApiResourceAuthoringException.Code.ALREADY_EXISTS, "API Resource already exists: " + resourceId);
        } else if (expected instanceof ExpectedRevision.Match match) {
            if (current == null) throw failure(ApiResourceAuthoringException.Code.NOT_FOUND, "API Resource not found: " + resourceId);
            if (current.revision() != match.revision()) throw failure(ApiResourceAuthoringException.Code.CAS_MISMATCH, "API Resource revision does not match: " + resourceId);
        } else invalid("unsupported expected revision");
        int revision = current == null ? 1 : current.revision() + 1;
        return new ApiResourceSpec(ApiResourceSpec.SCHEMA_VERSION, resourceId, revision, fingerprint(resourceId, connectionId, command), command.displayName(), command.description(), connectionId, command.operation(), command.contract(), command.response(), command.effect(), command.examples(), ApiResourceSpec.DRAFT);
    }

    /** Validates an identifier for read-only callers using the same policy as save. */
    void validateResourceId(String resourceId) { requireIdentifier(resourceId, "resourceId"); }

    private void validate(ApiResourceCommand command) {
        if (command == null) invalid("command is required"); if (blank(command.displayName()) || command.displayName().length() > 200) invalid("displayName is invalid"); if (command.description() != null && command.description().length() > 2000) invalid("description is invalid"); if (command.operation() == null) invalid("operation is required"); if (command.contract() == null) invalid("contract is required"); if (command.response() == null) invalid("response is required"); if (command.effect() == null) invalid("effect is required");
        validateOperation(command.operation()); validateContract(command.contract(), command.operation().bindings()); validateResponse(command.response()); validateEffect(command.operation().method(), command.effect()); validateExamples(command.examples(), command.contract());
    }
    private void validateOperation(ApiResourceCommand.Operation operation) {
        if (operation.method() == null || !Set.of("GET", "POST", "PUT", "DELETE").contains(operation.method())) invalid("method must be GET, PUT, POST, or DELETE"); if (operation.path() == null || operation.path().length() > 2048 || !OPERATION_PATH.matcher(operation.path()).matches()) invalid("path must match the relative operation path contract"); if (operation.bindings() == null) invalid("bindings are required"); if (operation.bindings().stream().filter(b -> b != null && b.to() != null && "BODY".equals(b.to().location())).count() > 1) invalid("at most one BODY binding is allowed");
        for (ApiResourceCommand.Binding binding : operation.bindings()) { if (binding == null || binding.from() == null || !JSON_PATH.matcher(binding.from()).matches()) invalid("binding input path must be a first-level JSON path"); if (binding.to() == null || binding.to().location() == null || binding.to().name() == null) invalid("binding target is required"); String location = binding.to().location(); if (!Set.of("PATH", "QUERY", "HEADER", "BODY").contains(location)) invalid("binding location is unsupported"); if (!binding.to().name().matches("^[A-Za-z0-9._~-]+$") && !"HEADER".equals(location)) invalid("binding name is invalid"); if ("HEADER".equals(location)) validateHeader(binding.to().name()); }
    }
    private void validateContract(ApiResourceCommand.Contract contract, List<ApiResourceCommand.Binding> bindings) { validateSchema(contract.input(), "input"); validateSchema(contract.output(), "output"); Set<String> names = schemaProperties(contract.input()); for (ApiResourceCommand.Binding binding : bindings) if (!names.contains(binding.from().substring(2))) invalid("binding input path does not exist: " + binding.from()); }
    private void validateSchema(SchemaEnvelope envelope, String label) {
        if (envelope == null || !SchemaEnvelope.JSON_SCHEMA.equals(envelope.format()) || !"2020-12".equals(envelope.version())) invalid(label + " schema envelope is unsupported"); Map<String, Object> schema = envelope.schema(); if (!"object".equals(schema.get("type")) || !(schema.get("properties") instanceof Map<?, ?>) || !(schema.get("required") instanceof List<?>)) invalid(label + " schema must be a first-level object with properties and required"); if (schema.containsKey("additionalProperties") && !Boolean.FALSE.equals(schema.get("additionalProperties"))) invalid(label + " schema additionalProperties must be false"); Map<?, ?> properties = (Map<?, ?>) schema.get("properties"); for (Map.Entry<?, ?> property : properties.entrySet()) { if (!(property.getKey() instanceof String) || !(property.getValue() instanceof Map<?, ?> definition)) invalid(label + " schema property definition is unsupported"); Object type = ((Map<?, ?>) property.getValue()).get("type"); if (!(type instanceof String) || !SUPPORTED_TYPES.contains(type) || ((Map<?, ?>) property.getValue()).keySet().stream().anyMatch(key -> !"type".equals(key))) invalid(label + " schema property shape is unsupported"); } Set<String> names = new HashSet<>(); for (Object required : (List<?>) schema.get("required")) if (!(required instanceof String) || !names.add((String) required) || !properties.containsKey(required)) invalid(label + " schema required entry is invalid");
    }
    private Set<String> schemaProperties(SchemaEnvelope envelope) { Set<String> names = new HashSet<>(); ((Map<?, ?>) envelope.schema().get("properties")).keySet().forEach(k -> names.add(String.valueOf(k))); return names; }
    private void validateResponse(ApiResourceCommand.Response response) { if (response.success() == null) invalid("response success is required"); if (response.outputPath() != null && !response.outputPath().matches("^\\$\\.[A-Za-z0-9._~-]+$|^\\$$")) invalid("response outputPath is invalid"); if (response.success() instanceof ApiResourceCommand.HttpStatus status) { if (status.codes().isEmpty() || status.codes().stream().anyMatch(code -> code == null || code < 100 || code > 599)) invalid("HTTP success codes are invalid"); } else if (response.success() instanceof ApiResourceCommand.BodyMatch bodyMatch) { if (bodyMatch.path() == null || !bodyMatch.path().startsWith("$.") || bodyMatch.values().isEmpty() || bodyMatch.values().stream().anyMatch(java.util.Objects::isNull)) invalid("body match response is invalid"); } else invalid("response success shape is unsupported"); }
    private void validateEffect(String method, ApiResourceCommand.Effect effect) { if ("GET".equals(method) && !(effect instanceof ApiResourceCommand.Effect.ReadOnly)) invalid("GET resources must be READ_ONLY"); if (!"GET".equals(method) && effect instanceof ApiResourceCommand.Effect.ReadOnly) invalid("write methods must use FIXTURE_ONLY_WRITE or MANAGED_WRITE"); if (effect instanceof ApiResourceCommand.Effect.ManagedWrite managed) validateManagedWrite(managed); }
    private void validateManagedWrite(ApiResourceCommand.Effect.ManagedWrite managed) { if (managed.receipt() == null || !validHeader(managed.idempotencyHeader())) invalid("managed write idempotency and receipt contract is required"); ApiResourceCommand.Effect.Receipt receipt = managed.receipt(); if (!validJsonPath(receipt.idPath()) || !validJsonPath(receipt.statusPath()) || receipt.succeededValues().isEmpty() || receipt.failedValues().isEmpty() || receipt.succeededValues().stream().anyMatch(java.util.Objects::isNull) || receipt.failedValues().stream().anyMatch(java.util.Objects::isNull)) invalid("managed write receipt contract is invalid"); ApiResourceCommand.Effect.Reconciliation reconciliation = managed.reconciliation(); if (reconciliation != null) { ApiResourceSpec.ResourceRef resource = reconciliation.resource(); if (resource == null || !"API_RESOURCE".equals(resource.kind()) || !validIdentifier(resource.resourceId()) || resource.revision() < 1 || !FINGERPRINT.matcher(String.valueOf(resource.fingerprint())).matches() || !validJsonPath(reconciliation.receiptIdInputPath())) invalid("managed write reconciliation contract is invalid"); } }
    private void validateExamples(List<ApiResourceCommand.Example> examples, ApiResourceCommand.Contract contract) { if (examples == null || examples.isEmpty()) invalid("at least one example is required"); Set<String> names = new HashSet<>(); for (ApiResourceCommand.Example example : examples) { if (example == null || !validIdentifier(example.name()) || !names.add(example.name())) invalid("example names must be unique valid identifiers"); validateExampleValue(example.input(), contract.input(), "input"); validateExampleValue(example.output(), contract.output(), "output"); } }
    private void validateExampleValue(JsonNode value, SchemaEnvelope envelope, String label) { if (value == null || !value.isObject()) invalid(label + " example must be an object"); Map<?, ?> properties = (Map<?, ?>) envelope.schema().get("properties"); List<?> required = (List<?>) envelope.schema().get("required"); for (Object name : required) if (!value.has(String.valueOf(name))) invalid(label + " example misses required property"); Iterator<Map.Entry<String, JsonNode>> fields = value.fields(); while (fields.hasNext()) { Map.Entry<String, JsonNode> field = fields.next(); Object definition = properties.get(field.getKey()); if (definition == null) { if (!Boolean.TRUE.equals(envelope.schema().get("additionalProperties"))) invalid(label + " example contains unknown property"); continue; } String type = String.valueOf(((Map<?, ?>) definition).get("type")); if (!matchesType(field.getValue(), type)) invalid(label + " example property type is invalid"); } }
    private boolean matchesType(JsonNode value, String type) { return switch (type) { case "string" -> value.isTextual(); case "integer" -> value.isIntegralNumber(); case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); case "object" -> value.isObject(); default -> false; }; }
    private void validateHeader(String header) { if (!validHeader(header)) invalid("header is reserved or invalid"); }
    private boolean validHeader(String header) { String lower = header == null ? "" : header.toLowerCase(java.util.Locale.ROOT); return header != null && HEADER_TOKEN.matcher(header).matches() && !RESERVED_HEADERS.contains(lower) && !lower.startsWith("x-forwarded-"); }
    private static boolean validJsonPath(String path) { return path != null && path.matches("^\\$[A-Za-z0-9._~:/{}-]*$"); }
    private static boolean validIdentifier(String value) { return value != null && value.length() <= 128 && IDENTIFIER.matcher(value).matches(); }
    private String fingerprint(String resourceId, String connectionId, ApiResourceCommand command) { ObjectNode payload = mapper.createObjectNode(); payload.put("resourceId", resourceId); payload.put("connectionId", connectionId); payload.set("command", mapper.valueToTree(command)); try { return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(canonicalize(payload).toString().getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw failure(ApiResourceAuthoringException.Code.VALIDATION, "unable to fingerprint resource content"); } }
    private JsonNode canonicalize(JsonNode value) { if (value.isObject()) { ObjectNode result = mapper.createObjectNode(); List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo); for (String key : keys) result.set(key, canonicalize(value.get(key))); return result; } if (value.isArray()) { ArrayNode result = mapper.createArrayNode(); value.forEach(item -> result.add(canonicalize(item))); return result; } return value; }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 0xff)); return out.toString(); }
    private static void requireIdentifier(String value, String name) { if (!validIdentifier(value)) invalid(name + " is invalid"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void invalid(String message) { throw failure(ApiResourceAuthoringException.Code.VALIDATION, message); }
    private static ApiResourceAuthoringException failure(ApiResourceAuthoringException.Code code, String message) { return new ApiResourceAuthoringException(code, message); }
}
