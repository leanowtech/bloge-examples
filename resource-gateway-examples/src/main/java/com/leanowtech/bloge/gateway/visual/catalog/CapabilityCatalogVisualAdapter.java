package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects BLOGE framework capability catalogs into the generic visual operator-library contract.
 *
 * <p>The generic canvas does not depend on this source format. This adapter is only a migration
 * convenience for teams that already export {@code bloge.capabilityCatalog.v1} from existing BLOGE
 * business code; the output remains a normal {@code bloge.visualOperatorLibrary.v1} draft.</p>
 */
@Service
public class CapabilityCatalogVisualAdapter {

    public static final String SOURCE_SCHEMA_VERSION = "bloge.capabilityCatalog.v1";
    private static final String VISUAL_LIBRARY_SCHEMA_VERSION = "bloge.visualOperatorLibrary.v1";
    private static final String VISUAL_OPERATOR_SCHEMA_VERSION = "bloge.visualOperator.v1";
    private static final String DEFAULT_VERSION = "1.0.0";

    /**
     * Adapts a decoded framework capability catalog into a visual operator-library draft.
     *
     * @param catalog decoded JSON/YAML source catalog
     * @return visual library draft plus adapter diagnostics
     */
    public CapabilityCatalogVisualAdapterResult project(Map<String, Object> catalog) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (catalog == null || catalog.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.capabilityCatalog.missing",
                    "Capability catalog is required.",
                    "/"));
            return result(null, diagnostics, CapabilityCatalogProjectionReview.empty("", ""));
        }

        String sourceSchemaVersion = string(catalog.get("schemaVersion"));
        String catalogId = firstNonBlank(string(catalog.get("catalogId")), "business-capability-catalog");
        if (!SOURCE_SCHEMA_VERSION.equals(sourceSchemaVersion)) {
            diagnostics.add(VisualDiagnostic.error("visual.capabilityCatalog.schemaVersion.unsupported",
                    "Capability catalog schemaVersion '%s' is unsupported; expected %s."
                            .formatted(sourceSchemaVersion, SOURCE_SCHEMA_VERSION),
                    "/schemaVersion"));
            return result(null, diagnostics,
                    CapabilityCatalogProjectionReview.empty(sourceSchemaVersion, catalogId));
        }

        ProjectionStats stats = new ProjectionStats();
        List<Map<String, Object>> sourceOperators = listOfMaps(catalog.get("operators"));
        List<Map<String, Object>> sourceFunctions = listOfMaps(catalog.get("functions"));
        copyCatalogDiagnostics(catalog, "/diagnostics", diagnostics, stats);

        List<OperatorDefinition> operators = new ArrayList<>();
        Set<String> operatorRefs = new LinkedHashSet<>();
        for (int index = 0; index < sourceOperators.size(); index++) {
            operators.add(operatorFrom(catalogId, catalog, sourceOperators.get(index), index, operatorRefs,
                    diagnostics, stats));
        }

        List<OperatorLibrary.BuiltInFunction> functions = new ArrayList<>();
        Set<String> functionNames = new LinkedHashSet<>();
        for (int index = 0; index < sourceFunctions.size(); index++) {
            OperatorLibrary.BuiltInFunction function = functionFrom(sourceFunctions.get(index), index, diagnostics,
                    stats);
            if (function == null) {
                continue;
            }
            String functionKey = function.namespace().isBlank()
                    ? function.name()
                    : function.namespace() + "." + function.name();
            if (!functionNames.add(functionKey)) {
                diagnostics.add(VisualDiagnostic.warning("visual.capabilityCatalog.function.duplicate",
                        "Duplicate built-in function '%s' was skipped while adapting capability catalog."
                                .formatted(functionKey),
                        "/functions/%d/name".formatted(index)));
                continue;
            }
            functions.add(function);
        }

        OperatorLibrary library = new OperatorLibrary(
                VISUAL_LIBRARY_SCHEMA_VERSION,
                visualLibraryId(catalogId),
                firstNonBlank(string(catalog.get("displayName")), catalogId),
                visualLibraryVersion(string(catalog.get("blogeVersion"))),
                "",
                OperatorLibrary.STATUS_ACTIVE,
                functions,
                operators
        );
        CapabilityCatalogProjectionReview review = new CapabilityCatalogProjectionReview(
                CapabilityCatalogProjectionReview.SCHEMA_VERSION,
                catalogId,
                sourceSchemaVersion,
                sourceOperators.size(),
                sourceFunctions.size(),
                operators.size(),
                functions.size(),
                stats.opaqueSchemaCount,
                stats.sourceDiagnosticCount,
                coverageStatus(sourceOperators.size(), operators.size(), sourceFunctions.size(), functions.size(),
                        diagnostics),
                stats.sourceKinds.stream().toList()
        );
        return result(library, diagnostics, review);
    }

    private static OperatorDefinition operatorFrom(String catalogId,
                                                   Map<String, Object> catalog,
                                                   Map<String, Object> source,
                                                   int index,
                                                   Set<String> operatorRefs,
                                                   List<VisualDiagnostic> diagnostics,
                                                   ProjectionStats stats) {
        String sourcePath = "/operators/%d".formatted(index);
        String rawRef = string(source.get("operatorRef"));
        String operatorRef = firstNonBlank(rawRef, "catalog:%d".formatted(index + 1));
        if (!operatorRefs.add(operatorRef)) {
            diagnostics.add(VisualDiagnostic.warning("visual.capabilityCatalog.operator.duplicate",
                    "Duplicate operatorRef '%s' was made unique in the visual library draft."
                            .formatted(operatorRef),
                    sourcePath + "/operatorRef"));
            operatorRef = operatorRef + "-" + (index + 1);
            operatorRefs.add(operatorRef);
        }

        Map<String, Object> display = objectMap(source.get("display"));
        Map<String, Object> implementation = objectMap(source.get("implementation"));
        Map<String, Object> capabilities = objectMap(source.get("capabilities"));
        String implementationKind = firstNonBlank(string(implementation.get("kind")), "bloge-operator");
        stats.sourceKinds.add(implementationKind);

        copyCatalogDiagnostics(source, sourcePath + "/diagnostics", diagnostics, stats);
        return new OperatorDefinition(
                VISUAL_OPERATOR_SCHEMA_VERSION,
                operatorRef,
                firstNonBlank(string(source.get("operatorVersion")), DEFAULT_VERSION),
                "",
                new OperatorDefinition.Display(
                        firstNonBlank(string(display.get("name")), operatorRef),
                        string(display.get("description")),
                        stringList(display.get("tags"))
                ),
                new OperatorDefinition.Source("user-library", "", "", "", false, visualLibraryId(catalogId)),
                portsFrom(source, sourcePath, diagnostics, stats),
                configSchema(source.get("configSchema"), sourcePath + "/configSchema", diagnostics, stats),
                capabilitiesFrom(capabilities),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("design", "", loweringParameters(catalog, source,
                        implementationKind)),
                List.of()
        );
    }

    private static OperatorDefinition.Ports portsFrom(Map<String, Object> source,
                                                      String sourcePath,
                                                      List<VisualDiagnostic> diagnostics,
                                                      ProjectionStats stats) {
        Map<String, Object> ports = objectMap(source.get("ports"));
        return new OperatorDefinition.Ports(
                portList(ports.get("inputs"), sourcePath + "/ports/inputs", diagnostics, stats),
                portList(ports.get("outputs"), sourcePath + "/ports/outputs", diagnostics, stats)
        );
    }

    private static List<OperatorDefinition.Port> portList(Object raw,
                                                          String sourcePath,
                                                          List<VisualDiagnostic> diagnostics,
                                                          ProjectionStats stats) {
        List<Map<String, Object>> sourcePorts = listOfMaps(raw);
        List<OperatorDefinition.Port> ports = new ArrayList<>();
        for (int index = 0; index < sourcePorts.size(); index++) {
            Map<String, Object> port = sourcePorts.get(index);
            ports.add(new OperatorDefinition.Port(
                    firstNonBlank(string(port.get("name")), "port%d".formatted(index + 1)),
                    schemaEnvelope(firstNonNull(port.get("schema"), port.get("schemaDescriptor")),
                            sourcePath + "/%d/schema".formatted(index), diagnostics, stats, true),
                    bool(port.get("required")),
                    string(port.get("description"))
            ));
        }
        return ports;
    }

    private static OperatorDefinition.Capabilities capabilitiesFrom(Map<String, Object> raw) {
        boolean deterministic = bool(raw.get("deterministic"));
        return new OperatorDefinition.Capabilities(
                effectFrom(firstNonBlank(string(raw.get("sideEffectType")), string(raw.get("effect"))),
                        deterministic),
                idempotencyFrom(string(raw.get("idempotency")), deterministic),
                bool(raw.get("streaming")),
                bool(firstNonNull(raw.get("suspendable"), raw.get("durable"))),
                bool(raw.get("requiresSecrets"))
        );
    }

    private static OperatorLibrary.BuiltInFunction functionFrom(Map<String, Object> source,
                                                                int index,
                                                                List<VisualDiagnostic> diagnostics,
                                                                ProjectionStats stats) {
        String sourcePath = "/functions/%d".formatted(index);
        String name = string(source.get("name"));
        if (name.isBlank()) {
            diagnostics.add(VisualDiagnostic.warning("visual.capabilityCatalog.function.nameMissing",
                    "Built-in function without a name was skipped while adapting capability catalog.",
                    sourcePath + "/name"));
            return null;
        }
        copyCatalogDiagnostics(source, sourcePath + "/diagnostics", diagnostics, stats);
        List<OperatorLibrary.Signature> signatures = listOfMaps(source.get("signatures")).stream()
                .map(signature -> signatureFrom(signature, sourcePath, diagnostics))
                .toList();
        return new OperatorLibrary.BuiltInFunction(
                name,
                string(source.get("namespace")),
                firstNonBlank(string(source.get("displayName")), name),
                string(source.get("description")),
                string(source.get("category")),
                signatures,
                stringList(source.get("examples"))
        );
    }

    private static OperatorLibrary.Signature signatureFrom(Map<String, Object> source,
                                                           String functionPath,
                                                           List<VisualDiagnostic> diagnostics) {
        List<OperatorLibrary.Parameter> parameters = listOfMaps(source.get("parameters")).stream()
                .map(parameter -> parameterFrom(parameter, functionPath, diagnostics))
                .toList();
        return new OperatorLibrary.Signature(
                string(source.get("label")),
                string(source.get("description")),
                parameters,
                returnValueFrom(objectMap(source.get("returns")), functionPath, diagnostics)
        );
    }

    private static OperatorLibrary.Parameter parameterFrom(Map<String, Object> source,
                                                           String functionPath,
                                                           List<VisualDiagnostic> diagnostics) {
        String rawType = string(source.get("type"));
        return new OperatorLibrary.Parameter(
                string(source.get("name")),
                normalizedFunctionType(rawType, functionPath + "/signatures/parameters/type", diagnostics),
                null,
                bool(source.get("optional")),
                bool(source.get("variadic")),
                string(source.get("description"))
        );
    }

    private static OperatorLibrary.ReturnValue returnValueFrom(Map<String, Object> source,
                                                               String functionPath,
                                                               List<VisualDiagnostic> diagnostics) {
        if (source.isEmpty()) {
            return OperatorLibrary.ReturnValue.any();
        }
        String rawType = string(source.get("type"));
        return new OperatorLibrary.ReturnValue(
                normalizedFunctionType(rawType, functionPath + "/signatures/returns/type", diagnostics),
                null,
                string(source.get("description"))
        );
    }

    private static SchemaEnvelope schemaEnvelope(Object raw,
                                                 String target,
                                                 List<VisualDiagnostic> diagnostics,
                                                 ProjectionStats stats,
                                                 boolean warnWhenOpaque) {
        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = objectMap(rawMap);
            if (map.containsKey("schema")) {
                Map<String, Object> schema = objectMap(map.get("schema"));
                if (!schema.isEmpty()) {
                    return new SchemaEnvelope(
                            firstNonBlank(string(map.get("format")), SchemaEnvelope.JSON_SCHEMA),
                            firstNonBlank(string(map.get("version")), "2020-12"),
                            schema
                    );
                }
            }
            Map<String, Object> descriptorSchema = schemaFromDescriptor(map);
            if (!descriptorSchema.isEmpty()) {
                return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", descriptorSchema);
            }
            if (looksLikeJsonSchema(map)) {
                return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", map);
            }
        }
        stats.opaqueSchemaCount++;
        if (warnWhenOpaque) {
            diagnostics.add(VisualDiagnostic.warning("visual.capabilityCatalog.schema.opaque",
                    "Schema at %s was missing or not projectable; using an opaque object schema."
                            .formatted(target),
                    target));
        }
        return SchemaEnvelope.opaque();
    }

    private static SchemaEnvelope configSchema(Object raw,
                                               String target,
                                               List<VisualDiagnostic> diagnostics,
                                               ProjectionStats stats) {
        if (raw == null) {
            return SchemaEnvelope.object(Map.of(), List.of());
        }
        return schemaEnvelope(raw, target, diagnostics, stats, false);
    }

    private static Map<String, Object> schemaFromDescriptor(Map<String, Object> descriptor) {
        if (descriptor.isEmpty()) {
            return Map.of();
        }
        String type = firstNonBlank(string(descriptor.get("type")), string(descriptor.get("rawType")));
        if (type.isBlank()) {
            return Map.of();
        }
        String jsonType = jsonTypeFromJavaType(type);
        if (jsonType.isBlank()) {
            return Map.of("type", "object", "additionalProperties", true);
        }
        return Map.of("type", jsonType);
    }

    private static Map<String, Object> loweringParameters(Map<String, Object> catalog,
                                                          Map<String, Object> source,
                                                          String implementationKind) {
        Map<String, Object> implementation = objectMap(source.get("implementation"));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("catalogId", string(catalog.get("catalogId")));
        provenance.put("generatedAt", string(catalog.get("generatedAt")));
        provenance.put("blogeVersion", string(catalog.get("blogeVersion")));
        provenance.put("implementationKind", implementationKind);
        copyIfPresent(provenance, "className", implementation.get("className"));
        copyIfPresent(provenance, "inputType", implementation.get("inputType"));
        copyIfPresent(provenance, "outputType", implementation.get("outputType"));
        copyIfPresent(provenance, "source", implementation.get("source"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("bindingTarget", string(source.get("operatorRef")));
        parameters.put("capabilityCatalog", provenance);
        return parameters;
    }

    private static void copyCatalogDiagnostics(Map<String, Object> source,
                                               String targetPrefix,
                                               List<VisualDiagnostic> diagnostics,
                                               ProjectionStats stats) {
        List<Map<String, Object>> sourceDiagnostics = listOfMaps(source.get("diagnostics"));
        stats.sourceDiagnosticCount += sourceDiagnostics.size();
        for (int index = 0; index < sourceDiagnostics.size(); index++) {
            Map<String, Object> diagnostic = sourceDiagnostics.get(index);
            String level = firstNonBlank(string(diagnostic.get("level")), "WARNING").toUpperCase(Locale.ROOT);
            String code = firstNonBlank(string(diagnostic.get("code")),
                    "visual.capabilityCatalog.sourceDiagnostic");
            String message = firstNonBlank(string(diagnostic.get("message")),
                    "Capability catalog carried a source diagnostic.");
            String path = firstNonBlank(string(diagnostic.get("path")), targetPrefix + "/%d".formatted(index));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "bloge.capabilityCatalog.v1");
            metadata.put("sourcePath", path);
            diagnostics.add(new VisualDiagnostic(level, code, message, path, -1, -1, metadata));
        }
    }

    private static CapabilityCatalogVisualAdapterResult result(OperatorLibrary library,
                                                               List<VisualDiagnostic> diagnostics,
                                                               CapabilityCatalogProjectionReview review) {
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        OperatorLibraryValidationResult validation = new OperatorLibraryValidationResult(
                safeDiagnostics.stream().noneMatch(VisualDiagnostic::error),
                safeDiagnostics,
                OperatorLibraryImpactReview.empty(),
                library == null ? OperatorLibraryProfile.empty() : OperatorLibraryProfile.from(library,
                        safeDiagnostics),
                library
        );
        return new CapabilityCatalogVisualAdapterResult(
                CapabilityCatalogVisualAdapterResult.SCHEMA_VERSION,
                library,
                validation,
                review
        );
    }

    private static String coverageStatus(int sourceOperatorCount,
                                         int projectedOperatorCount,
                                         int sourceFunctionCount,
                                         int projectedFunctionCount,
                                         List<VisualDiagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return "BLOCKED";
        }
        if (sourceOperatorCount == 0 && sourceFunctionCount == 0) {
            return "NO_MATCH";
        }
        return sourceOperatorCount == projectedOperatorCount && sourceFunctionCount == projectedFunctionCount
                ? "FULL"
                : "PARTIAL";
    }

    private static String effectFrom(String value, boolean deterministic) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "", "NONE", "NO_SIDE_EFFECT", "NO_SIDE_EFFECTS", "PURE" ->
                    deterministic || normalized.isBlank() ? "PURE" : "EXTERNAL";
            case "READ", "READ_ONLY", "READ_EXTERNAL" -> "READ_EXTERNAL";
            case "WRITE", "WRITE_ONLY", "MUTATION", "WRITE_EXTERNAL" -> "WRITE_EXTERNAL";
            default -> "EXTERNAL";
        };
    }

    private static String idempotencyFrom(String value, boolean deterministic) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "DETERMINISTIC", "IDEMPOTENT", "NON_IDEMPOTENT", "UNKNOWN" -> normalized;
            case "" -> deterministic ? "DETERMINISTIC" : "UNKNOWN";
            default -> "UNKNOWN";
        };
    }

    private static String normalizedFunctionType(String value,
                                                 String target,
                                                 List<VisualDiagnostic> diagnostics) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "any";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        String mapped = switch (lower) {
            case "any", "unknown", "json" -> lower;
            case "string", "charsequence", "java.lang.string" -> "string";
            case "integer", "int", "java.lang.integer", "long", "java.lang.long", "short", "byte" -> "integer";
            case "number", "double", "java.lang.double", "float", "java.lang.float",
                    "bigdecimal", "java.math.bigdecimal", "decimal" -> "number";
            case "boolean", "bool", "java.lang.boolean" -> "boolean";
            case "object", "map", "java.util.map", "jsonobject" -> "object";
            case "array", "list", "java.util.list", "collection", "java.util.collection" -> "array";
            case "date", "datetime" -> lower;
            default -> "";
        };
        if (!mapped.isBlank()) {
            return mapped;
        }
        diagnostics.add(VisualDiagnostic.warning("visual.capabilityCatalog.function.typeUnknown",
                "Function type '%s' is not a compact visual type; using unknown."
                        .formatted(value),
                target,
                Map.of("sourceType", value)));
        return "unknown";
    }

    private static String jsonTypeFromJavaType(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith("string") || lower.equals("charsequence")) {
            return "string";
        }
        if (Set.of("int", "integer", "long", "short", "byte", "java.lang.integer", "java.lang.long")
                .contains(lower)) {
            return "integer";
        }
        if (Set.of("double", "float", "bigdecimal", "number", "java.math.bigdecimal", "java.lang.double")
                .contains(lower)) {
            return "number";
        }
        if (Set.of("boolean", "bool", "java.lang.boolean").contains(lower)) {
            return "boolean";
        }
        if (lower.contains("list") || lower.contains("collection") || lower.endsWith("[]")) {
            return "array";
        }
        if (lower.contains("map") || lower.endsWith("object")) {
            return "object";
        }
        return "";
    }

    private static boolean looksLikeJsonSchema(Map<String, Object> map) {
        return map.containsKey("type")
                || map.containsKey("properties")
                || map.containsKey("oneOf")
                || map.containsKey("anyOf")
                || map.containsKey("$ref");
    }

    private static String visualLibraryId(String catalogId) {
        String value = firstNonBlank(catalogId, "business-capability-catalog")
                .trim()
                .replaceAll("[^A-Za-z0-9_.:-]", "-");
        return value.isBlank() ? "business-capability-catalog" : value;
    }

    private static String visualLibraryVersion(String blogeVersion) {
        if (blogeVersion == null || blogeVersion.isBlank()) {
            return DEFAULT_VERSION;
        }
        String trimmed = blogeVersion.trim();
        return trimmed.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
                ? trimmed
                : DEFAULT_VERSION;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? string(second) : first.trim();
    }

    private static void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                maps.add(objectMap(map));
            }
        }
        return maps;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return objectMap(map);
    }

    private static Map<String, Object> objectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static final class ProjectionStats {
        private int opaqueSchemaCount;
        private int sourceDiagnosticCount;
        private final Set<String> sourceKinds = new LinkedHashSet<>();
    }
}
