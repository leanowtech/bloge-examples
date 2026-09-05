package com.leanowtech.bloge.gateway.agenttdd;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies server-side MCP product-surface visibility without granting authorization.
 *
 * <p>An explicit surface can only remove tools. Visibility is intersected with the authenticated
 * purpose accepted by the tool's impact operation. The temporary {@link Surface#LEGACY_ALL}
 * mode preserves the pre-1.4.6 catalog only when the request omits {@code X-RG-Surface}.</p>
 */
public final class McpSurfacePolicy {
    private static final Set<String> BUSINESS_SOLUTION = Set.of(
            "rg.library.overview.get",
            "rg.feature.define", "rg.feature.handoff", "rg.feature.evaluate",
            "rg.scenario.define", "rg.instruction.define", "rg.engineering.handoff",
            "rg.solution.compose", "rg.solution.getContract", "rg.solution.invoke",
            "rg.solution.baseline", "rg.solution.commit", "rg.solution.readiness",
            "rg.solution.performance", "rg.solution.publish");
    private static final Set<String> OPERATIONS = Set.of(
            "rg.contract.get", "rg.verdict.get", "rg.evidence.get", "rg.readiness.get",
            "rg.solution.getContract", "rg.solution.readiness", "rg.solution.performance");
    private final AgentTddAuthoringTelemetry telemetry;

    /** Creates the compatibility policy with inert telemetry for focused tests. */
    public McpSurfacePolicy() {
        this(AgentTddAuthoringTelemetry.noop());
    }

    McpSurfacePolicy(AgentTddAuthoringTelemetry telemetry) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Stable product surfaces accepted by the MCP boundary. */
    public enum Surface {
        BUSINESS_SOLUTION,
        PLATFORM_AUTHORING,
        OPERATIONS,
        LEGACY_ALL
    }

    /**
     * Resolves a request header, preserving legacy behavior only for a missing value.
     *
     * @param headerValue raw {@code X-RG-Surface} value
     * @return canonical surface
     * @throws McpProtocolException when a nonblank value is not supported
     */
    public Surface resolve(String headerValue) {
        String value = headerValue == null ? "" : headerValue.trim();
        if (value.isBlank()) {
            telemetry.surfaceUsed(Surface.LEGACY_ALL.name());
            return Surface.LEGACY_ALL;
        }
        try {
            Surface surface = Surface.valueOf(value.toUpperCase(Locale.ROOT));
            if (surface == Surface.LEGACY_ALL) {
                throw new IllegalArgumentException();
            }
            telemetry.surfaceUsed(surface.name());
            return surface;
        } catch (IllegalArgumentException failure) {
            throw new McpProtocolException(-32602, "Unsupported MCP surface");
        }
    }

    /**
     * Returns whether one definition is visible in both the product surface and authenticated purpose.
     *
     * @param definition canonical tool definition
     * @param surface resolved request surface
     * @param identity authenticated integration identity
     * @return true when both independent restrictions allow the tool
     */
    public boolean visible(McpToolDefinition definition,
                           Surface surface,
                           IntegrationRequestContext identity) {
        if (definition == null || surface == null || identity == null) return false;
        if (surface == Surface.LEGACY_ALL) return true;
        if (!definition.impact().operation().accepts(identity.purpose())) return false;
        return switch (surface) {
            case BUSINESS_SOLUTION -> BUSINESS_SOLUTION.contains(definition.name());
            case PLATFORM_AUTHORING -> platformAuthoring(definition.name());
            case OPERATIONS -> OPERATIONS.contains(definition.name());
            case LEGACY_ALL -> true;
        };
    }

    /** Returns definitions visible through one resolved surface in stable catalog order. */
    public List<McpToolDefinition> visibleDefinitions(List<McpToolDefinition> definitions,
                                                      Surface surface,
                                                      IntegrationRequestContext identity) {
        if (definitions == null) return List.of();
        return definitions.stream().filter(definition -> visible(definition, surface, identity)).toList();
    }

    /** Rejects direct calls that bypass a surface-filtered {@code tools/list}. */
    public void requireVisible(McpToolDefinition definition,
                               Surface surface,
                               IntegrationRequestContext identity) {
        if (!visible(definition, surface, identity)) {
            throw new McpProtocolException(-32031, "TOOL_NOT_VISIBLE_IN_SURFACE");
        }
    }

    private static boolean platformAuthoring(String name) {
        return name != null && !name.startsWith("rg.solution.")
                && !name.equals("rg.feature.define")
                && !name.equals("rg.feature.handoff")
                && !name.equals("rg.feature.evaluate")
                && !name.equals("rg.scenario.define")
                && !name.equals("rg.instruction.define")
                && !name.equals("rg.engineering.handoff");
    }
}
