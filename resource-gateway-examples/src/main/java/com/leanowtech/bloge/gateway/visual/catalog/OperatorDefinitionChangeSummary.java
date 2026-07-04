package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.SchemaCompatibilityIssue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemaCompatibilityIssueDetail;

/**
 * Describes schema and execution surface changes between two operator definitions.
 */
public final class OperatorDefinitionChangeSummary {

    private static final int MAX_CHANGES = 5;
    public static final String RISK_BREAKING_SCHEMA = "BREAKING_SCHEMA";
    public static final String RISK_RUNTIME_BINDING = "RUNTIME_BINDING";
    public static final String RISK_GOVERNANCE = "GOVERNANCE";
    public static final String RISK_POLICY = "POLICY";
    public static final String RISK_COMPATIBLE_SCHEMA = "COMPATIBLE_SCHEMA";
    public static final String RISK_METADATA = "METADATA";

    private OperatorDefinitionChangeSummary() {
    }

    /**
     * @param previous previous operator definition
     * @param replacement replacement operator definition
     * @return concise human-readable change summary
     */
    public static String describe(OperatorDefinition previous, OperatorDefinition replacement) {
        return analyze(previous, replacement).summary();
    }

    /**
     * @param previous previous operator definition
     * @param replacement replacement operator definition
     * @return change report with coarse risk classification for library replacement impact review
     */
    public static ChangeReport analyze(OperatorDefinition previous, OperatorDefinition replacement) {
        if (previous == null || replacement == null) {
            return ChangeReport.empty();
        }
        ChangeAccumulator changes = new ChangeAccumulator();
        addIfChanged(changes, previous.operatorVersion(), replacement.operatorVersion(), "operator version",
                RISK_METADATA);
        addIfChanged(changes, sourceRuntimeIdentity(previous.source()), sourceRuntimeIdentity(replacement.source()),
                "source metadata", RISK_RUNTIME_BINDING);
        describePorts("input", previous.ports().inputs(), replacement.ports().inputs(), changes);
        describePorts("output", previous.ports().outputs(), replacement.ports().outputs(), changes);
        addInputLikeSchemaChange(changes, previous.configSchema(), replacement.configSchema(), "config schema");
        describeCapabilityChanges(changes, previous.capabilities(), replacement.capabilities());
        addIfChanged(changes, previous.policy(), replacement.policy(), "availability policy", RISK_POLICY);
        addIfChanged(changes, previous.lowering(), replacement.lowering(), "lowering", RISK_RUNTIME_BINDING);
        if (changes.isEmpty()) {
            changes.add("executable operator metadata changed", RISK_METADATA);
        }
        return changes.toReport();
    }

    private static void addIfChanged(ChangeAccumulator changes,
                                     Object previous,
                                     Object replacement,
                                     String label,
                                     String risk) {
        if (!Objects.equals(previous, replacement)) {
            changes.add(label + " changed", risk);
        }
    }

    private static Map<String, Object> sourceRuntimeIdentity(OperatorDefinition.Source source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("kind", source.kind());
        identity.put("resourceId", source.resourceId());
        identity.put("method", source.method());
        identity.put("urlTemplate", source.urlTemplate());
        identity.put("virtual", source.virtual());
        return identity;
    }

