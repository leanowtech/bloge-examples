package com.leanowtech.bloge.gateway.visual.draft;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts implicit data dependencies from visual draft bindings.
 */
public final class GraphDraftDependencies {

    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String PATH_PATTERN = IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*";
    private static final Pattern NODE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(" + IDENTIFIER_PATTERN + ")\\.output(?:\\.(" + PATH_PATTERN + "))?"
                    + "(?![A-Za-z0-9_])");

    private GraphDraftDependencies() {
    }

    /**
     * Returns source node ids referenced by this node's input bindings.
     *
     * @param node draft node
     * @return referenced source node ids in encounter order
     */
    public static Set<String> nodeDependencies(GraphDraft.DraftNode node) {
        Set<String> dependencies = new LinkedHashSet<>();
        node.inputs().values().forEach(binding -> collectBindingDependencies(binding, dependencies));
        collectConfigDependencies(node.config(), dependencies);
        return dependencies;
    }

    private static void collectBindingDependencies(GraphDraft.Binding binding, Set<String> dependencies) {
        if ("nodePath".equals(binding.kind())) {
            if (!binding.nodeId().isBlank()) {
                dependencies.add(binding.nodeId());
            }
            return;
        }
        if ("objectTemplate".equals(binding.kind())) {
            binding.fields().values().forEach(nested -> collectBindingDependencies(nested, dependencies));
            return;
        }
        if ("expression".equals(binding.kind())) {
            Matcher matcher = NODE_REFERENCE.matcher(withoutQuotedStrings(binding.expr()));
            while (matcher.find()) {
                dependencies.add(matcher.group(1));
            }
        }
    }

    private static void collectConfigDependencies(Object value, Set<String> dependencies) {
        if (value instanceof String expression) {
            collectExpressionDependencies(expression, dependencies);
            return;
        }
        if (value instanceof GraphDraft.Binding binding) {
            collectBindingDependencies(binding, dependencies);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if ("nodePath".equals(kind)) {
                Object nodeId = map.get("nodeId");
                if (nodeId != null && !String.valueOf(nodeId).isBlank()) {
                    dependencies.add(String.valueOf(nodeId));
                }
                return;
            }
            if ("expression".equals(kind)) {
                Object expr = map.get("expr");
                collectExpressionDependencies(expr == null ? "" : String.valueOf(expr), dependencies);
                return;
            }
            if ("objectTemplate".equals(kind) && map.get("fields") instanceof Map<?, ?> fields) {
                fields.values().forEach(nested -> collectConfigDependencies(nested, dependencies));
                return;
            }
            map.values().forEach(nested -> collectConfigDependencies(nested, dependencies));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectConfigDependencies(item, dependencies));
        }
    }

    private static void collectExpressionDependencies(String expression, Set<String> dependencies) {
        Matcher matcher = NODE_REFERENCE.matcher(withoutQuotedStrings(expression));
        while (matcher.find()) {
            dependencies.add(matcher.group(1));
        }
    }

    private static String withoutQuotedStrings(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        boolean escaped = false;
        char quote = '\0';
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (quoted) {
                result.append(' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
            } else if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
