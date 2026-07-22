package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves structural selectors and freezes runtime candidate precedence. */
public class SelectorResolver {

    /**
     * Resolves every rule and rejects zero-match or same-precedence ambiguous declarations.
     *
     * @param inventory frozen root, nested, and compensation invocation inventory
     * @param rules frozen fixture rules
     * @return controls keyed by governance invocation-site id
     */
    public Map<String, CompiledExecutionControl.ResolvedControl> resolve(
            InvocationInventory inventory,
            List<FixtureRule> rules) {
        return resolve(inventory, rules, false);
    }

    /**
     * Resolves mirror candidates by fixed source priority before selector specificity.
     *
     * <p>Overlap between OWNER_SPECIFIED and GOVERNED_REPLAY is intentional fallback, while
     * ambiguity within the same source and selector precedence remains fail-closed.</p>
     *
     * @param inventory exact frozen invocation inventory
     * @param rules frozen owner and governed replay rules
     * @return mirror controls carrying exact resolver order
     */
    public Map<String, CompiledExecutionControl.ResolvedControl> resolveMirror(
            InvocationInventory inventory,
            List<FixtureRule> rules) {
        return resolve(inventory, rules, true);
    }

    private Map<String, CompiledExecutionControl.ResolvedControl> resolve(
            InvocationInventory inventory,
            List<FixtureRule> rules,
            boolean mirror) {
        Map<String, List<ScoredRule>> bySite = new LinkedHashMap<>();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            FixtureRule rule = rules.get(ruleIndex);
            int declarationIndex = ruleIndex;
            List<InvocationInventory.Entry> matched = inventory.entries().stream()
                    .filter(entry -> matches(entry, rule.selector()))
                    .toList();
            if (matched.isEmpty()) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_ZERO_MATCH", List.of(
                        "Fixture rule '" + rule.ruleId() + "' did not match any invocation site."));
            }
            int score = precedence(rule.selector());
            matched.forEach(entry -> bySite.computeIfAbsent(
                            entry.site().invocationSiteId(), ignored -> new ArrayList<>())
                    .add(new ScoredRule(rule, score, declarationIndex)));
        }

        Map<String, CompiledExecutionControl.ResolvedControl> resolved = new LinkedHashMap<>();
        bySite.forEach((siteId, candidates) -> {
            Map<PrecedenceGroup, List<FixtureRule>> byPrecedence = new LinkedHashMap<>();
            java.util.Comparator<ScoredRule> ordering = mirror
                    ? java.util.Comparator.comparingInt(ScoredRule::sourceRank)
                    .thenComparing(java.util.Comparator.comparingInt(ScoredRule::score).reversed())
                    .thenComparingInt(ScoredRule::declarationIndex)
                    : java.util.Comparator.comparingInt(ScoredRule::score).reversed()
                    .thenComparingInt(ScoredRule::declarationIndex);
            candidates.stream().sorted(ordering)
                    .forEach(candidate -> byPrecedence
                            .computeIfAbsent(new PrecedenceGroup(
                                    mirror ? candidate.sourceRank() : 0, candidate.score()),
                                    ignored -> new ArrayList<>())
                            .add(candidate.rule()));
            for (List<FixtureRule> peers : byPrecedence.values()) {
                if (peers.size() > 1 && !pairwiseDisjoint(peers)) {
                    throw new ControlPlanRejectedException("CONTROL_PLAN_AMBIGUOUS", List.of(
                            "Invocation site '" + siteId + "' has same-precedence fixture rules: "
                                    + peers.stream().map(FixtureRule::ruleId).toList()));
                }
            }
            InvocationSite site = inventory.byInvocationSiteId().get(siteId).site();
            List<FixtureRule> ordered = byPrecedence.values().stream().flatMap(List::stream).toList();
            resolved.put(siteId, mirror
                    ? CompiledExecutionControl.ResolvedControl.mirror(site, ordered, false)
                    : new CompiledExecutionControl.ResolvedControl(site, ordered, false));
        });
        return resolved;
    }

    private static boolean matches(InvocationInventory.Entry entry, FixtureRule.Selector selector) {
        NodeSpec node = entry.node();
        InvocationSite site = entry.site();
        if (!selector.graphPath().isBlank() && !site.graphPath().equals(selector.graphPath())) {
            return false;
        }
        if (!selector.nodeId().isBlank() && !selector.nodeId().equals(node.id())) {
            return false;
        }
        if (!selector.operatorRef().isBlank() && !selector.operatorRef().equals(node.operatorRef())) {
            return false;
        }
        if (!selector.resourceRef().isBlank() && !"httpResource".equals(node.operatorRef())) {
            return false;
        }
        if (!matchesKind(site.invocationKind(), selector.invocationKind())) {
            return false;
        }
        return requiredLabels(node, selector.capabilities()) && requiredLabels(node, selector.tags());
    }

    private static boolean matchesKind(InvocationSite.InvocationKind actual,
                                       InvocationSite.InvocationKind requested) {
        return switch (requested) {
            case COMPENSATION -> actual == InvocationSite.InvocationKind.COMPENSATION;
            case RESOURCE -> actual == InvocationSite.InvocationKind.RESOURCE;
            case FUNCTION -> actual == InvocationSite.InvocationKind.FUNCTION;
            case SUBGRAPH -> actual == InvocationSite.InvocationKind.SUBGRAPH;
            case PRIMARY -> actual == InvocationSite.InvocationKind.PRIMARY
                    || actual == InvocationSite.InvocationKind.RESOURCE
                    || actual == InvocationSite.InvocationKind.SUBGRAPH;
        };
    }

    private static boolean requiredLabels(NodeSpec node, List<String> required) {
        if (required.isEmpty()) {
            return true;
        }
        String labels = node.metadata().attributes().getOrDefault("capabilities", "") + ","
                + node.metadata().attributes().getOrDefault("tags", "");
        List<String> actual = java.util.Arrays.stream(labels.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        return actual.containsAll(required);
    }

    /**
     * Computes the stable precedence shared by compile-time ordering and runtime fallback.
     *
     * @param selector immutable fixture selector
     * @return larger value for a more constrained selector
     */
    public static int precedence(FixtureRule.Selector selector) {
        int score = 0;
        if (!selector.graphPath().isBlank()) score += 100;
        if (!selector.nodeId().isBlank()) score += 100;
        if (!selector.operatorRef().isBlank()) score += 50;
        if (!selector.resourceRef().isBlank()) score += 50;
        if (!selector.functionRef().isBlank()) score += 50;
        if (!selector.capabilities().isEmpty() || !selector.tags().isEmpty()) score += 10;
        if (!selector.attempts().isEmpty()) score += 30;
        if (!selector.occurrences().isEmpty()) score += 30;
        if (!selector.correlationKey().isBlank()) score += 20;
        FixtureRule.Match match = selector.match();
        if (match.canonicalInput() != null) score += 40;
        score += Math.min(20, match.pathEquals().size() * 4);
        score += Math.min(10, (match.pathsExist().size() + match.pathsAbsent().size()) * 2);
        if (!match.schema().isEmpty()) score += 10;
        if (!match.correlationKey().isBlank()) score += 10;
        score += Math.min(10, match.boundedRegex().size() * 2);
        return score;
    }

    private static boolean pairwiseDisjoint(List<FixtureRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                if (!provablyDisjoint(rules.get(i), rules.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean provablyDisjoint(FixtureRule left, FixtureRule right) {
        if (disjointCoordinates(left.selector().attempts(), right.selector().attempts())
                || disjointCoordinates(left.selector().occurrences(), right.selector().occurrences())) {
            return true;
        }
        String leftCorrelation = left.selector().correlationKey();
        String rightCorrelation = right.selector().correlationKey();
        if (!leftCorrelation.isBlank() && !rightCorrelation.isBlank()
                && !leftCorrelation.equals(rightCorrelation)) {
            return true;
        }
        String leftResource = left.selector().resourceRef();
        String rightResource = right.selector().resourceRef();
        if (!leftResource.isBlank() && !rightResource.isBlank()
                && !leftResource.equals(rightResource)) {
            return true;
        }
        for (Map.Entry<String, Object> entry : left.selector().match().pathEquals().entrySet()) {
            if (right.selector().match().pathEquals().containsKey(entry.getKey())
                    && !Objects.equals(entry.getValue(),
                    right.selector().match().pathEquals().get(entry.getKey()))) {
                return true;
            }
        }
        Object leftCanonical = left.selector().match().canonicalInput();
        Object rightCanonical = right.selector().match().canonicalInput();
        if (leftCanonical != null && rightCanonical != null
                && !Objects.equals(leftCanonical, rightCanonical)) {
            return true;
        }
        String leftMatchCorrelation = left.selector().match().correlationKey();
        String rightMatchCorrelation = right.selector().match().correlationKey();
        if (!leftMatchCorrelation.isBlank() && !rightMatchCorrelation.isBlank()
                && !leftMatchCorrelation.equals(rightMatchCorrelation)) {
            return true;
        }
        return false;
    }

    private static boolean disjointCoordinates(List<Integer> left, List<Integer> right) {
        return !left.isEmpty() && !right.isEmpty()
                && left.stream().noneMatch(right::contains);
    }

    private record ScoredRule(FixtureRule rule, int score, int declarationIndex) {
        private int sourceRank() {
            return rule.behavior().kind() == FixtureRule.BehaviorKind.REPLAY
                    ? com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan.MirrorSource
                    .GOVERNED_REPLAY.ordinal()
                    : com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan.MirrorSource
                    .OWNER_SPECIFIED.ordinal();
        }
    }

    private record PrecedenceGroup(int sourceRank, int selectorScore) {
    }
}
