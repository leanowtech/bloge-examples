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
    private static final Set<String> SEMANTIC_RECALL_ROLLOUT = Set.of(
            "rg.library.overview.get",
            "rg.capability.search", "rg.entity.list", "rg.entity.get",
            "rg.journey.start", "rg.journey.next",
            "rg.solution.golden.propose", "rg.solution.golden.list");
    private static final Set<String> BUSINESS_SOLUTION = Set.of(
            "rg.library.overview.get",
            "rg.capability.search", "rg.entity.list", "rg.entity.get",
            "rg.journey.start", "rg.journey.next",
            "rg.solution.golden.propose", "rg.solution.golden.list",
            "rg.solution.coverage",
            "rg.feature.define", "rg.feature.handoff", "rg.feature.evaluate",
            "rg.scenario.define", "rg.instruction.define", "rg.engineering.handoff",
            "rg.solution.compose", "rg.solution.getContract", "rg.solution.invoke",
            "rg.solution.baseline", "rg.solution.commit", "rg.solution.readiness",
            "rg.solution.performance", "rg.solution.publish");
    private static final Set<String> PLATFORM_AUTHORING = Set.of(
            "rg.capability.list", "rg.library.get", "rg.library.list",
            "rg.library.overview.get", "rg.capability.search", "rg.entity.list", "rg.entity.get",
            "rg.journey.start", "rg.journey.next",
            "rg.contract.get", "rg.tool.getInstruction", "rg.scenario.listCases",
            "rg.verdict.get", "rg.evidence.get", "rg.dsl.reference.get",
            "rg.library.upsert", "rg.resource.declare", "rg.feature.compose", "rg.tool.compose",
            "rg.tool.setInstruction", "rg.scenario.upsertCases", "rg.oracle.propose",
            "rg.scenario.setDependencyBehavior", "rg.scenario.test", "rg.dsl.preview", "rg.gate.check",
            "rg.feature.rehearse", "rg.tool.baseline", "rg.simulate",
            "rg.fixture.promote", "rg.fixture.provide", "rg.tool.publishSpec",
            "rg.tool.publish", "rg.readiness.get");
    private static final Set<String> OPERATIONS = Set.of(
            "rg.contract.get", "rg.verdict.get", "rg.evidence.get", "rg.readiness.get",
            "rg.capability.search", "rg.entity.list", "rg.entity.get",
            "rg.solution.getContract", "rg.solution.readiness", "rg.solution.performance");
    private final AgentTddAuthoringTelemetry telemetry;
    private final SemanticRecallProperties properties;

    /** Creates the compatibility policy with inert telemetry for focused tests. */
    public McpSurfacePolicy() {
        this(AgentTddAuthoringTelemetry.noop(), new SemanticRecallProperties());
    }

    McpSurfacePolicy(AgentTddAuthoringTelemetry telemetry) {
        this(telemetry, new SemanticRecallProperties());
    }

    McpSurfacePolicy(AgentTddAuthoringTelemetry telemetry, SemanticRecallProperties properties) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
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
            if (properties.isRequireSurface()) {
                throw new McpProtocolException(-32602, "SURFACE_REQUIRED");
            }
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
        if (!properties.isEnabled() && SEMANTIC_RECALL_ROLLOUT.contains(definition.name())) return false;
        if (!properties.isControlledBusinessTestsEnabled()
                && surface == Surface.BUSINESS_SOLUTION
                && "rg.solution.baseline".equals(definition.name())) return false;
        if (surface == Surface.LEGACY_ALL) return true;
        if (!definition.impact().operation().accepts(identity.purpose())) return false;
        return switch (surface) {
            case BUSINESS_SOLUTION -> BUSINESS_SOLUTION.contains(definition.name());
            case PLATFORM_AUTHORING -> PLATFORM_AUTHORING.contains(definition.name());
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

    /**
     * Returns every tool that has been deliberately assigned to at least one explicit product surface.
     *
     * <p>The catalog consistency test consumes this closed classification. A newly added tool therefore
     * remains invisible until its product owner assigns it to a surface instead of inheriting access from
     * a negative prefix rule.</p>
     */
    static Set<String> explicitlyClassifiedTools() {
        java.util.LinkedHashSet<String> classified = new java.util.LinkedHashSet<>();
        classified.addAll(BUSINESS_SOLUTION);
        classified.addAll(PLATFORM_AUTHORING);
        classified.addAll(OPERATIONS);
        return Set.copyOf(classified);
    }
}
