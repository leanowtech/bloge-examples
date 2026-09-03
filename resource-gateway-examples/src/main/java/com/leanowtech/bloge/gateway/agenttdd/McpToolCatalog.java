package com.leanowtech.bloge.gateway.agenttdd;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical five-stage tool catalog for the Resource Gateway business-TDD operating surface.
 *
 * <p>Definitions live in one list so protocol discovery, authorization and application dispatch
 * cannot silently disagree about a tool's stable name or impact level.</p>
 */
@Component
public final class McpToolCatalog {
    private final Map<String, McpToolDefinition> definitions;

    /** Builds the frozen 1.4 catalog in workflow order. */
    public McpToolCatalog() {
        List<McpToolDefinition> values = new ArrayList<>();
        values.add(tool("rg.capability.list", "List capabilities", "List API, Feature and Tool assets.",
                McpToolImpact.READ, props("kind", string(), "cursor", string()), List.of()));
        values.add(tool("rg.library.get", "Get library", "Read one exact library contract.",
                McpToolImpact.READ, props("libraryId", string()), List.of("libraryId")));
        values.add(tool("rg.library.list", "List libraries", "List visible library contracts and speccing state.",
                McpToolImpact.READ, props("includeDeprecated", bool()), List.of()));
        values.add(tool("rg.contract.get", "Get business contract", "Read the business contract for an asset.",
                McpToolImpact.READ, props("assetRef", string()), List.of("assetRef")));
        values.add(tool("rg.tool.getInstruction", "Get tool instruction", "Read the Agent-facing tool contract.",
                McpToolImpact.READ, props("toolRef", string()), List.of("toolRef")));
        values.add(tool("rg.scenario.listCases", "List scenario cases", "Read golden and regression table rows.",
                McpToolImpact.READ, props("caseSetRef", string(), "lifecycle", string()), List.of("caseSetRef")));
        values.add(tool("rg.verdict.get", "Get verdict", "Read layered red-to-green status and business backlog.",
                McpToolImpact.READ, props("toolRef", string()), List.of("toolRef")));
        values.add(tool("rg.evidence.get", "Get evidence", "Read a classification-filtered execution evidence lens.",
                McpToolImpact.READ, props("evidenceRef", string(), "view", string()), List.of("evidenceRef")));

        values.add(tool("rg.library.upsert", "Upsert library", "Compile and save a library authoring YAML document.",
                McpToolImpact.DRAFT_WRITE,
                props("libraryYaml", string(), "idempotencyKey", string()),
                List.of("libraryYaml", "idempotencyKey")));
        values.add(tool("rg.feature.compose", "Compose feature", "Create or replace a Feature graph draft.",
                McpToolImpact.DRAFT_WRITE, composeProperties(), List.of("featureRef", "graph", "libraryRefs", "idempotencyKey")));
        values.add(tool("rg.tool.compose", "Compose tool", "Create or replace a Tool graph draft.",
                McpToolImpact.DRAFT_WRITE, composeProperties(), List.of("toolRef", "graph", "libraryRefs", "idempotencyKey")));
        values.add(tool("rg.tool.setInstruction", "Set tool instruction", "Set Agent semantics; examples remain golden-derived.",
                McpToolImpact.DRAFT_WRITE,
                props("toolRef", string(), "instruction", object(), "idempotencyKey", string()),
                List.of("toolRef", "instruction", "idempotencyKey")));
        values.add(tool("rg.scenario.upsertCases", "Upsert scenarios", "Write cases; GOLDEN rows become human-review proposals.",
                McpToolImpact.DRAFT_WRITE,
                props("caseSetRef", string(), "rows", array(), "enumerateFrom", object(), "idempotencyKey", string()),
                List.of("caseSetRef", "rows", "idempotencyKey")));
        values.add(tool("rg.oracle.propose", "Propose oracle", "Propose a business-owned expected outcome.",
                McpToolImpact.PROPOSE,
                props("caseSetRef", string(), "caseId", string(), "expect", object(), "oracleOwner", string(),
                        "idempotencyKey", string()),
                List.of("caseSetRef", "caseId", "expect", "oracleOwner", "idempotencyKey")));
        values.add(tool("rg.scenario.setDependencyBehavior", "Set dependency behavior",
                "Set a bounded RETURN, ERROR, DELAY, TIMEOUT, REPLAY, OBSERVE or MUST_NOT_CALL stub.",
                McpToolImpact.DRAFT_WRITE,
                props("caseSetRef", string(), "caseId", string(), "nodeId", string(), "behavior", object(),
                        "idempotencyKey", string()),
                List.of("caseSetRef", "caseId", "nodeId", "behavior", "idempotencyKey")));

        values.add(tool("rg.dsl.preview", "Preview DSL", "Compile with an explicit library contract context.",
                McpToolImpact.READ, previewProperties(), List.of("source", "libraryRefs")));
        values.add(tool("rg.gate.check", "Check merge gate", "Evaluate compile, contract and honest-verdict gates.",
                McpToolImpact.READ, previewProperties(), List.of("source", "libraryRefs")));

        values.add(tool("rg.feature.rehearse", "Rehearse feature", "Run fixture-only Feature rehearsal with zero egress.",
                McpToolImpact.EXECUTE,
                props("featureRef", string(), "libraryRefs", stringArray(), "cases", object()),
                List.of("featureRef", "libraryRefs", "cases")));
        values.add(tool("rg.tool.baseline", "Baseline tool", "Run multi-case, multi-round business baseline.",
                McpToolImpact.EXECUTE,
                props("toolRef", string(), "libraryRefs", stringArray(), "caseSetRef", string(), "cases", object(),
                        "rounds", integer(), "side", string()),
                List.of("toolRef", "libraryRefs", "caseSetRef")));
        values.add(tool("rg.simulate", "Simulate", "Run one side of the red-to-green line with honest evidence.",
                McpToolImpact.EXECUTE,
                props("toolRef", string(), "libraryRefs", stringArray(), "cases", object(),
                        "adhocFixtures", array(), "side", string()),
                List.of("toolRef", "libraryRefs", "cases")));

        values.add(tool("rg.fixture.promote", "Promote fixture", "Promote one server-captured output to governed fixture.",
                McpToolImpact.GOVERNED_WRITE,
                props("draftId", string(), "nodeId", string(), "outputPort", string(), "fixtureId", string(),
                        "category", string(), "retentionDays", integer(), "redactPaths", stringArray(),
                        "idempotencyKey", string()),
                List.of("draftId", "nodeId", "outputPort", "fixtureId", "category", "retentionDays", "idempotencyKey")));
        values.add(tool("rg.tool.publishSpec", "Publish specification", "Propose an immutable speccing artifact for review.",
                McpToolImpact.PROPOSE,
                props("toolRef", string(), "idempotencyKey", string()), List.of("toolRef", "idempotencyKey")));
        values.add(tool("rg.tool.publish", "Publish tool", "Publish an immutable executable tool after all gates pass.",
                McpToolImpact.GOVERNED_WRITE,
                props("toolRef", string(), "signoffRef", string(), "idempotencyKey", string()),
                List.of("toolRef", "signoffRef", "idempotencyKey")));
        values.add(tool("rg.readiness.get", "Get readiness", "Read publish gates and remaining limitations.",
                McpToolImpact.READ, props("toolRef", string()), List.of("toolRef")));

        LinkedHashMap<String, McpToolDefinition> indexed = new LinkedHashMap<>();
        values.forEach(value -> {
            if (indexed.put(value.name(), value) != null) {
                throw new IllegalStateException("Duplicate MCP tool " + value.name());
            }
        });
        definitions = java.util.Collections.unmodifiableMap(indexed);
    }

