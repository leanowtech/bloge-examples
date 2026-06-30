package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Projects a practical OpenAPI 3 operation subset into a visual resource design contract draft.
 */
@Service
public class OpenApiResourceDesignContractImporter {

    private static final ObjectMapper OPENAPI_TEXT_MAPPER = new YAMLMapper();
    private static final TypeReference<Map<String, Object>> OPENAPI_MAP = new TypeReference<>() {
    };

    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace"
    );
    private static final Set<String> SUPPORTED_STRING_FORMATS = Set.of(
            "date", "date-time", "duration", "email", "uri", "uuid"
    );
    private static final List<String> SCHEMA_MAP_KEYWORDS = List.of(
            "properties", "patternProperties", "dependentSchemas", "$defs"
    );
    private static final List<String> SCHEMA_OBJECT_KEYWORDS = List.of(
            "items", "contains", "propertyNames", "not", "if", "then", "else"
    );
    private static final List<String> SCHEMA_ARRAY_KEYWORDS = List.of(
            "allOf", "oneOf", "anyOf", "prefixItems"
    );
    private static final String FALLBACK_SERVER_URL = "https://api.example.com";

    /**
     * Projects one OpenAPI operation into a contract draft without storing it.
     *
     * @param request import request
     * @return generated draft and import diagnostics
     */
    public OpenApiResourceDesignContractImportResult project(OpenApiResourceDesignContractImportRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (request == null) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.requestMissing",
                    "OpenAPI import request is required.",
                    "/"));
            return result(null, diagnostics);
        }
        if (blank(request.resourceId())) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.resourceId.required",
                    "OpenAPI import request must declare a resourceId.",
                    "/resourceId"));
        }
        Map<String, Object> openApi = openApiDocument(request, diagnostics);
        if (openApi.isEmpty() && !hasErrors(diagnostics)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.documentMissing",
                    "OpenAPI document is required as openApi or openApiText.",
                    blank(request.openApiText()) ? "/openApi" : "/openApiText"));
        }
        if (hasErrors(diagnostics)) {
            return result(null, diagnostics);
        }

        Optional<SelectedOperation> selected = selectOperation(request, openApi, diagnostics);
        if (selected.isEmpty()) {
            return result(null, diagnostics);
        }

        SelectedOperation operation = selected.get();
        Map<String, Object> requestSchema = requestSchema(openApi, operation, diagnostics);
        Optional<Map<String, Object>> responseSchema = responseSchema(openApi, operation, diagnostics);
        if (responseSchema.isEmpty()) {
            return result(null, diagnostics);
        }

        ResourceDesignContract contract = new ResourceDesignContract(
                "contract:" + request.resourceId(),
                request.resourceId(),
                displayName(operation),
                description(operation),
                tags(operation),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", requestSchema),
                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", responseSchema.get()),
                examples(operation),
                request.status()
        );
        ResourceDescriptor descriptorSuggestion = descriptorSuggestion(openApi, operation,
                request.resourceId(), diagnostics);
        return result(contract, descriptorSuggestion, diagnostics);
    }

    private static Map<String, Object> openApiDocument(OpenApiResourceDesignContractImportRequest request,
                                                       List<VisualDiagnostic> diagnostics) {
        if (request.openApi() != null && !request.openApi().isEmpty()) {
            return request.openApi();
        }
        if (blank(request.openApiText())) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OPENAPI_TEXT_MAPPER.readValue(request.openApiText(), OPENAPI_MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.documentMalformed",
                    "OpenAPI document must be valid JSON or YAML: " + e.getOriginalMessage(),
                    "/openApiText"));
            return Map.of();
        }
    }

    private Optional<SelectedOperation> selectOperation(OpenApiResourceDesignContractImportRequest request,
                                                        Map<String, Object> openApi,
                                                        List<VisualDiagnostic> diagnostics) {
        Object rawPaths = openApi.get("paths");
        if (!(rawPaths instanceof Map<?, ?> paths)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.pathsMissing",
                    "OpenAPI document must declare a paths object.",
                    "/openApi/paths"));
            return Optional.empty();
        }

        if (!blank(request.path()) || !blank(request.method())) {
            return selectByPathAndMethod(request, objectMap(paths), diagnostics);
        }
        if (!blank(request.operationId())) {
            return selectByOperationId(request.operationId(), objectMap(paths), diagnostics);
        }

        diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.selectorMissing",
                "OpenAPI import requires either operationId or path plus method.",
                "/operationId"));
        return Optional.empty();
    }

    private Optional<SelectedOperation> selectByPathAndMethod(OpenApiResourceDesignContractImportRequest request,
                                                              Map<String, Object> paths,
                                                              List<VisualDiagnostic> diagnostics) {
        if (blank(request.path()) || blank(request.method())) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.selectorIncomplete",
                    "OpenAPI path and method selectors must be provided together.",
                    "/path"));
            return Optional.empty();
        }
        Object rawPathItem = paths.get(request.path());
        if (!(rawPathItem instanceof Map<?, ?> pathItem)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.operationMissing",
                    "OpenAPI path '%s' was not found.".formatted(request.path()),
                    "/openApi/paths/" + pointerSegment(request.path())));
            return Optional.empty();
        }
        String method = request.method().trim().toLowerCase(Locale.ROOT);
        Object rawOperation = pathItem.get(method);
        if (!(rawOperation instanceof Map<?, ?> operation)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.operationMissing",
                    "OpenAPI operation '%s %s' was not found.".formatted(method.toUpperCase(Locale.ROOT),
                            request.path()),
                    "/openApi/paths/" + pointerSegment(request.path()) + "/" + method));
            return Optional.empty();
        }
        Map<String, Object> operationMap = objectMap(operation);
        if (!blank(request.operationId())) {
            String operationId = string(operationMap.get("operationId"));
            if (!operationId.isBlank() && !request.operationId().equals(operationId)) {
                diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.operationIdMismatch",
                        "Selected operationId '%s' does not match requested operationId '%s'."
                                .formatted(operationId, request.operationId()),
                        "/operationId"));
                return Optional.empty();
            }
        }
        return Optional.of(new SelectedOperation(request.path(), method, objectMap(pathItem), operationMap));
    }

    private Optional<SelectedOperation> selectByOperationId(String operationId,
                                                            Map<String, Object> paths,
                                                            List<VisualDiagnostic> diagnostics) {
        List<SelectedOperation> matches = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            if (!(pathEntry.getValue() instanceof Map<?, ?> pathItem)) {
                continue;
            }
            Map<String, Object> pathItemMap = objectMap(pathItem);
            for (Map.Entry<String, Object> operationEntry : pathItemMap.entrySet()) {
                String method = operationEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method) || !(operationEntry.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                Map<String, Object> operationMap = objectMap(operation);
                if (operationId.equals(string(operationMap.get("operationId")))) {
                    matches.add(new SelectedOperation(pathEntry.getKey(), method, pathItemMap, operationMap));
                }
            }
        }
        if (matches.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.operationMissing",
                    "OpenAPI operationId '%s' was not found.".formatted(operationId),
                    "/operationId"));
            return Optional.empty();
        }
        if (matches.size() > 1) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.operationAmbiguous",
                    "OpenAPI operationId '%s' matched %d operations.".formatted(operationId, matches.size()),
                    "/operationId"));
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private Map<String, Object> requestSchema(Map<String, Object> openApi,
                                              SelectedOperation selected,
                                              List<VisualDiagnostic> diagnostics) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        addParameters(openApi, selected.pathItem().get("parameters"), properties, required,
                diagnostics, "/openApi/paths/" + pointerSegment(selected.path()) + "/parameters");
        addParameters(openApi, selected.operation().get("parameters"), properties, required,
                diagnostics, "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                        + "/parameters");
        addRequestBody(openApi, selected, properties, required, diagnostics);
        return objectSchema(properties, required);
    }

    private void addParameters(Map<String, Object> openApi,
                               Object rawParameters,
                               Map<String, Object> properties,
                               List<String> required,
                               List<VisualDiagnostic> diagnostics,
                               String target) {
        if (rawParameters == null) {
            return;
        }
        if (!(rawParameters instanceof List<?> parameters)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.parametersInvalid",
                    "OpenAPI parameters must be an array.",
                    target));
            return;
        }
        for (int i = 0; i < parameters.size(); i++) {
            String parameterTarget = target + "/" + i;
            Optional<Map<String, Object>> parameter = dereferenceObject(openApi, parameters.get(i),
                    diagnostics, parameterTarget, "parameter");
            if (parameter.isEmpty()) {
                continue;
            }
            String name = string(parameter.get().get("name"));
            String location = string(parameter.get().get("in"));
            if (name.isBlank() || location.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.parameterInvalid",
                        "OpenAPI parameter must declare non-blank name and in fields.",
                        parameterTarget));
                continue;
            }
            if (properties.containsKey(name)) {
                diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.parameterDuplicate",
                        "OpenAPI parameters flatten to duplicate request field '%s'.".formatted(name),
                        parameterTarget + "/name"));
                continue;
            }
            Object rawSchema = parameter.get().get("schema");
            Map<String, Object> schema = rawSchema instanceof Map<?, ?> schemaMap
                    ? visualSchema(openApi, objectMap(schemaMap), diagnostics, parameterTarget + "/schema")
                    : Map.of("type", "string");
            properties.put(name, schema);
            if (Boolean.TRUE.equals(parameter.get().get("required")) || "path".equals(location)) {
                required.add(name);
            }
        }
    }

    private void addRequestBody(Map<String, Object> openApi,
                                SelectedOperation selected,
                                Map<String, Object> properties,
                                List<String> required,
                                List<VisualDiagnostic> diagnostics) {
        Object rawBody = selected.operation().get("requestBody");
        if (rawBody == null) {
            return;
        }
        String bodyTarget = "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                + "/requestBody";
        Optional<Map<String, Object>> requestBody = dereferenceObject(openApi, rawBody, diagnostics,
                bodyTarget, "requestBody");
        if (requestBody.isEmpty()) {
            return;
        }
        Optional<Map<String, Object>> jsonContent = jsonContent(requestBody.get(), bodyTarget, diagnostics, false);
        if (jsonContent.isEmpty()) {
            return;
        }
        Object rawSchema = jsonContent.get().get("schema");
        if (!(rawSchema instanceof Map<?, ?> schemaMap)) {
            diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.openapi.requestBodySchemaMissing",
                    "OpenAPI JSON requestBody has no schema; body input will be treated as opaque.",
                    bodyTarget + "/content/schema"));
            properties.put("body", SchemaEnvelope.opaque().schema());
        } else {
            properties.put("body", visualSchema(openApi, objectMap(schemaMap), diagnostics,
                    bodyTarget + "/content/schema"));
        }
        if (Boolean.TRUE.equals(requestBody.get().get("required"))) {
            required.add("body");
        }
    }

    private Optional<Map<String, Object>> responseSchema(Map<String, Object> openApi,
                                                         SelectedOperation selected,
                                                         List<VisualDiagnostic> diagnostics) {
        Object rawResponses = selected.operation().get("responses");
        String responsesTarget = "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                + "/responses";
        if (!(rawResponses instanceof Map<?, ?> responses)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.responsesMissing",
                    "OpenAPI operation must declare responses.",
                    responsesTarget));
            return Optional.empty();
        }
        Optional<String> status = successStatus(objectMap(responses));
        if (status.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.responseMissing",
                    "OpenAPI operation must declare at least one 2xx response.",
                    responsesTarget));
            return Optional.empty();
        }
        String responseTarget = responsesTarget + "/" + status.get();
        Optional<Map<String, Object>> response = dereferenceObject(openApi, objectMap(responses).get(status.get()),
                diagnostics, responseTarget, "response");
        if (response.isEmpty()) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> jsonContent = jsonContent(response.get(), responseTarget, diagnostics, true);
        if (jsonContent.isEmpty()) {
            return Optional.empty();
        }
        Object rawSchema = jsonContent.get().get("schema");
        if (!(rawSchema instanceof Map<?, ?> schemaMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.responseSchemaMissing",
                    "OpenAPI 2xx JSON response must declare a schema.",
                    responseTarget + "/content/schema"));
            return Optional.empty();
        }
        return Optional.of(visualSchema(openApi, objectMap(schemaMap), diagnostics,
                responseTarget + "/content/schema"));
    }

    private Optional<String> successStatus(Map<String, Object> responses) {
        return responses.keySet().stream()
                .filter(this::successStatus)
                .sorted(Comparator.naturalOrder())
                .findFirst();
    }

    private boolean successStatus(String status) {
        if (status == null || status.length() != 3) {
            return false;
        }
        try {
            int value = Integer.parseInt(status);
            return value >= 200 && value < 300;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private Optional<Map<String, Object>> jsonContent(Map<String, Object> holder,
                                                      String target,
                                                      List<VisualDiagnostic> diagnostics,
                                                      boolean required) {
        Object rawContent = holder.get("content");
        if (!(rawContent instanceof Map<?, ?> content)) {
            if (required) {
                diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.jsonContentMissing",
                        "OpenAPI response must declare JSON content.",
                        target + "/content"));
            }
            return Optional.empty();
        }
        Map<String, Object> contentMap = objectMap(content);
        Object exact = contentMap.get("application/json");
        if (exact instanceof Map<?, ?> media) {
            return Optional.of(objectMap(media));
        }
        return contentMap.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("+json") || entry.getKey().contains("/json"))
                .map(Map.Entry::getValue)
                .filter(Map.class::isInstance)
                .map(value -> objectMap((Map<?, ?>) value))
                .findFirst()
                .or(() -> {
                    if (required) {
                        diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.jsonContentMissing",
                                "OpenAPI response must declare application/json content.",
                                target + "/content"));
                    }
                    return Optional.empty();
                });
    }

    private Map<String, Object> visualSchema(Map<String, Object> openApi,
                                             Map<String, Object> schema,
                                             List<VisualDiagnostic> diagnostics,
                                             String target) {
        Set<String> referencedDefinitions = new LinkedHashSet<>();
        Map<String, Object> converted = convertSchema(openApi, schema, referencedDefinitions, diagnostics, target);
        Map<String, Object> definitions = collectDefinitions(openApi, referencedDefinitions, diagnostics, target);
        converted = expandTopLevelReference(converted, definitions);
        if (!definitions.isEmpty()) {
            converted = new LinkedHashMap<>(converted);
            converted.put("$defs", definitions);
        }
        return converted;
    }

    private Map<String, Object> collectDefinitions(Map<String, Object> openApi,
                                                   Set<String> referencedDefinitions,
                                                   List<VisualDiagnostic> diagnostics,
                                                   String target) {
        Map<String, Object> definitions = new LinkedHashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(referencedDefinitions);
        Set<String> processed = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (!processed.add(name)) {
                continue;
            }
            Optional<Map<String, Object>> component = componentSchema(openApi, name);
            if (component.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.refUnresolved",
                        "OpenAPI component schema '%s' could not be resolved.".formatted(name),
                        target + "/$defs/" + pointerSegment(name)));
                continue;
            }
            Set<String> nestedReferences = new LinkedHashSet<>();
            Map<String, Object> definition = convertSchema(openApi, component.get(), nestedReferences,
                    diagnostics, target + "/$defs/" + pointerSegment(name));
            definitions.put(name, definition);
            for (String nested : nestedReferences) {
                if (!processed.contains(nested)) {
                    queue.addLast(nested);
                }
            }
        }
        return definitions;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertSchema(Map<String, Object> openApi,
                                              Map<String, Object> schema,
                                              Set<String> referencedDefinitions,
                                              List<VisualDiagnostic> diagnostics,
                                              String target) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if ("nullable".equals(entry.getKey())) {
                continue;
            }
            if ("$ref".equals(entry.getKey())) {
                String ref = string(entry.getValue());
                Optional<String> componentName = componentSchemaRef(ref);
                if (componentName.isPresent()) {
                    referencedDefinitions.add(componentName.get());
                    converted.put("$ref", "#/$defs/" + componentName.get());
                } else {
                    diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.refUnsupported",
                            "OpenAPI schema reference '%s' is not a supported local components/schemas reference."
                                    .formatted(ref),
                            target + "/$ref"));
                    converted.put("type", "any");
                }
                continue;
            }
            if (SCHEMA_MAP_KEYWORDS.contains(entry.getKey()) && entry.getValue() instanceof Map<?, ?> rawMap) {
                Map<String, Object> nestedMap = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> {
                    if (value instanceof Map<?, ?> nested) {
                        nestedMap.put(String.valueOf(key), convertSchema(openApi, (Map<String, Object>) nested,
                                referencedDefinitions, diagnostics,
                                target + "/" + entry.getKey() + "/" + pointerSegment(String.valueOf(key))));
                    } else {
                        nestedMap.put(String.valueOf(key), value);
                    }
                });
                converted.put(entry.getKey(), nestedMap);
                continue;
            }
            if (SCHEMA_OBJECT_KEYWORDS.contains(entry.getKey()) && entry.getValue() instanceof Map<?, ?> nested) {
                converted.put(entry.getKey(), convertSchema(openApi, (Map<String, Object>) nested,
                        referencedDefinitions, diagnostics, target + "/" + entry.getKey()));
                continue;
            }
            if ("additionalProperties".equals(entry.getKey())) {
                if (entry.getValue() instanceof Map<?, ?> nested) {
                    converted.put(entry.getKey(), convertSchema(openApi, (Map<String, Object>) nested,
                            referencedDefinitions, diagnostics, target + "/additionalProperties"));
                } else {
                    converted.put(entry.getKey(), entry.getValue());
                }
                continue;
            }
            if (SCHEMA_ARRAY_KEYWORDS.contains(entry.getKey()) && entry.getValue() instanceof List<?> values) {
                List<Object> nestedSchemas = new ArrayList<>();
                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);
                    if (value instanceof Map<?, ?> nested) {
                        nestedSchemas.add(convertSchema(openApi, (Map<String, Object>) nested,
                                referencedDefinitions, diagnostics, target + "/" + entry.getKey() + "/" + i));
                    } else {
                        nestedSchemas.add(value);
                    }
                }
                converted.put(entry.getKey(), nestedSchemas);
                continue;
            }
            if ("format".equals(entry.getKey()) && dropUnsupportedFormat(schema, entry.getValue())) {
                diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.openapi.formatDropped",
                        "OpenAPI schema format '%s' is not enforced by visual schemas and was dropped."
                                .formatted(entry.getValue()),
                        target + "/format"));
                continue;
            }
            converted.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        normalizeNullable(schema, converted);
        return converted;
    }

    private boolean dropUnsupportedFormat(Map<String, Object> schema, Object rawFormat) {
        if (!(rawFormat instanceof String format)) {
            return false;
        }
        Object type = schema.get("type");
        return !("string".equals(type) && SUPPORTED_STRING_FORMATS.contains(format));
    }

    private void normalizeNullable(Map<String, Object> source, Map<String, Object> converted) {
        if (!Boolean.TRUE.equals(source.get("nullable"))) {
            return;
        }
        Object type = converted.get("type");
        if (type instanceof String value && !"null".equals(value)) {
            converted.put("type", List.of(value, "null"));
        }
    }

    private Map<String, Object> expandTopLevelReference(Map<String, Object> schema,
                                                        Map<String, Object> definitions) {
        Object rawRef = schema.get("$ref");
        if (!(rawRef instanceof String ref) || !ref.startsWith("#/$defs/")) {
            return schema;
        }
        for (String key : schema.keySet()) {
            if (!"$ref".equals(key) && !"$comment".equals(key) && !"title".equals(key)
                    && !"description".equals(key) && !"examples".equals(key)
                    && !"deprecated".equals(key) && !"readOnly".equals(key) && !"writeOnly".equals(key)) {
                return schema;
            }
        }
        String name = ref.substring("#/$defs/".length());
        Object definition = definitions.get(name);
        if (!(definition instanceof Map<?, ?> definitionMap)) {
            return schema;
        }
        Map<String, Object> expanded = objectMap(definitionMap);
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!"$ref".equals(entry.getKey())) {
                expanded.put(entry.getKey(), deepCopyValue(entry.getValue()));
            }
        }
        return expanded;
    }

    private Optional<Map<String, Object>> componentSchema(Map<String, Object> openApi, String name) {
        Object rawComponents = openApi.get("components");
        if (!(rawComponents instanceof Map<?, ?> components)) {
            return Optional.empty();
        }
        Object rawSchemas = components.get("schemas");
        if (!(rawSchemas instanceof Map<?, ?> schemas)) {
            return Optional.empty();
        }
        Object schema = schemas.get(name);
        if (!(schema instanceof Map<?, ?> schemaMap)) {
            return Optional.empty();
        }
        return Optional.of(objectMap(schemaMap));
    }

    private Optional<String> componentSchemaRef(String ref) {
        String prefix = "#/components/schemas/";
        if (ref == null || !ref.startsWith(prefix) || ref.length() == prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(decodeJsonPointerToken(ref.substring(prefix.length())));
    }

    private Optional<Map<String, Object>> dereferenceObject(Map<String, Object> openApi,
                                                           Object value,
                                                           List<VisualDiagnostic> diagnostics,
                                                           String target,
                                                           String kind) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.objectInvalid",
                    "OpenAPI %s must be an object.".formatted(kind),
                    target));
            return Optional.empty();
        }
        Map<String, Object> map = objectMap(rawMap);
        Object rawRef = map.get("$ref");
        if (!(rawRef instanceof String ref)) {
            return Optional.of(map);
        }
        if (!ref.startsWith("#/")) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.refUnsupported",
                    "OpenAPI %s reference '%s' must be a local JSON pointer.".formatted(kind, ref),
                    target + "/$ref"));
            return Optional.empty();
        }
        Object resolved = resolveJsonPointer(openApi, ref);
        if (!(resolved instanceof Map<?, ?> resolvedMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.resourceContract.openapi.refUnresolved",
                    "OpenAPI %s reference '%s' could not be resolved.".formatted(kind, ref),
                    target + "/$ref"));
            return Optional.empty();
        }
        return Optional.of(objectMap(resolvedMap));
    }

    private Object resolveJsonPointer(Map<String, Object> root, String ref) {
        Object current = root;
        String[] tokens = ref.substring(2).split("/");
        for (String token : tokens) {
            String key = decodeJsonPointerToken(token);
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof List<?> list) {
                Integer index = listIndex(key);
                if (index == null || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Integer listIndex(String value) {
        try {
            int index = Integer.parseInt(value);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private String displayName(SelectedOperation selected) {
        String summary = string(selected.operation().get("summary"));
        if (!summary.isBlank()) {
            return summary;
        }
        String operationId = string(selected.operation().get("operationId"));
        if (!operationId.isBlank()) {
            return operationId;
        }
        return selected.method().toUpperCase(Locale.ROOT) + " " + selected.path();
    }

    private String description(SelectedOperation selected) {
        String description = string(selected.operation().get("description"));
        if (!description.isBlank()) {
            return description;
        }
        return string(selected.operation().get("summary"));
    }

    private List<String> tags(SelectedOperation selected) {
        Object rawTags = selected.operation().get("tags");
        if (!(rawTags instanceof List<?> values)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Object value : values) {
            String tag = string(value);
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private Map<String, Object> examples(SelectedOperation selected) {
        Map<String, Object> examples = new LinkedHashMap<>();
        Object rawExamples = selected.operation().get("x-bloge-examples");
        if (rawExamples instanceof Map<?, ?> map) {
            map.forEach((key, value) -> examples.put(String.valueOf(key), deepCopyValue(value)));
        }
        return examples;
    }

    private ResourceDescriptor descriptorSuggestion(Map<String, Object> openApi,
                                                    SelectedOperation selected,
                                                    String resourceId,
                                                    List<VisualDiagnostic> diagnostics) {
        Map<String, String> pathExpressions = new LinkedHashMap<>();
        Map<String, String> queryExpressions = new LinkedHashMap<>();
        for (OpenApiParameter parameter : descriptorParameters(openApi, selected)) {
            if ("path".equals(parameter.location())) {
                putParameterExpression(pathExpressions, parameter, diagnostics);
            } else if ("query".equals(parameter.location())) {
                putParameterExpression(queryExpressions, parameter, diagnostics);
            } else if ("header".equals(parameter.location()) || "cookie".equals(parameter.location())) {
                diagnostics.add(VisualDiagnostic.warning(
                        "visual.resourceContract.openapi.descriptorParameterLocationUnsupported",
                        "OpenAPI %s parameter '%s' is present in the contract schema but cannot be mapped by ResourceDescriptor; review descriptorSuggestion before saving."
                                .formatted(parameter.location(), parameter.name()),
                        parameter.target()));
            }
        }

        boolean hasBody = hasJsonRequestBody(openApi, selected);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (hasBody) {
            headers.put("Content-Type", "application/json");
        }

        return new ResourceDescriptor(
                resourceId,
                joinUrl(serverUrl(openApi, selected, diagnostics), selected.path()),
                selected.method(),
                headers,
                null,
                Duration.ofSeconds(30),
                new ParameterMapping(pathExpressions, queryExpressions, hasBody ? "ctx.params.body" : null),
                new ResponseProtocol.HttpStatus(),
                null
        );
    }

    private void putParameterExpression(Map<String, String> expressions,
                                        OpenApiParameter parameter,
                                        List<VisualDiagnostic> diagnostics) {
        if (!expressionFieldName(parameter.name())) {
            diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.openapi.descriptorParameterUnsupported",
                    "OpenAPI parameter '%s' is present in the contract schema but cannot be mapped by ResourceDescriptor because it is not a BLOGE field identifier; review descriptorSuggestion before saving."
                            .formatted(parameter.name()),
                    parameter.target()));
            return;
        }
        expressions.put(parameter.name(), "ctx.params." + parameter.name());
    }

    private List<OpenApiParameter> descriptorParameters(Map<String, Object> openApi,
                                                        SelectedOperation selected) {
        Map<String, OpenApiParameter> parameters = new LinkedHashMap<>();
        addDescriptorParameters(openApi, selected.pathItem().get("parameters"), parameters,
                "/openApi/paths/" + pointerSegment(selected.path()) + "/parameters");
        addDescriptorParameters(openApi, selected.operation().get("parameters"), parameters,
                "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                        + "/parameters");
        return List.copyOf(parameters.values());
    }

    private void addDescriptorParameters(Map<String, Object> openApi,
                                         Object rawParameters,
                                         Map<String, OpenApiParameter> parameters,
                                         String target) {
        if (!(rawParameters instanceof List<?> values)) {
            return;
        }
        for (int i = 0; i < values.size(); i++) {
            String parameterTarget = target + "/" + i;
            Optional<Map<String, Object>> parameter = dereferenceObject(openApi, values.get(i),
                    new ArrayList<>(), parameterTarget, "parameter");
            if (parameter.isEmpty()) {
                continue;
            }
            String name = string(parameter.get().get("name"));
            String location = string(parameter.get().get("in")).toLowerCase(Locale.ROOT);
            if (name.isBlank() || location.isBlank()) {
                continue;
            }
            parameters.put(location + "\n" + name, new OpenApiParameter(name, location, parameterTarget));
        }
    }

    private boolean hasJsonRequestBody(Map<String, Object> openApi, SelectedOperation selected) {
        Object rawBody = selected.operation().get("requestBody");
        if (rawBody == null) {
            return false;
        }
        String bodyTarget = "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                + "/requestBody";
        Optional<Map<String, Object>> requestBody = dereferenceObject(openApi, rawBody, new ArrayList<>(),
                bodyTarget, "requestBody");
        return requestBody.flatMap(body -> jsonContent(body, bodyTarget, new ArrayList<>(), false)).isPresent();
    }

    private String serverUrl(Map<String, Object> openApi,
                             SelectedOperation selected,
                             List<VisualDiagnostic> diagnostics) {
        String operationTarget = "/openApi/paths/" + pointerSegment(selected.path()) + "/" + selected.method()
                + "/servers";
        Optional<String> operationServer = serverUrl(selected.operation().get("servers"), operationTarget,
                diagnostics);
        if (operationServer.isPresent()) {
            return operationServer.get();
        }
        Optional<String> pathServer = serverUrl(selected.pathItem().get("servers"),
                "/openApi/paths/" + pointerSegment(selected.path()) + "/servers", diagnostics);
        if (pathServer.isPresent()) {
            return pathServer.get();
        }
        Optional<String> rootServer = serverUrl(openApi.get("servers"), "/openApi/servers", diagnostics);
        if (rootServer.isPresent()) {
            return rootServer.get();
        }
        diagnostics.add(VisualDiagnostic.warning("visual.resourceContract.openapi.serverMissing",
                "OpenAPI document has no servers entry; descriptorSuggestion uses https://api.example.com as a review placeholder.",
                "/openApi/servers"));
        return FALLBACK_SERVER_URL;
    }

    private Optional<String> serverUrl(Object rawServers,
                                       String target,
                                       List<VisualDiagnostic> diagnostics) {
        if (!(rawServers instanceof List<?> servers)) {
            return Optional.empty();
        }
        for (int i = 0; i < servers.size(); i++) {
            if (!(servers.get(i) instanceof Map<?, ?> rawServer)) {
                continue;
            }
            Map<String, Object> server = objectMap(rawServer);
            String url = string(server.get("url"));
            if (!url.isBlank()) {
                return Optional.of(resolveServerVariables(url, server, target + "/" + i, diagnostics));
            }
        }
        return Optional.empty();
    }

    private String resolveServerVariables(String url,
                                          Map<String, Object> server,
                                          String target,
                                          List<VisualDiagnostic> diagnostics) {
        Object rawVariables = server.get("variables");
        if (!(rawVariables instanceof Map<?, ?> variables)) {
            return url;
        }
        String resolved = url;
        for (Map.Entry<String, Object> entry : objectMap(variables).entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> rawVariable)) {
                continue;
            }
            String defaultValue = string(rawVariable.get("default"));
            if (defaultValue.isBlank()) {
                diagnostics.add(VisualDiagnostic.warning(
                        "visual.resourceContract.openapi.serverVariableDefaultMissing",
                        "OpenAPI server variable '%s' has no default; descriptorSuggestion keeps the URL placeholder."
                                .formatted(entry.getKey()),
                        target + "/variables/" + pointerSegment(entry.getKey())));
                continue;
            }
            resolved = resolved.replace("{" + entry.getKey() + "}", defaultValue);
        }
        return resolved;
    }

    private static String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String suffix = path == null ? "" : path.trim();
        if (base.endsWith("/") && suffix.startsWith("/")) {
            return base.substring(0, base.length() - 1) + suffix;
        }
        if (!base.endsWith("/") && !suffix.startsWith("/")) {
            return base + "/" + suffix;
        }
        return base + suffix;
    }

    private OpenApiResourceDesignContractImportResult result(ResourceDesignContract contract,
                                                            List<VisualDiagnostic> diagnostics) {
        return result(contract, null, diagnostics);
    }

    private OpenApiResourceDesignContractImportResult result(ResourceDesignContract contract,
                                                            ResourceDescriptor descriptorSuggestion,
                                                            List<VisualDiagnostic> diagnostics) {
        return new OpenApiResourceDesignContractImportResult(
                contract,
                new VisualValidationResult(false, diagnostics),
                descriptorSuggestion
        );
    }

    private static boolean hasErrors(List<VisualDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(VisualDiagnostic::error);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean expressionFieldName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> objectMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    private static String pointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String decodeJsonPointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private record SelectedOperation(
            String path,
            String method,
            Map<String, Object> pathItem,
            Map<String, Object> operation
    ) {
        private SelectedOperation {
            Objects.requireNonNull(path);
            Objects.requireNonNull(method);
            pathItem = pathItem == null ? Map.of() : Map.copyOf(pathItem);
            operation = operation == null ? Map.of() : Map.copyOf(operation);
        }
    }

    private record OpenApiParameter(
            String name,
            String location,
            String target
    ) {
    }
}