    private static void describePorts(String direction,
                                      List<OperatorDefinition.Port> previous,
                                      List<OperatorDefinition.Port> replacement,
                                      ChangeAccumulator changes) {
        Map<String, OperatorDefinition.Port> previousByName = portsByName(previous);
        Map<String, OperatorDefinition.Port> replacementByName = portsByName(replacement);
        previousByName.keySet().stream()
                .filter(name -> !replacementByName.containsKey(name))
                .forEach(name -> changes.add(direction + " port '" + name + "' removed",
                        RISK_BREAKING_SCHEMA,
                        direction,
                        name,
                        direction + " port '" + name + "' removed"));
        replacementByName.keySet().stream()
                .filter(name -> !previousByName.containsKey(name))
                .forEach(name -> changes.add(direction + " port '" + name + "' added",
                        "input".equals(direction) && replacementByName.get(name).required()
                                ? RISK_BREAKING_SCHEMA
                                : RISK_COMPATIBLE_SCHEMA,
                        direction,
                        name,
                        direction + " port '" + name + "' added"));
        previousByName.forEach((name, previousPort) -> {
            OperatorDefinition.Port replacementPort = replacementByName.get(name);
            if (replacementPort == null) {
                return;
            }
            if (previousPort.required() != replacementPort.required()) {
                String risk = "input".equals(direction) && replacementPort.required()
                        ? RISK_BREAKING_SCHEMA
                        : RISK_COMPATIBLE_SCHEMA;
                changes.add(direction + " port '" + name + "' required flag changed",
                        risk,
                        direction,
                        name,
                        direction + " port '" + name + "' required flag changed");
            }
            if ("output".equals(direction)) {
                addOutputSchemaChange(changes, previousPort.schema(), replacementPort.schema(),
                        direction + " port '" + name + "' schema");
            } else {
                addInputLikeSchemaChange(changes, previousPort.schema(), replacementPort.schema(),
                        direction + " port '" + name + "' schema");
            }
        });
    }

    private static Map<String, OperatorDefinition.Port> portsByName(List<OperatorDefinition.Port> ports) {
        Map<String, OperatorDefinition.Port> byName = new LinkedHashMap<>();
        if (ports == null) {
            return byName;
        }
        for (OperatorDefinition.Port port : ports) {
            if (port != null) {
                byName.putIfAbsent(port.name(), port);
            }
        }
        return byName;
    }

    private static void addInputLikeSchemaChange(ChangeAccumulator changes,
                                                 SchemaEnvelope previous,
                                                 SchemaEnvelope replacement,
                                                 String label) {
        addSchemaChange(changes, previous, replacement, label, true);
    }

    private static void addOutputSchemaChange(ChangeAccumulator changes,
                                              SchemaEnvelope previous,
                                              SchemaEnvelope replacement,
                                              String label) {
        addSchemaChange(changes, previous, replacement, label, false);
    }

    private static void addSchemaChange(ChangeAccumulator changes,
                                        SchemaEnvelope previous,
                                        SchemaEnvelope replacement,
                                        String label,
                                        boolean inputLike) {
        if (!Objects.equals(previous, replacement)) {
            Map<String, Object> previousSchema = previous == null ? Map.of() : previous.schema();
            Map<String, Object> replacementSchema = replacement == null ? Map.of() : replacement.schema();
            Optional<SchemaCompatibilityIssue> compatibilityIssue = inputLike
                    ? schemaCompatibilityIssueDetail(previousSchema, replacementSchema)
                    : schemaCompatibilityIssueDetail(replacementSchema, previousSchema);
            String risk = compatibilityIssue.isPresent() ? RISK_BREAKING_SCHEMA : RISK_COMPATIBLE_SCHEMA;
            changes.add(label + " changed", risk, schemaSurface(label), schemaPortName(label),
                    compatibilityIssue.map(SchemaCompatibilityIssue::path)
                            .orElse(""),
                    compatibilityIssue.map(SchemaCompatibilityIssue::message)
                            .orElse(label + " changed compatibly"));
        }
    }

    private static String schemaSurface(String label) {
        if (label.startsWith("input port")) {
            return "input";
        }
        if (label.startsWith("output port")) {
            return "output";
        }
        if (label.startsWith("config")) {
            return "config";
        }
        return "";
    }

    private static String schemaPortName(String label) {
        int start = label.indexOf('\'');
        int end = start < 0 ? -1 : label.indexOf('\'', start + 1);
        if (start < 0 || end <= start) {
            return "";
        }
        return label.substring(start + 1, end);
    }

    private static void describeCapabilityChanges(ChangeAccumulator changes,
                                                  OperatorDefinition.Capabilities previous,
                                                  OperatorDefinition.Capabilities replacement) {
        if (Objects.equals(previous, replacement)) {
            return;
        }
        if (previous.streaming() != replacement.streaming()) {
            changes.add("streaming capability changed", RISK_RUNTIME_BINDING);
        }
        if (previous.durable() != replacement.durable()) {
            changes.add("durable capability changed", RISK_RUNTIME_BINDING);
        }
        if (previous.requiresSecrets() != replacement.requiresSecrets()) {
            changes.add("secret requirement changed", RISK_GOVERNANCE);
        }
        if (!Objects.equals(previous.effect(), replacement.effect())) {
            changes.add("effect capability changed", RISK_GOVERNANCE);
        }
        if (!Objects.equals(previous.idempotency(), replacement.idempotency())) {
            changes.add("idempotency capability changed", RISK_GOVERNANCE);
        }
    }