    /** @return all definitions in stable workflow order */
    public List<McpToolDefinition> all() {
        return List.copyOf(definitions.values());
    }

    /** @return exact definition or a protocol-safe not-found failure */
    public McpToolDefinition require(String name) {
        McpToolDefinition definition = definitions.get(name == null ? "" : name.trim());
        if (definition == null) {
            throw new McpProtocolException(-32601, "Unknown MCP tool");
        }
        return definition;
    }

    private static McpToolDefinition tool(String name,
                                          String title,
                                          String description,
                                          McpToolImpact impact,
                                          Map<String, Object> properties,
                                          List<String> required) {
        return new McpToolDefinition(name, title, description, impact,
                schema(properties, required), envelopeSchema());
    }

    private static Map<String, Object> composeProperties() {
        return props("featureRef", string(), "toolRef", string(), "graph", object(),
                "libraryRefs", stringArray(), "idempotencyKey", string());
    }

    private static Map<String, Object> previewProperties() {
        return props("source", Map.of("oneOf", List.of(string(), object())), "libraryRefs", stringArray());
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> envelopeSchema() {
        return schema(props("ok", bool(), "data", object(), "diagnostics", array(), "error", object()),
                List.of("ok"));
    }

    private static Map<String, Object> props(Object... entries) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(values);
    }

    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer"); }
    private static Map<String, Object> object() { return Map.of("type", "object"); }
    private static Map<String, Object> array() { return Map.of("type", "array"); }
    private static Map<String, Object> stringArray() {
        return Map.of("type", "array", "items", string(), "uniqueItems", true);
    }
}
