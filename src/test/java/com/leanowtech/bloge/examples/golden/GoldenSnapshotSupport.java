package com.leanowtech.bloge.examples.golden;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeError;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.ext.checkpoint.SessionCheckpoint;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Normalizes example execution outputs into deterministic JSON snapshots.
 *
 * <p>The projection intentionally strips runtime-generated metadata such as elapsed time,
 * execution IDs, and listener timestamps so the baselines focus on semantic behavior.</p>
 */
final class GoldenSnapshotSupport {

    private static final Path GOLDEN_ROOT = resolveGoldenRoot();
    private static final boolean UPDATE_GOLDEN = Boolean.getBoolean("bloge.updateGolden");
    private static final Pattern ISO_INSTANT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TRANSFER_ID = Pattern.compile("TXN-[0-9a-f]{8}");
    private static final Set<String> DYNAMIC_FIELD_NAMES = Set.of(
            "timestamp",
            "executionid",
            "elapsed",
            "startedat",
            "lasttouchat",
            "checkpointedat"
    );
    private static final Set<String> DYNAMIC_ID_FIELDS = Set.of("sessionid", "transferid");

    private GoldenSnapshotSupport() {
    }

    static void assertMatchesGolden(ExampleGoldenScenarios.GoldenScenario scenario) throws Exception {
        Object fixtureOutput = scenario.executeFixture();
        String actualJson = renderSnapshot(fixtureOutput);
        Path goldenPath = GOLDEN_ROOT.resolve(scenario.resourcePath());
        if (UPDATE_GOLDEN || Files.notExists(goldenPath)) {
            Files.createDirectories(goldenPath.getParent());
            Files.writeString(goldenPath, actualJson, StandardCharsets.UTF_8);
        }
        if (Files.notExists(goldenPath)) {
            Assertions.fail("Missing golden file " + goldenPath + ". Re-run with -Dbloge.updateGolden=true to create it.");
        }
        String expectedJson = Files.readString(goldenPath, StandardCharsets.UTF_8);
        Assertions.assertEquals(expectedJson, actualJson, () -> "Golden snapshot mismatch for " + scenario.resourcePath());
    }

    private static String renderSnapshot(Object fixtureOutput) {
        Object snapshot = switch (fixtureOutput) {
            case GraphResult graphResult -> snapshotGraphResult(graphResult);
            case SessionCheckpoint sessionCheckpoint -> snapshotSessionCheckpoint(sessionCheckpoint);
            case null -> throw new IllegalArgumentException("Fixture returned null");
            default -> throw new IllegalArgumentException(
                    "Unsupported golden snapshot source: " + fixtureOutput.getClass().getName());
        };
        return prettyPrint(JsonCodec.DEFAULT.serialize(snapshot)) + System.lineSeparator();
    }

