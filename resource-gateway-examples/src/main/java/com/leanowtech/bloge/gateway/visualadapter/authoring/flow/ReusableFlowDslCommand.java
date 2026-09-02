package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    public ReusableFlowDslCommand {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        displayName = Objects.requireNonNull(displayName, "displayName");
        kind = Objects.requireNonNull(kind, "kind");
        description = Objects.requireNonNull(description, "description");
        source = Objects.requireNonNull(source, "source");
        dependencyPins = dependencyPins == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(dependencyPins));
    }

    /** BLOGE source identity and exact text retained for parsing and source diagnostics. */
    public record Source(String sourceId, String dsl) {
        public Source {
            sourceId = sourceId == null || sourceId.isBlank() ? "inline.bloge" : sourceId.trim();
            dsl = Objects.requireNonNull(dsl, "dsl");
        }
    }
}
