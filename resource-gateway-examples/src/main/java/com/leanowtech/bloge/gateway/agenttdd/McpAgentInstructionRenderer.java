package com.leanowtech.bloge.gateway.agenttdd;

import java.util.Objects;

/**
 * Renders Agent operating guidance from names owned by the canonical MCP catalog.
 *
 * <p>The prose contains no independently maintained tool-name literals. A renamed or removed
 * capability therefore fails through {@link McpToolCatalog#require(String)} instead of leaving a
 * stale instruction that sends Codex to a nonexistent operation.</p>
 */
public final class McpAgentInstructionRenderer {
    private final McpToolCatalog catalog;

    /** Creates a renderer bound to one immutable catalog. */
    public McpAgentInstructionRenderer(McpToolCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Returns payload-free business and DSL authoring guidance for MCP initialization.
     *
     * @return stable instructions whose referenced tool names are resolved from the catalog
     */
    public String render() {
        String overview = name(McpToolCatalog.LIBRARY_OVERVIEW);
        String featureHandoff = name(McpToolCatalog.FEATURE_HANDOFF);
        String engineeringHandoff = name(McpToolCatalog.ENGINEERING_HANDOFF);
        String reference = name(McpToolCatalog.DSL_REFERENCE);
        String readiness = name(McpToolCatalog.READINESS);
        return "For a business Solution request, converse only in business language: elicit the goal, "
                + "decision facts, rules including the otherwise outcome, possible dispositions, and "
                + "representative expected examples. Never ask the business user for YAML, DSL, schemas, "
                + "bindings, operator references, or implementation details. Translate the agreed intent "
                + "yourself into Feature, Scenario, Instruction, Solution, and GOLDEN proposals. Use "
                + overview + " to reuse governed business building blocks. Define a missing Feature as "
                + "design-only, then call " + featureHandoff + "; never fulfil that handoff or invent an "
                + "evaluator. Every Instruction must include plain businessSemantics. A WRITE Instruction "
                + "without an approved implementation remains design-only: compose the Solution, call "
                + engineeringHandoff + ", and tell the user which business capability awaits engineering; "
                + "never invent a bindingRef or alter one unless it is the exact governed binding returned "
                + "by the platform. Propose GOLDEN examples but never approve them. Present the five-panel "
                + "business review before asking the human owner to approve or sign off. Run the RED/GREEN, "
                + "commit, readiness, and publish sequence only through declared governance boundaries, and "
                + "publish only when publishable=true. For lower-level Tool DSL work, start with " + reference
                + " and treat its languageVersion, contracts, examples, and authoringContextFingerprint as "
                + "authoritative. Generate DSL from the user's business intent; never ask the user to write "
                + "DSL. Preview, revise from authoringDiagnostics, and gate the exact source. Treat "
                + "blocking=true as authoritative even when level is WARNING. Before another preview, "
                + "compare the ordered blocking fingerprint set with the prior rejected receipt. Stop after "
                + "three repair rounds or when the same blocking set appears twice; never refetch and "
                + "resubmit unchanged source after an operator-not-found result. Report "
                + "HUMAN_OR_PLATFORM_REQUIRED or PLATFORM_MAINTAINER instead of guessing. If the business "
                + "intent admits materially different outcomes, stop before generating DSL, report "
                + "BUSINESS_CLARIFICATION_REQUIRED, and ask one business-language clarification without "
                + "technical terms. Compose only the exact accepted source with its context and receipt "
                + "fingerprints, then bind the case set to the toolRef returned by compose. Never invent "
                + "runtime bindings, Oracle approval, GREEN, attestation, or signoff evidence. Human Oracle "
                + "approval and executable signoff require the reviewer boundary. Call " + readiness
                + " before publish and publish only when publishable=true.";
    }

    /**
     * Returns guidance limited to the selected product surface.
     *
     * @param surface server-resolved product surface
     * @return instructions that never recommend a tool hidden from that surface
     */
    public String render(McpSurfacePolicy.Surface surface) {
        Objects.requireNonNull(surface, "surface");
        return switch (surface) {
            case LEGACY_ALL -> render();
            case BUSINESS_SOLUTION -> businessInstructions();
            case PLATFORM_AUTHORING -> platformInstructions();
            case OPERATIONS -> operationsInstructions();
        };
    }

    private String businessInstructions() {
        return "Converse only in business language. Elicit the goal, decision facts, complete rules, "
                + "otherwise outcome, possible dispositions, and representative expected examples. Never ask "
                + "the business user for YAML, DSL, schemas, bindings, operator references, or implementation "
                + "details. Start each change with " + name(McpToolCatalog.JOURNEY_START)
                + " and follow only the allowedNextTools returned by " + name(McpToolCatalog.JOURNEY_NEXT)
                + ". Use " + name(McpToolCatalog.LIBRARY_OVERVIEW) + " and "
                + name(McpToolCatalog.CAPABILITY_SEARCH) + " to reuse only governed "
                + "business capabilities whose meaning is current. Carry the returned "
                + "authoringPatternsFingerprint unchanged in every journey-scoped Feature, Scenario, "
                + "Instruction, and Solution authoring call; refetch the overview when it is rejected as stale. "
                + "Define a missing Feature as design-only, "
                + "then call " + name(McpToolCatalog.FEATURE_HANDOFF) + "; never invent or fulfil an evaluator. "
                + "For an unimplemented WRITE result, call " + name(McpToolCatalog.ENGINEERING_HANDOFF)
                + " and stop at the accountable engineering boundary. Propose complete business examples with "
                + name(McpToolCatalog.GOLDEN_PROPOSE) + " including the assumed fact values, dependency outcomes, "
                + "expected result, explanation class, and accountable Oracle owner; never approve them. Do not "
                + "expose internal fixture, stub, node, graph, or binding fields. Never invent runtime bindings, "
                + "approval, GREEN, attestation, or signoff "
                + "evidence. Call " + name(McpToolCatalog.READINESS)
                + " before publish and publish only when publishable=true.";
    }

    private String platformInstructions() {
        return "Operate on the platform authoring surface. Read the exact library and contract before changing "
                + "a Tool. Start DSL work with " + name(McpToolCatalog.DSL_REFERENCE)
                + " and treat its languageVersion, contracts, examples, and authoringContextFingerprint as "
                + "authoritative. Generate DSL from business intent, preview it, revise only from safe authoring "
                + "diagnostics, and gate the exact accepted source. Stop after three repair rounds or when the "
                + "same blocking fingerprint set appears twice. Never invent a binding, Oracle approval, GREEN, "
                + "attestation, or signoff. Call " + name(McpToolCatalog.READINESS)
                + " before a Tool publication attempt.";
    }

    private String operationsInstructions() {
        return "This is a read-only operations surface. Inspect current contracts, evidence, verdicts, "
                + "performance, and " + name(McpToolCatalog.READINESS)
                + ". Do not propose, execute, approve, sign, or publish changes.";
    }

    private String name(String catalogName) {
        return catalog.require(catalogName).name();
    }
}
