package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Converts operator declarations into conservative effects and aggregates child effects for graphs.
 *
 * <p>The analyzer never treats an unrecognized label as pure or read-only. This is the central
 * defense against an imported operator library omitting or misspelling a write declaration.</p>
 */
public final class CapabilityEffectAnalyzer {
    private CapabilityEffectAnalyzer() {
    }

    /**
     * Derives one atomic operator effect.
     *
     * @param operator immutable operator definition
     * @return declared effect or a critical unknown effect
     */
    public static EffectContract fromOperator(OperatorDefinition operator) {
        java.util.Objects.requireNonNull(operator, "operator");
        OperatorDefinition.Capabilities capabilities = operator.capabilities();
        String label = normalize(capabilities.effect());
        String boundary = resourcePattern(operator);
        return switch (label) {
            case "PURE" -> externalIdentity(operator)
                    ? EffectContract.unknown("operator declares PURE while its source crosses an external boundary")
                    : EffectContract.readOnly(List.of());
            case "READ_EXTERNAL" -> EffectContract.readOnly(List.of(boundary));
            case "WRITE_EXTERNAL" -> new EffectContract("", EffectContract.Mode.EXTERNAL_MUTATION,
                    List.of(), List.of(boundary), List.of(), null, true,
                    capabilities.sideEffectProtocol().managedWrite()
                            ? EffectContract.RiskLevel.HIGH : EffectContract.RiskLevel.CRITICAL,
                    EffectContract.Derivation.DECLARED, List.of());
            case "EXTERNAL" -> EffectContract.unknown(
                    "operator effect EXTERNAL does not distinguish read from write");
            default -> EffectContract.unknown("unsupported operator effect: " + label);
        };
    }

    /**
     * Aggregates the complete set of child effects reachable from a composed graph.
     *
     * @param effects node-scoped child effects
     * @return deterministic conservative transitive summary
     */
    public static EffectContract aggregate(List<ScopedEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            throw new IllegalArgumentException("composed effect aggregation requires child effects");
        }
        LinkedHashSet<String> reads = new LinkedHashSet<>();
        LinkedHashSet<String> writes = new LinkedHashSet<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        LinkedHashSet<MirrorArtifactRef> compensations = new LinkedHashSet<>();
        List<EffectContract.ConditionalEffect> conditional = new ArrayList<>();
        EffectContract.RiskLevel risk = EffectContract.RiskLevel.LOW;
        boolean externalMutation = false;
        boolean virtualMutation = false;
        boolean approval = false;
        for (ScopedEffect scoped : effects.stream()
                .sorted(java.util.Comparator.comparing(ScopedEffect::nodeId)
                        .thenComparing(ScopedEffect::condition)).toList()) {
            EffectContract effect = scoped.effect();
            reads.addAll(effect.readSet());
            writes.addAll(effect.writeSet());
            risk = EffectContract.RiskLevel.max(risk, effect.riskLevel());
            approval |= effect.requiresApproval();
            if (effect.compensationRef() != null) {
                compensations.add(effect.compensationRef());
            }
            externalMutation |= effect.mode() == EffectContract.Mode.EXTERNAL_MUTATION
                    || effect.mode() == EffectContract.Mode.MIXED;
            virtualMutation |= effect.mode() == EffectContract.Mode.VIRTUAL_MUTATION
                    || effect.mode() == EffectContract.Mode.MIXED;
            if (effect.mode() == EffectContract.Mode.UNKNOWN) {
                effect.unresolvedReasons().forEach(reason -> unresolved.add(scoped.nodeId() + ": " + reason));
            }
            if (!scoped.condition().isBlank()) {
                conditional.add(new EffectContract.ConditionalEffect(scoped.condition(), effect.mode(),
                        effect.readSet(), effect.writeSet()));
            }
        }
        if (externalMutation && virtualMutation) {
            unresolved.add("graph combines external and virtual mutations without an aggregate effect model");
        }
        if (compensations.size() > 1) {
            unresolved.add("graph declares multiple compensation capabilities without an aggregate compensation");
        }
        EffectContract.Mode mode;
        if (!unresolved.isEmpty()) {
            mode = EffectContract.Mode.UNKNOWN;
            risk = EffectContract.RiskLevel.CRITICAL;
            approval = true;
        } else if (externalMutation && (virtualMutation || !reads.isEmpty())) {
            mode = EffectContract.Mode.MIXED;
        } else if (externalMutation) {
            mode = EffectContract.Mode.EXTERNAL_MUTATION;
        } else if (virtualMutation && !reads.isEmpty()) {
            mode = EffectContract.Mode.MIXED;
        } else if (virtualMutation) {
            mode = EffectContract.Mode.VIRTUAL_MUTATION;
        } else {
            mode = EffectContract.Mode.READ_ONLY;
        }
        MirrorArtifactRef compensation = compensations.size() == 1 ? compensations.getFirst() : null;
        return new EffectContract("", mode, List.copyOf(reads), List.copyOf(writes), conditional,
                compensation, approval, risk, EffectContract.Derivation.TRANSITIVE_SUMMARY,
                List.copyOf(unresolved));
    }

    /**
     * One node effect and the optional route under which it is reachable.
     *
     * @param nodeId stable parent node identifier
     * @param condition stable route identifier; blank when unconditional
     * @param effect child effect
     */
    public record ScopedEffect(String nodeId, String condition, EffectContract effect) {
        /** Validates scoped aggregation input. */
        public ScopedEffect {
            nodeId = required(nodeId, "nodeId");
            condition = condition == null ? "" : condition.trim();
            effect = java.util.Objects.requireNonNull(effect, "effect");
        }
    }

    private static String resourcePattern(OperatorDefinition operator) {
        String resourceId = operator.source().resourceId();
        return "resource:" + (resourceId == null || resourceId.isBlank()
                ? operator.operatorRef() : resourceId.trim());
    }

    private static boolean externalIdentity(OperatorDefinition operator) {
        String kind = normalize(operator.source().kind());
        return !operator.source().resourceId().isBlank()
                || List.of("REMOTE_WORKER", "AI_TOOL", "EVENT_SOURCE", "MESSAGE_HANDLER", "WEBHOOK",
                "GRAPH", "COMPOSED_GRAPH", "NESTED_GRAPH").contains(kind);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
