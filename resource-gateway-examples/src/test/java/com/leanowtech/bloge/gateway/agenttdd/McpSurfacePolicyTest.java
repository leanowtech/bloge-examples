package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies server-side MCP surface and authenticated-purpose intersections. */
class McpSurfacePolicyTest {
    private final McpToolCatalog catalog = new McpToolCatalog();
    private final McpSurfacePolicy policy = new McpSurfacePolicy();

    @Test
    void businessSurfaceExposesBusinessToolsAndRejectsPlatformAuthoringTools() {
        assertThat(policy.visible(catalog.require("rg.library.overview.get"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_READ"))).isTrue();
        assertThat(policy.visible(catalog.require("rg.capability.search"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_READ"))).isTrue();
        assertThat(policy.visible(catalog.require("rg.entity.get"),
                McpSurfacePolicy.Surface.OPERATIONS, identity("AGENT_TDD_READ"))).isTrue();
        assertThat(policy.visible(catalog.require("rg.solution.compose"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_AUTHORING"))).isTrue();
        assertThat(policy.visible(catalog.require("rg.tool.compose"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_AUTHORING"))).isFalse();
        assertThat(policy.visible(catalog.require("rg.dsl.reference.get"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_READ"))).isFalse();
        assertThat(policy.visible(catalog.require("rg.scenario.test"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_EXECUTION"))).isFalse();
    }

    @Test
    void explicitSurfaceNeverExpandsTheAuthenticatedPurpose() {
        assertThat(policy.visible(catalog.require("rg.solution.compose"),
                McpSurfacePolicy.Surface.BUSINESS_SOLUTION, identity("AGENT_TDD_READ"))).isFalse();
        assertThat(policy.visible(catalog.require("rg.solution.readiness"),
                McpSurfacePolicy.Surface.OPERATIONS, identity("AGENT_TDD_READ"))).isTrue();
        assertThat(policy.visible(catalog.require("rg.solution.publish"),
                McpSurfacePolicy.Surface.OPERATIONS, identity("AGENT_TDD_GOVERNANCE"))).isFalse();
    }

    @Test
    void missingHeaderUsesLegacyAllAndUnknownHeaderFailsClosed() {
        assertThat(policy.resolve("")).isEqualTo(McpSurfacePolicy.Surface.LEGACY_ALL);
        assertThat(policy.visible(catalog.require("rg.tool.compose"),
                McpSurfacePolicy.Surface.LEGACY_ALL, identity("AGENT_TDD_READ"))).isTrue();
        assertThatThrownBy(() -> policy.resolve("unknown"))
                .isInstanceOfSatisfying(McpProtocolException.class,
                        failure -> assertThat(failure.getMessage()).isEqualTo("Unsupported MCP surface"));
    }

    @Test
    void recordsOnlyLowCardinalityExplicitAndLegacySurfaceNames() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        McpSurfacePolicy measured = new McpSurfacePolicy(new AgentTddAuthoringTelemetry(registry));

        measured.resolve("");
        measured.resolve("BUSINESS_SOLUTION");

        assertThat(registry.get("rg.mcp.surface.requests").tag("surface", "legacy_all")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("rg.mcp.surface.requests").tag("surface", "business_solution")
                .counter().count()).isEqualTo(1.0d);
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", purpose, "corr-1");
    }
}
