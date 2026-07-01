package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes schema and execution surface changes between two operator definitions.
 */
public final class OperatorDefinitionChangeSummary {

    private static final int MAX_CHANGES = 5;

    private OperatorDefinitionChangeSummary() {
    }

    /**
     * @param previous previous operator definition
     * @param replacement replacement operator definition
     * @return concise human-readable change summary
     */
    public static String describe(OperatorDefinition previous, OperatorDefinition replacement) {
        if (previous == null || replacement == null) {
            return "";
        }
        List<String> changes = new ArrayList<>();
        addIfChanged(changes, previous.operatorVersion(), replacement.operatorVersion(), "operator version");
        addIfChanged(changes, previous.source(), replacement.source(), "source metadata");
        describePorts("input", previous.ports().inputs(), replacement.ports().inputs(), changes);
        describePorts("output", previous.ports().outputs(), replacement.ports().outputs(), changes);
        addSchemaChange(changes, previous.configSchema(), replacement.configSchema(), "config schema");
        addIfChanged(changes, previous.capabilities(), replacement.capabilities(), "capabilities");
        addIfChanged(changes, previous.policy(), replacement.policy(), "availability policy");
        addIfChanged(changes, previous.lowering(), replacement.lowering(), "lowering");
        if (changes.isEmpty()) {
            return "executable operator metadata changed";
        }
        int visible = Math.min(changes.size(), MAX_CHANGES);
        String summary = String.join("; ", changes.subList(0, visible));
        int remaining = changes.size() - visible;
        return remaining == 0 ? summary : summary + "; +" + remaining + " more";
    }

    private static void addIfChanged(List<String> changes, Object previous, Object replacement, String label) {
        if (!Objects.equals(previous, replacement)) {
            changes.add(label + " changed");
        }
    }

    private static void describePorts(String direction,
                                      List<OperatorDefinition.Port> previous,
                                      List<OperatorDefinition.Port> replacement,
                                      List<String> changes) {
        Map<String, OperatorDefinition.Port> previousByName = portsByName(previous);
        Map<String, OperatorDefinition.Port> replacementByName = portsByName(replacement);
        previousByName.keySet().stream()
                .filter(name -> !replacementByName.containsKey(name))
                .forEach(name -> changes.add(direction + " port '" + name + "' removed"));
        replacementByName.keySet().stream()
                .filter(name -> !previousByName.containsKey(name))
                .forEach(name -> changes.add(direction + " port '" + name + "' added"));
        previousByName.forEach((name, previousPort) -> {
            OperatorDefinition.Port replacementPort = replacementByName.get(name);
            if (replacementPort == null) {
                return;
            }
            if (previousPort.required() != replacementPort.required()) {
                changes.add(direction + " port '" + name + "' required flag changed");
            }
            addSchemaChange(changes, previousPort.schema(), replacementPort.schema(),
                    direction + " port '" + name + "' schema");
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

    private static void addSchemaChange(List<String> changes,
                                        SchemaEnvelope previous,
                                        SchemaEnvelope replacement,
                                        String label) {
        if (!Objects.equals(previous, replacement)) {
            changes.add(label + " changed");
        }
    }
}
