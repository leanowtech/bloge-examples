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

    @Test
    void rendersGuidanceThatDoesNotRecommendToolsHiddenFromTheSelectedSurface() {
        McpToolCatalog catalog = new McpToolCatalog();
        McpAgentInstructionRenderer renderer = new McpAgentInstructionRenderer(catalog);

        String business = renderer.render(McpSurfacePolicy.Surface.BUSINESS_SOLUTION);
        String platform = renderer.render(McpSurfacePolicy.Surface.PLATFORM_AUTHORING);
        String operations = renderer.render(McpSurfacePolicy.Surface.OPERATIONS);

        assertThat(business).contains("rg.library.overview.get", "rg.feature.handoff",
                        "authoringPatternsFingerprint", "two-pass recall", "rg.capability.search",
                        "rg.entity.get", "one unique EXACT", "reuseAllowed=true",
                        "ask exactly one plain-language business question",
                        "never ask the business user to provide or understand them",
                        "primary active journey", "rg.journey.next",
                        "do not start another journey first",
                        "reuse that journey when it allows the requested action",
                        "bind its targetRef to the exact entity already read",
                        "copy its display.businessName verbatim into factName or capabilityName",
                        "Never paraphrase that name or append words such as service",
                        "Include a dependency value only for RETURNS",
                        "omit value for UNAVAILABLE")
                .doesNotContain("rg.dsl.reference.get", "rg.tool.compose");
        assertThat(platform).contains("rg.dsl.reference.get", "rg.readiness.get")
                .doesNotContain("rg.feature.handoff", "rg.solution.compose");
        assertThat(operations).contains("read-only operations surface", "rg.readiness.get")
                .doesNotContain("rg.dsl.reference.get", "rg.solution.publish");
    }
}
