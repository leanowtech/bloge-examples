package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that Agent guidance references only tools in the canonical MCP catalog. */
class McpAgentInstructionRendererTest {
    private static final Pattern TOOL_NAME = Pattern.compile("rg\\.[a-zA-Z0-9_.]+");

    @Test
    void rendersBusinessAndDslGuidanceOnlyFromCataloguedToolNames() {
        McpToolCatalog catalog = new McpToolCatalog();

        String instructions = new McpAgentInstructionRenderer(catalog).render();

        Set<String> catalogued = catalog.all().stream()
                .map(McpToolDefinition::name)
                .collect(Collectors.toUnmodifiableSet());
        Matcher matcher = TOOL_NAME.matcher(instructions);
        while (matcher.find()) {
            assertThat(catalogued).contains(matcher.group());
        }
        assertThat(instructions)
                .contains("rg.library.overview.get", "rg.feature.handoff", "rg.engineering.handoff",
                        "rg.dsl.reference.get", "rg.readiness.get",
                        "Never ask the business user for YAML", "never ask the user to write DSL");
    }
}
