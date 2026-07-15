package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the v1 static selector subset against a frozen root-graph inventory. */
public class SelectorResolver {

    /**
     * Resolves every rule and rejects zero-match or same-precedence ambiguous declarations.
     *
     * @param graph frozen graph artifact
     * @param artifactFingerprint graph artifact fingerprint
     * @param bindingFingerprints per-node runtime binding fingerprints
     * @param rules frozen fixture rules
     * @return controls keyed by node id
     */
    public Map<String, CompiledExecutionControl.ResolvedControl> resolve(
            Graph graph,
            String artifactFingerprint,
            Map<String, String> bindingFingerprints,
            List<FixtureRule> rules) {
        Map<String, List<ScoredRule>> byNode = new LinkedHashMap<>();
        for (FixtureRule rule : rules) {
            List<NodeSpec> matched = graph.nodes().values().stream()
                    .filter(node -> matches(node, rule.selector()))
                    .toList();
            if (matched.isEmpty()) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_ZERO_MATCH", List.of(
                        "Fixture rule '" + rule.ruleId() + "' did not match any invocation site."));
            }
            int score = specificity(rule.selector());
            matched.forEach(node -> byNode.computeIfAbsent(node.id(), ignored -> new ArrayList<>())
                    .add(new ScoredRule(rule, score)));
        }

        Map<String, CompiledExecutionControl.ResolvedControl> resolved = new LinkedHashMap<>();
        byNode.forEach((nodeId, candidates) -> {
            int max = candidates.stream().mapToInt(ScoredRule::score).max().orElse(0);
            List<FixtureRule> winners = candidates.stream()
                    .filter(candidate -> candidate.score() == max)
                    .map(ScoredRule::rule)
                    .toList();
            if (winners.size() > 1 && !pairwiseDisjoint(winners)) {
                throw new ControlPlanRejectedException("CONTROL_PLAN_AMBIGUOUS", List.of(
                        "Invocation site '/root/" + nodeId + "' has same-precedence fixture rules: "
                                + winners.stream().map(FixtureRule::ruleId).toList()));
            }
            NodeSpec node = graph.nodes().get(nodeId);
            InvocationSite site = site(node, artifactFingerprint, bindingFingerprints.get(nodeId));
            resolved.put(nodeId, new CompiledExecutionControl.ResolvedControl(site, winners, false));
        });
        return resolved;
    }

    /** Creates the stable root-graph invocation site for one node. */
    public InvocationSite site(NodeSpec node, String artifactFingerprint, String bindingFingerprint) {
        boolean resource = "httpResource".equals(node.operatorRef());
        return new InvocationSite(InvocationSite.SCHEMA_VERSION, artifactFingerprint, "/root",
                node.id(), node.operatorRef(), "", "", bindingFingerprint,
                resource ? InvocationSite.InvocationKind.RESOURCE : InvocationSite.InvocationKind.PRIMARY,
                null, "", null);
    }

    private static boolean matches(NodeSpec node, FixtureRule.Selector selector) {
        if (!selector.graphPath().isBlank() && !"/root".equals(selector.graphPath())) {
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
        if (selector.invocationKind() == InvocationSite.InvocationKind.RESOURCE
                && !"httpResource".equals(node.operatorRef())) {
            return false;
        }
        return requiredLabels(node, selector.capabilities()) && requiredLabels(node, selector.tags());
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
