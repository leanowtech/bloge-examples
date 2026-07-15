package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the v1 static selector subset against a frozen recursive invocation inventory. */
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
        Map<String, List<ScoredRule>> bySite = new LinkedHashMap<>();
        for (FixtureRule rule : rules) {
            List<InvocationInventory.Entry> matched = inventory.entries().stream()
                    .filter(entry -> matches(entry, rule.selector()))
                    .toList();
            if (matched.isEmpty()) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_ZERO_MATCH", List.of(
                        "Fixture rule '" + rule.ruleId() + "' did not match any invocation site."));
            }
            int score = specificity(rule.selector());
            matched.forEach(entry -> bySite.computeIfAbsent(
                            entry.site().invocationSiteId(), ignored -> new ArrayList<>())
                    .add(new ScoredRule(rule, score)));
        }

        Map<String, CompiledExecutionControl.ResolvedControl> resolved = new LinkedHashMap<>();
        bySite.forEach((siteId, candidates) -> {
            int max = candidates.stream().mapToInt(ScoredRule::score).max().orElse(0);
            List<FixtureRule> winners = candidates.stream()
                    .filter(candidate -> candidate.score() == max)
                    .map(ScoredRule::rule)
                    .toList();
            if (winners.size() > 1 && !pairwiseDisjoint(winners)) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_AMBIGUOUS", List.of(
                        "Invocation site '" + siteId + "' has same-precedence fixture rules: "
                                + winners.stream().map(FixtureRule::ruleId).toList()));
            }
            InvocationSite site = inventory.byInvocationSiteId().get(siteId).site();
            resolved.put(siteId, new CompiledExecutionControl.ResolvedControl(site, winners, false));
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

    private static int specificity(FixtureRule.Selector selector) {
        int score = 0;
        if (!selector.graphPath().isBlank()) score += 100;
        if (!selector.nodeId().isBlank()) score += 100;
        if (!selector.operatorRef().isBlank()) score += 50;
        if (!selector.resourceRef().isBlank()) score += 50;
        if (!selector.capabilities().isEmpty() || !selector.tags().isEmpty()) score += 10;
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
        return false;
    }

    private record ScoredRule(FixtureRule rule, int score) {
    }
}
