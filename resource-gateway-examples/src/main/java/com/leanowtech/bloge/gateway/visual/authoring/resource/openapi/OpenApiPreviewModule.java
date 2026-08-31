package com.leanowtech.bloge.gateway.visual.authoring.resource.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiOperationDiscoveryResult;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiOperationSummary;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportRequest;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImportResult;
import com.leanowtech.bloge.gateway.visual.resource.OpenApiResourceDesignContractImporter;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Deep, side-effect-free module that turns an inline OpenAPI document into standard Resource commands.
 *
 * <p>The module deliberately narrows schemas to the flat contract accepted by the current Resource
 * authority. It reports that simplification per operation and rejects shapes whose runtime meaning
 * would change. It never stores a Connection, Resource, Fixture, or OpenAPI document.</p>
 */
public final class OpenApiPreviewModule {
    private static final int MAX_DOCUMENT_LENGTH = 10 * 1024 * 1024;
    private static final int MAX_OPERATION_IDS = 256;
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "string", "integer", "number", "boolean", "object");
    private static final List<Integer> HTTP_SUCCESS = IntStream.rangeClosed(200, 299).boxed().toList();

    private final OpenApiResourceDesignContractImporter importer;
    private final JsonSchemaSampleGenerator samples;
    private final ObjectMapper mapper;
    private final ApiResourceDecisions decisions;

    public OpenApiPreviewModule(OpenApiResourceDesignContractImporter importer,
                                JsonSchemaSampleGenerator samples,
                                ObjectMapper mapper,
                                ApiResourceDecisions decisions) {
        this.importer = Objects.requireNonNull(importer, "importer");
        this.samples = Objects.requireNonNull(samples, "samples");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    /** Projects the requested inline operations without persistence or network I/O. */
    public OpenApiPreview preview(OpenApiPreviewCommand command) {
        String document = validate(command);
        OpenApiResourceDesignContractImportRequest discoveryRequest = request(null, null, null, null, document);
        OpenApiOperationDiscoveryResult discovered = importer.discoverOperations(discoveryRequest);
        if (!discovered.validation().valid()) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        Set<String> requested = new HashSet<>(command.operationIds());
        List<OpenApiOperationSummary> selected = discovered.operations().stream()
                .filter(operation -> requested.isEmpty()
                        ? importable(operation)
                        : requested.contains(operation.operationId()))
                .toList();
        if (selected.isEmpty() || !requested.isEmpty()
                && selected.stream().map(OpenApiOperationSummary::operationId).collect(java.util.stream.Collectors.toSet())
                .size() != requested.size()) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        List<OpenApiPreview.Operation> operations = new ArrayList<>();
        for (OpenApiOperationSummary operation : selected) {
            operations.add(project(document, operation));
        }
        return new OpenApiPreview(OpenApiPreview.SCHEMA_VERSION, discoveryId(document), operations);
    }

    private String validate(OpenApiPreviewCommand command) {
        if (command == null || !OpenApiPreviewCommand.SCHEMA_VERSION.equals(command.schemaVersion())
                || command.source() == null || command.operationIds().size() > MAX_OPERATION_IDS
                || command.operationIds().stream().anyMatch(id -> id == null || !IDENTIFIER.matcher(id).matches())
                || new HashSet<>(command.operationIds()).size() != command.operationIds().size()) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        if (command.source() instanceof OpenApiPreviewCommand.Remote) {
            throw failure(OpenApiPreviewFailure.Code.CAPABILITY_UNAVAILABLE);
        }
        if (!(command.source() instanceof OpenApiPreviewCommand.Inline inline)
                || inline.documentText() == null || inline.documentText().isBlank()
                || inline.documentText().length() > MAX_DOCUMENT_LENGTH) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        return inline.documentText();
    }

    private OpenApiPreview.Operation project(String document, OpenApiOperationSummary operation) {
        if (!importable(operation)) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        OpenApiResourceDesignContractImportResult projected = importer.project(request(
                operation.operationId(), operation.operationId(), operation.method(), operation.path(), document));
        if (!projected.validation().valid() || projected.contract() == null
                || projected.descriptorSuggestion() == null) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        Projection projection = command(projected.contract(), projected.descriptorSuggestion(), operation);
        try {
            decisions.validateForAuthoring(projection.command());
        } catch (ApiResourceAuthoringException | IllegalArgumentException ex) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        List<OpenApiPreview.Diagnostic> diagnostics = new ArrayList<>();
        if ("WARNING".equals(operation.projectionLevel())) {
            diagnostics.add(new OpenApiPreview.Diagnostic("OPENAPI_PROJECTION_WARNING",
                    bounded(operation.projectionMessage())));
        }
        if (projection.simplified()) {
            diagnostics.add(new OpenApiPreview.Diagnostic("OPENAPI_SCHEMA_SIMPLIFIED",
                    "Schema constraints were narrowed to the standard flat Resource contract."));
        }
        return new OpenApiPreview.Operation(operation.operationId(), operation.method(), operation.path(),
                projection.command(), diagnostics);
    }

    private static boolean importable(OpenApiOperationSummary operation) {
        return operation != null
                && operation.operationId() != null
                && IDENTIFIER.matcher(operation.operationId()).matches()
                && SUPPORTED_METHODS.contains(operation.method())
                && !"BLOCKED".equals(operation.projectionLevel());
    }

    private Projection command(ResourceDesignContract contract, VisualResourceDescriptor descriptor,
                               OpenApiOperationSummary operation) {
        SimplifiedSchema input = simplify(contract.requestSchema());
        SimplifiedSchema output = simplify(contract.responseSchema());
        List<ApiResourceCommand.Binding> bindings = bindings(descriptor.parameterMapping(), input.envelope());
        ApiResourceCommand.Response response = response(descriptor);
        JsonNode inputExample = mapper.valueToTree(samples.generate(input.envelope()));
        JsonNode outputExample = mapper.valueToTree(samples.generate(output.envelope()));
        ApiResourceCommand command = new ApiResourceCommand(
                contract.displayName(), blankToNull(contract.description()),
                new ApiResourceCommand.Operation(operation.method(), operation.path(), bindings),
                new ApiResourceCommand.Contract(input.envelope(), output.envelope()), response,
                "GET".equals(operation.method())
                        ? ApiResourceCommand.Effect.readOnly()
                        : ApiResourceCommand.Effect.fixtureOnlyWrite(),
                List.of(new ApiResourceCommand.Example("openapi-example", inputExample, outputExample)));
        return new Projection(command, input.simplified() || output.simplified());
    }

    private SimplifiedSchema simplify(SchemaEnvelope source) {
        if (source == null || !(source.schema().get("properties") instanceof Map<?, ?> rawProperties)
                || !"object".equals(source.schema().get("type"))) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        boolean simplified = source.schema().keySet().stream()
                .anyMatch(key -> !Set.of("type", "properties", "required", "additionalProperties").contains(key));
        for (Map.Entry<?, ?> entry : rawProperties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!IDENTIFIER.matcher(name).matches() || !(entry.getValue() instanceof Map<?, ?> definition)) {
                throw failure(OpenApiPreviewFailure.Code.VALIDATION);
            }
            String type = schemaType(definition.get("type"));
            if (!SUPPORTED_TYPES.contains(type)) {
                throw failure(OpenApiPreviewFailure.Code.VALIDATION);
            }
            properties.put(name, Map.of("type", type));
            simplified |= definition.size() != 1 || !definition.containsKey("type");
        }
        List<String> required = source.required().stream().filter(properties::containsKey).toList();
        return new SimplifiedSchema(SchemaEnvelope.object(properties, required), simplified);
    }

    private List<ApiResourceCommand.Binding> bindings(VisualResourceParameterMapping mapping,
                                                       SchemaEnvelope input) {
        if (!mapping.cookieExpressions().isEmpty()) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        List<ApiResourceCommand.Binding> bindings = new ArrayList<>();
        addBindings(bindings, mapping.pathExpressions(), "PATH", input);
        addBindings(bindings, mapping.queryExpressions(), "QUERY", input);
        addBindings(bindings, mapping.headerExpressions(), "HEADER", input);
        if (mapping.bodyExpression() != null && !mapping.bodyExpression().isBlank()) {
            String inputName = inputName(mapping.bodyExpression());
            requireInput(input, inputName);
            bindings.add(new ApiResourceCommand.Binding("$." + inputName,
                    new ApiResourceCommand.Location("BODY", "body")));
        }
        bindings.sort(Comparator.comparingInt((ApiResourceCommand.Binding binding) -> switch (binding.to().location()) {
            case "PATH" -> 0;
            case "QUERY" -> 1;
            case "HEADER" -> 2;
            default -> 3;
        }).thenComparing(binding -> binding.to().name()));
        return List.copyOf(bindings);
    }

    private void addBindings(List<ApiResourceCommand.Binding> target, Map<String, String> expressions,
                             String location, SchemaEnvelope input) {
        expressions.forEach((name, expression) -> {
            String inputName = inputName(expression);
            requireInput(input, inputName);
            target.add(new ApiResourceCommand.Binding("$." + inputName,
                    new ApiResourceCommand.Location(location, name)));
        });
    }

    private ApiResourceCommand.Response response(VisualResourceDescriptor descriptor) {
        ApiResourceCommand.Success success;
        VisualResourceResponseProtocol protocol = descriptor.responseProtocol();
        if (protocol instanceof VisualResourceResponseProtocol.HttpStatus) {
            success = new ApiResourceCommand.HttpStatus(HTTP_SUCCESS);
        } else if (protocol instanceof VisualResourceResponseProtocol.StatusCodes statusCodes) {
            success = new ApiResourceCommand.HttpStatus(statusCodes.successCodes().stream().sorted().toList());
        } else if (protocol instanceof VisualResourceResponseProtocol.BodyCode bodyCode) {
            success = new ApiResourceCommand.BodyMatch(jsonPath(bodyCode.codePath()),
                    bodyCode.successValues().stream()
                            .map(value -> (JsonNode) mapper.valueToTree(value)).toList());
        } else if (protocol instanceof VisualResourceResponseProtocol.BodyFlag bodyFlag) {
            success = new ApiResourceCommand.BodyMatch(jsonPath(bodyFlag.flagPath()),
                    List.of(mapper.valueToTree(true)));
        } else {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
        return new ApiResourceCommand.Response(success,
                descriptor.payloadPath() == null || descriptor.payloadPath().isBlank()
                        ? null : jsonPath(descriptor.payloadPath()));
    }

    private static OpenApiResourceDesignContractImportRequest request(String resourceId, String operationId,
                                                                       String method, String path,
                                                                       String document) {
        return new OpenApiResourceDesignContractImportRequest(resourceId, operationId, path, method,
                ResourceDesignContract.STATUS_ACTIVE, null, document);
    }

    private static String inputName(String expression) {
        if (expression != null && expression.matches("^ctx\\.params\\.[A-Za-z0-9._:-]+$")) {
            return expression.substring(expression.lastIndexOf('.') + 1);
        }
        if (expression != null && expression.matches("^ctx\\.params\\[\"[A-Za-z0-9._:-]+\"\\]$")) {
            return expression.substring(expression.indexOf('"') + 1, expression.lastIndexOf('"'));
        }
        throw failure(OpenApiPreviewFailure.Code.VALIDATION);
    }

    private static void requireInput(SchemaEnvelope input, String name) {
        if (!input.properties().containsKey(name)) {
            throw failure(OpenApiPreviewFailure.Code.VALIDATION);
        }
    }

    private static String schemaType(Object rawType) {
        if (rawType instanceof String type) return type;
        if (rawType instanceof List<?> types) {
            return types.stream().map(String::valueOf).filter(type -> !"null".equals(type)).findFirst().orElse("");
        }
        return "";
    }

    private static String jsonPath(String value) {
        String normalized = value == null ? "" : value.trim();
        if ("$".equals(normalized)) return "$";
        if (normalized.startsWith("$.")) return normalized;
        if (normalized.matches("^[A-Za-z0-9._~-]+$")) return "$." + normalized;
        throw failure(OpenApiPreviewFailure.Code.VALIDATION);
    }

    private static String discoveryId(String document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(document.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) hex.append(String.format(Locale.ROOT, "%02x", digest[index]));
            return "preview-" + hex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "OpenAPI projection requires review.";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static OpenApiPreviewFailure failure(OpenApiPreviewFailure.Code code) {
        return new OpenApiPreviewFailure(code);
    }

    private record SimplifiedSchema(SchemaEnvelope envelope, boolean simplified) {
    }

    private record Projection(ApiResourceCommand command, boolean simplified) {
    }
}
