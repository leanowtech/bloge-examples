package com.leanowtech.bloge.gateway.agenttdd;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the public MCP tool catalog promised by the RG 1.4 design. */
class McpToolCatalogTest {

    @Test
    void exposesTheCompleteFiveStageCatalogWithHonestImpactLevels() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.all()).hasSize(24);
        assertThat(catalog.require("rg.capability.list").impact()).isEqualTo(McpToolImpact.READ);
        assertThat(catalog.require("rg.library.upsert").impact()).isEqualTo(McpToolImpact.DRAFT_WRITE);
        assertThat(catalog.require("rg.oracle.propose").impact()).isEqualTo(McpToolImpact.PROPOSE);
        assertThat(catalog.require("rg.simulate").impact()).isEqualTo(McpToolImpact.EXECUTE);
        assertThat(catalog.require("rg.tool.publish").impact()).isEqualTo(McpToolImpact.GOVERNED_WRITE);
        assertThat(catalog.all()).extracting(McpToolDefinition::name).doesNotHaveDuplicates();
    }

    @Test
    void publishesConcreteSchemasInsteadOfUnboundedPlaceholderObjects() {
        McpToolCatalog catalog = new McpToolCatalog();

        McpToolDefinition simulate = catalog.require("rg.simulate");
        assertThat(simulate.inputSchema()).containsEntry("type", "object");
        assertThat(simulate.inputSchema()).containsKey("properties");
        Map<?, ?> properties = (Map<?, ?>) simulate.inputSchema().get("properties");
        assertThat(properties.containsKey("toolRef")).isTrue();
        assertThat(properties.containsKey("libraryRefs")).isTrue();
        assertThat(properties.containsKey("cases")).isTrue();
        assertThat(simulate.outputSchema()).containsKey("properties");
    }
}