    /**
     * Classified surface change report.
     *
     * @param risk highest-risk category
     * @param categories all risk categories present in the change
     * @param summary concise human-readable summary
     */
    public record ChangeReport(
            String risk,
            List<String> categories,
            String summary,
            List<SchemaChange> schemaChanges
    ) {
        public ChangeReport(String risk, List<String> categories, String summary) {
            this(risk, categories, summary, List.of());
        }

        public ChangeReport {
            risk = risk == null || risk.isBlank() ? RISK_METADATA : risk;
            categories = categories == null ? List.of() : List.copyOf(categories);
            summary = summary == null ? "" : summary;
            schemaChanges = schemaChanges == null ? List.of() : List.copyOf(schemaChanges);
        }

        public static ChangeReport empty() {
            return new ChangeReport(RISK_METADATA, List.of(), "", List.of());
        }
    }

    /**
     * Machine-readable schema surface change for review UIs.
     *
     * @param surface input, output, or config
     * @param portName input/output port name, blank for config schema
     * @param compatibility breaking or compatible
     * @param path schema-relative path inside the port/config schema, blank for whole-surface changes
     * @param message compatibility reason or concise surface-change message
     */
    public record SchemaChange(String surface, String portName, String compatibility, String path, String message) {
        public SchemaChange(String surface, String portName, String compatibility, String message) {
            this(surface, portName, compatibility, "", message);
        }

        public SchemaChange {
            surface = surface == null ? "" : surface;
            portName = portName == null ? "" : portName;
            compatibility = compatibility == null || compatibility.isBlank()
                    ? "compatible"
                    : compatibility.trim().toLowerCase(java.util.Locale.ROOT);
            path = path == null ? "" : path;
            message = message == null ? "" : message;
        }
    }

    private static final class ChangeAccumulator {
        private final List<String> changes = new ArrayList<>();
        private final LinkedHashSet<String> categories = new LinkedHashSet<>();
        private final List<SchemaChange> schemaChanges = new ArrayList<>();

        private void add(String description, String category) {
            changes.add(description);
            categories.add(category);
        }

        private void add(String description,
                         String category,
                         String surface,
                         String portName,
                         String message) {
            add(description, category, surface, portName, "", message);
        }

        private void add(String description,
                         String category,
                         String surface,
                         String portName,
                         String path,
                         String message) {
            add(description, category);
            if (RISK_BREAKING_SCHEMA.equals(category) || RISK_COMPATIBLE_SCHEMA.equals(category)) {
                schemaChanges.add(new SchemaChange(surface, portName,
                        RISK_BREAKING_SCHEMA.equals(category) ? "breaking" : "compatible",
                        path,
                        message == null || message.isBlank() ? description : message));
            }
        }

        private boolean isEmpty() {
            return changes.isEmpty();
        }

        private ChangeReport toReport() {
            int visible = Math.min(changes.size(), MAX_CHANGES);
            String summary = String.join("; ", changes.subList(0, visible));
            int remaining = changes.size() - visible;
            if (remaining > 0) {
                summary = summary + "; +" + remaining + " more";
            }
            List<String> sortedCategories = categories.stream()
                    .sorted((left, right) -> Integer.compare(riskRank(right), riskRank(left)))
                    .toList();
            return new ChangeReport(sortedCategories.isEmpty() ? RISK_METADATA : sortedCategories.getFirst(),
                    sortedCategories,
                    summary,
                    schemaChanges);
        }
    }

    static int riskRank(String risk) {
        return switch (risk == null ? "" : risk) {
            case RISK_BREAKING_SCHEMA -> 6;
            case RISK_RUNTIME_BINDING -> 5;
            case RISK_GOVERNANCE -> 4;
            case RISK_POLICY -> 3;
            case RISK_COMPATIBLE_SCHEMA -> 2;
            case RISK_METADATA -> 1;
            default -> 0;
        };
    }
}