    private static Map<String, Object> snapshotGraphResult(GraphResult result) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", "graph-result");
        snapshot.put("success", result.isSuccess());
        snapshot.put("statuses", sortedStatuses(result));
        snapshot.put("outputs", snapshotOutputs(result));
        if (!result.suspendedNodes().isEmpty()) {
            snapshot.put("suspendedNodes", normalizeMap(result.suspendedNodes()));
        }
        if (!result.errors().isEmpty()) {
            snapshot.put("errors", snapshotErrors(result.errors()));
        }
        return snapshot;
    }

    private static Map<String, Object> snapshotSessionCheckpoint(SessionCheckpoint checkpoint) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", "session-checkpoint");
        snapshot.put("sessionGraphName", checkpoint.sessionGraphName());
        snapshot.put("status", checkpoint.status().name());
        snapshot.put("currentPhaseId", checkpoint.currentPhaseId());
        snapshot.put("currentPhaseRound", checkpoint.currentPhaseRound());
        snapshot.put("totalRounds", checkpoint.totalRounds());
        snapshot.put("phaseOutputs", normalizeValue("phaseOutputs", checkpoint.phaseOutputs()));
        snapshot.put("history", normalizeValue("history", checkpoint.history()));
        snapshot.put("sharedState", normalizeValue("sharedState", checkpoint.sharedState()));
        snapshot.put("contextData", normalizeValue("contextData", checkpoint.contextData()));
        return snapshot;
    }

    private static Map<String, Object> sortedStatuses(GraphResult result) {
        LinkedHashMap<String, Object> statuses = new LinkedHashMap<>();
        result.statusMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> statuses.put(entry.getKey(), entry.getValue().name()));
        return statuses;
    }

    private static Map<String, Object> snapshotOutputs(GraphResult result) {
        LinkedHashMap<String, Object> outputs = new LinkedHashMap<>();
        TreeSet<String> nodeIds = new TreeSet<>();
        nodeIds.addAll(result.results().completedNodes());
        for (String nodeId : nodeIds) {
            outputs.put(nodeId, normalizeValue(nodeId, result.results().getRaw(nodeId)));
        }
        return outputs;
    }

    private static List<Object> snapshotErrors(List<NodeError> errors) {
        List<Object> normalized = new ArrayList<>();
        for (NodeError error : errors) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", error.nodeId());
            entry.put("attempt", error.attempt());
            entry.put("exceptionType", error.exception().getClass().getName());
            entry.put("message", normalizeValue("message", error.exception().getMessage()));
            normalized.add(entry);
        }
        return normalized;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> input) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        input.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> normalized.put(
                        String.valueOf(entry.getKey()),
                        normalizeValue(String.valueOf(entry.getKey()), entry.getValue())));
        return normalized;
    }

    private static Object normalizeValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (shouldReplaceWithPlaceholder(fieldName, value)) {
            return placeholder(fieldName);
        }
        return switch (value) {
            case String stringValue -> normalizeString(fieldName, stringValue);
            case Boolean ignored -> value;
            case Number ignored -> value;
            case Instant instant -> instant.toString();
            case Duration duration -> duration.toString();
            case Enum<?> enumValue -> enumValue.name();
            case Map<?, ?> mapValue -> normalizeMap(mapValue);
            case Collection<?> collectionValue -> normalizeCollection(fieldName, collectionValue);
            case null -> null;
            default -> {
                Class<?> valueClass = value.getClass();
                if (valueClass.isArray()) {
                    yield normalizeArray(fieldName, value);
                }
                if (valueClass.isRecord()) {
                    yield normalizeRecord(value);
                }
                yield String.valueOf(value);
            }
        };
    }

    private static List<Object> normalizeCollection(String fieldName, Collection<?> collection) {
        List<Object> normalized = new ArrayList<>(collection.size());
        for (Object item : collection) {
            normalized.add(normalizeValue(fieldName, item));
        }
        return normalized;
    }

    private static List<Object> normalizeArray(String fieldName, Object arrayValue) {
        int length = Array.getLength(arrayValue);
        List<Object> normalized = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            normalized.add(normalizeValue(fieldName, Array.get(arrayValue, index)));
        }
        return normalized;
    }

    private static Map<String, Object> normalizeRecord(Object record) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                normalized.put(component.getName(), normalizeValue(component.getName(), component.getAccessor().invoke(record)));
            } catch (ReflectiveOperationException reflectionException) {
                throw new IllegalStateException(
                        "Failed to snapshot record component '" + component.getName() + "' from " + record.getClass().getName(),
                        reflectionException
                );
            }
        }
        return normalized;
    }

    private static boolean shouldReplaceWithPlaceholder(String fieldName, Object value) {
        String normalizedField = normalizeFieldName(fieldName);
        if (DYNAMIC_FIELD_NAMES.contains(normalizedField)) {
            return true;
        }
        if (DYNAMIC_ID_FIELDS.contains(normalizedField)
                && value instanceof String stringValue
                && (UUID_VALUE.matcher(stringValue).matches() || TRANSFER_ID.matcher(stringValue).matches())) {
            return true;
        }
        return normalizedField.endsWith("timestamp") && value instanceof Number;
    }

    private static Object normalizeString(String fieldName, String value) {
        if (value == null) {
            return null;
        }
        if (shouldReplaceWithPlaceholder(fieldName, value)) {
            return placeholder(fieldName);
        }
        if (ISO_INSTANT.matcher(value).matches()) {
            return "<instant>";
        }
        return value;
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
    }

    private static String placeholder(String fieldName) {
        return "<" + Objects.requireNonNullElse(fieldName, "value") + ">";
    }

    private static Path resolveGoldenRoot() {
        Path workingDirectoryRoot = Path.of("src", "test", "resources", "golden");
        if (Files.isDirectory(workingDirectoryRoot)) {
            return workingDirectoryRoot;
        }
        try {
            Path testClassesDirectory = Path.of(
                    GoldenSnapshotSupport.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path moduleRoot = testClassesDirectory.getParent().getParent();
            Path moduleRelativeRoot = moduleRoot.resolve(Path.of("src", "test", "resources", "golden"));
            if (Files.isDirectory(moduleRelativeRoot)) {
                return moduleRelativeRoot;
            }
            throw new IllegalStateException("Golden resource root not found under " + moduleRoot);
        } catch (URISyntaxException uriSyntaxException) {
            throw new IllegalStateException("Failed to resolve golden resource root", uriSyntaxException);
        }
    }

    private static String prettyPrint(String json) {
        StringBuilder formatted = new StringBuilder(json.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int index = 0; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (escaping) {
                formatted.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                formatted.append(ch);
                if (inString) {
                    escaping = true;
                }
                continue;
            }
            if (ch == '"') {
                formatted.append(ch);
                inString = !inString;
                continue;
            }
            if (inString) {
                formatted.append(ch);
                continue;
            }
            switch (ch) {
                case '{', '[' -> {
                    formatted.append(ch).append('\n');
                    indent++;
                    appendIndent(formatted, indent);
                }
                case '}', ']' -> {
                    formatted.append('\n');
                    indent--;
                    appendIndent(formatted, indent);
                    formatted.append(ch);
                }
                case ',' -> {
                    formatted.append(ch).append('\n');
                    appendIndent(formatted, indent);
                }
                case ':' -> formatted.append(": ");
                default -> {
                    if (!Character.isWhitespace(ch)) {
                        formatted.append(ch);
                    }
                }
            }
        }
        return formatted.toString();
    }

    private static void appendIndent(StringBuilder formatted, int indent) {
        for (int level = 0; level < indent; level++) {
            formatted.append("  ");
        }
    }
}
