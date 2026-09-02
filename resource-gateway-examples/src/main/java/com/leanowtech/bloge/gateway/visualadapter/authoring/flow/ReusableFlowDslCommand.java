package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Human-oriented BLOGE DSL command for creating or updating a reusable Flow.
 *
 * <p>The DSL owns graph semantics. {@code dependencyPins} separately binds every authored
 * operator reference to one immutable API Resource, published Flow, or Operator coordinate.
 * Fixture selections and other per-run controls deliberately do not belong to this command.</p>
 */
public record ReusableFlowDslCommand(
        String schemaVersion,
        String displayName,
        ReusableFlowCommand.Kind kind,
        String description,
        Source source,
        Map<String, ReusableFlowCommand.ComposableRef> dependencyPins
) {
    public static final String SCHEMA_VERSION = "bloge.reusableFlowDslSaveCommand.v1";
    private static final Pattern DEPENDENCY_PIN_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public ReusableFlowDslCommand {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported reusable Flow DSL command schema version");
        }
        displayName = boundedNonBlank(displayName, 200, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
        description = bounded(Objects.requireNonNull(description, "description"), 2000, "description");
        source = Objects.requireNonNull(source, "source");
        if (dependencyPins == null || dependencyPins.isEmpty()) {
            throw new IllegalArgumentException("dependencyPins must not be empty");
        }
        LinkedHashMap<String, ReusableFlowCommand.ComposableRef> pins = new LinkedHashMap<>();
        dependencyPins.forEach((key, value) -> {
            if (key == null || !DEPENDENCY_PIN_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid dependency pin key");
            }
            pins.put(key, Objects.requireNonNull(value, "dependency pin"));
        });
        dependencyPins = Map.copyOf(pins);
    }

    /** BLOGE source identity and exact text retained for parsing and source diagnostics. */
    public record Source(String sourceId, String dsl) {
        public Source {
            sourceId = boundedNonBlank(sourceId, 256, "sourceId");
            dsl = boundedNonBlank(dsl, 524_288, "dsl");
        }
    }

    private static String boundedNonBlank(String value, int maxLength, String field) {
        String bounded = bounded(Objects.requireNonNull(value, field), maxLength, field);
        if (bounded.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return bounded;
    }

    private static String bounded(String value, int maxLength, String field) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds its maximum length");
        }
        return value;
    }
}
