package com.leanowtech.bloge.gateway.agenttdd;

import java.util.LinkedHashMap;
import java.util.Map;

/** Wire-visible definition for one RG MCP tool. */
public record McpToolDefinition(
        String name,
        String title,
        String description,
        McpToolImpact impact,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {
    /** Creates an immutable, non-null definition suitable for {@code tools/list}. */
    public McpToolDefinition {
        name = name == null ? "" : name.trim();
        title = title == null || title.isBlank() ? name : title.trim();
        description = description == null ? "" : description.trim();
        impact = java.util.Objects.requireNonNull(impact, "impact");
        inputSchema = Map.copyOf(new LinkedHashMap<>(inputSchema == null ? Map.of() : inputSchema));
        outputSchema = Map.copyOf(new LinkedHashMap<>(outputSchema == null ? Map.of() : outputSchema));
    }

    /** @return protocol projection including standard MCP annotations and RG impact */
    public Map<String, Object> protocolView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("title", title);
        view.put("description", description);
        view.put("inputSchema", inputSchema);
        view.put("outputSchema", outputSchema);
        view.put("annotations", impact.annotations());
        view.put("x-rg-impact", impact.name());
        return Map.copyOf(view);
    }
}
