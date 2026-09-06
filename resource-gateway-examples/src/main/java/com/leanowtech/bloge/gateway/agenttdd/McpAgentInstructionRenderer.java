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
                + overview + " to reuse governed business building blocks. " + recallInstructions()
                + "For every new entity, derive its businessName, description, aliases, whenToUse, "
                + "and whenNotToUse from the agreed business phrases and include the complete display "
                + "contract from the server template. "
                + "Define a missing Feature as "
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
                + "details. Start a genuinely new business goal with " + name(McpToolCatalog.JOURNEY_START)
                + ". When the user refers to an existing, previous, or just-created capability, or asks to "
                + "continue, revise, or add examples, do not start another journey first. Rediscover the "
                + "current entity and its owning journey, call " + name(McpToolCatalog.JOURNEY_NEXT)
                + ", and reuse that journey when it allows the requested action. Start a replacement journey "
                + "only when no applicable active journey exists, and bind its targetRef to the exact entity "
                + "already read. Follow only the allowedNextTools returned by "
                + name(McpToolCatalog.JOURNEY_NEXT)
                + ". Use " + name(McpToolCatalog.LIBRARY_OVERVIEW) + " and "
                + name(McpToolCatalog.CAPABILITY_SEARCH) + " to reuse governed business capabilities. "
                + recallInstructions() + "For every new entity, derive its businessName, description, "
                + "aliases, whenToUse, and whenNotToUse from the agreed business phrases and include the "
                + "complete display contract from the server template. Carry the returned "
                + "authoringPatternsFingerprint unchanged in every journey-scoped Feature, Scenario, "
                + "Instruction, and Solution authoring call; refetch the overview when it is rejected as stale. "
                + "Define a missing Feature as design-only, "
                + "then call " + name(McpToolCatalog.FEATURE_HANDOFF) + "; never invent or fulfil an evaluator. "
                + "For an unimplemented WRITE result, call " + name(McpToolCatalog.ENGINEERING_HANDOFF)
                + " and stop at the accountable engineering boundary. Propose complete business examples with "
                + name(McpToolCatalog.GOLDEN_PROPOSE) + " including the assumed fact values, dependency outcomes, "
                + "expected result, explanation class, and accountable Oracle owner. Before proposing an example, "
                + "read every referenced Feature or Instruction with " + name(McpToolCatalog.ENTITY_GET)
                + " and copy its display.businessName verbatim into factName or capabilityName. Never paraphrase "
                + "that name or append words such as service; if there is no unique exact entity, ask one business "
                + "question and stop. Include a dependency value only for RETURNS; omit value for UNAVAILABLE, "
                + "SUCCEEDS_WITHOUT_EFFECT, FAILS_WITHOUT_EFFECT, and MUST_NOT_BE_USED because the server derives "
                + "those controlled results. Never approve proposed examples. Do not "
                + "expose internal fixture, stub, node, graph, or binding fields. Never invent runtime bindings, "
                + "approval, GREEN, attestation, or signoff "
                + "evidence. Call " + name(McpToolCatalog.READINESS)
                + " before publish and publish only when publishable=true.";
    }

    /**
     * Builds the mandatory business recall sequence from canonical catalog names.
     *
     * <p>Business users never see protocol fields. Codex infers the entity family, reads candidate
     * contracts and constructs the complete second-pass query itself.</p>
     */
    private String recallInstructions() {
        String search = name(McpToolCatalog.CAPABILITY_SEARCH);
        String get = name(McpToolCatalog.ENTITY_GET);
        return "For every reuse decision, perform two-pass recall. First infer one business entity family "
                + "from the user's intent: FEATURE for a fact, SCENARIO for a decision, INSTRUCTION for an "
                + "outcome or action, or SOLUTION for an end-to-end workflow. When that family is known, call "
                + search + " with the user's words and that one assetKinds value. Treat its rank as discovery, "
                + "not proof. Read each relevant candidate with " + get
                + ", compare its business meaning with the user's stated intent, and call " + search
                + " again using the candidate's complete business definition. Reuse only when this second "
                + "call returns one unique EXACT candidate with reuseAllowed=true. If candidates differ on "
                + "a business dimension the user has not settled, or if multiple EXACT candidates remain, "
                + "ask exactly one plain-language business question and stop. Construct schemaVersion, "
                + "semanticKey, assetKinds, and all other protocol fields yourself; never ask the business "
                + "user to provide or understand them. In a new session, use the owning journey coordinates "
                + "returned by " + get + " and call " + name(McpToolCatalog.JOURNEY_NEXT)
                + " for the primary active journey before reporting the current workflow stage. ";
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
