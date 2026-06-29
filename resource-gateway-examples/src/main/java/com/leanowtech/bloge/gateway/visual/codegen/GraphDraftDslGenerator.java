package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Lowers a visual graph draft into executable BLOGE DSL.
 */
@Service
public class GraphDraftDslGenerator {

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public GraphDraftDslGenerator(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Generates BLOGE DSL for a visual graph draft.
     *
     * @param draft graph draft
     * @return generated DSL and diagnostics
     */
    public DslGenerationResult generate(GraphDraft draft) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draft == null) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.missing", "Graph draft is required.", "/"));
            return new DslGenerationResult(false, "", diagnostics);
        }

        Map<String, GraphDraft.DraftNode> nodesById = nodesById(draft.nodes());
        StringBuilder dsl = new StringBuilder();
        dsl.append("graph ").append(draft.graphName()).append(" {\n\n");
        for (GraphDraft.DraftNode node : orderedNodes(draft)) {
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unknown",
                        "Unknown operatorRef: " + node.operatorRef(), "/nodes/" + node.id()));
                continue;
            }
            String block = nodeToDsl(node, operator.get(), nodesById, diagnostics);
            if (!block.isBlank()) {
                dsl.append(block).append("\n\n");
            }
        }
        dsl.append("}");
        return new DslGenerationResult(diagnostics.stream().noneMatch(VisualDiagnostic::error),
                dsl.toString(), diagnostics);
    }

    private String nodeToDsl(GraphDraft.DraftNode node,
                             OperatorDefinition operator,
                             Map<String, GraphDraft.DraftNode> nodesById,
                             List<VisualDiagnostic> diagnostics) {
        if ("resource-descriptor".equals(operator.source().kind())) {
            return resourceNodeToDsl(node, operator, nodesById);
        }
        if ("transform".equals(operator.lowering().mode())) {
            return loweredTransformToDsl(node, operator, nodesById);
        }
        if ("native".equals(operator.lowering().mode())
                && !List.of("httpResource", "bloge:decisionTable", "bloge:transform")
                .contains(operator.operatorRef())) {
            return nativeOperatorNodeToDsl(node, operator, nodesById);
        }
        return switch (operator.operatorRef()) {
            case "httpResource" -> httpResourceNodeToDsl(node, nodesById);
            case "bloge:decisionTable" -> decisionTableToDsl(node, nodesById);
            case "bloge:transform" -> transformToDsl(node, nodesById);
            default -> {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unsupported",
                        "Operator '%s' cannot be lowered by this example generator.".formatted(operator.operatorRef()),
                        "/nodes/" + node.id() + "/operatorRef"));
                yield "";
            }
        };
    }

    private String nativeOperatorNodeToDsl(GraphDraft.DraftNode node,
                                           OperatorDefinition operator,
                                           Map<String, GraphDraft.DraftNode> nodesById) {
        String executableOperatorRef = stringValue(operator.lowering().operatorRef()).isBlank()
                ? operator.operatorRef()
                : operator.lowering().operatorRef();
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : ").append(executableOperatorRef).append(" {\n")
                .append("    input {\n");
        node.inputs().forEach((key, binding) -> block.append("      ")
                .append(targetInputName(key, binding)).append(" = ")
                .append(bindingToExpression(binding, nodesById)).append("\n"));
        block.append("    }\n");
        appendCommonExecutionConfig(block, node.config());
        block.append("  }");
        return block.toString();
    }

    private String loweredTransformToDsl(GraphDraft.DraftNode node,
                                         OperatorDefinition operator,
                                         Map<String, GraphDraft.DraftNode> nodesById) {
        Map<String, String> inputExpressions = new LinkedHashMap<>();
        node.inputs().forEach((key, binding) -> addInputExpressionAliases(inputExpressions, key, binding,
                bindingToExpression(binding, nodesById)));

        Map<String, Object> assignments = objectMap(operator.lowering().parameters().get("assignments"));
        if (assignments.isEmpty()) {
            return transformToDsl(node, nodesById);
        }

        StringBuilder block = new StringBuilder();
        block.append("  transform ").append(node.id()).append(" {\n");
        assignments.forEach((key, value) -> block.append("    ")
                .append(key).append(" = ")
                .append(renderTemplateExpression(String.valueOf(value), inputExpressions))
                .append("\n"));
        block.append("  }");
        return block.toString();
    }

    private String resourceNodeToDsl(GraphDraft.DraftNode node,
                                     OperatorDefinition operator,
                                     Map<String, GraphDraft.DraftNode> nodesById) {
        String resourceId = stringValue(operator.lowering().parameters().get("resourceId"));
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : httpResource {\n")
                .append("    input {\n")
                .append("      resourceId = ").append(quote(resourceId)).append("\n")
                .append("      params = ").append(renderObjectBindings(node.inputs(), nodesById)).append("\n")
                .append("    }\n");
        appendCommonExecutionConfig(block, node.config());
        block.append("  }");
        return block.toString();
    }

    private String httpResourceNodeToDsl(GraphDraft.DraftNode node,
                                         Map<String, GraphDraft.DraftNode> nodesById) {
        String resourceId = stringValue(node.config().get("resourceId"));
        if (resourceId.isBlank() && node.inputs().containsKey("resourceId")) {
            resourceId = bindingToExpression(node.inputs().get("resourceId"), nodesById);
        } else {
            resourceId = quote(resourceId);
        }
        Map<String, GraphDraft.Binding> params = Map.of();
        if (node.inputs().containsKey("params") && "objectTemplate".equals(node.inputs().get("params").kind())) {
            params = node.inputs().get("params").fields();
        }
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : httpResource {\n")
                .append("    input {\n")
                .append("      resourceId = ").append(resourceId).append("\n")
                .append("      params = ").append(renderObjectBindings(params, nodesById)).append("\n")
                .append("    }\n");
        appendCommonExecutionConfig(block, node.config());
        block.append("  }");
        return block.toString();
    }

    private String decisionTableToDsl(GraphDraft.DraftNode node,
                                      Map<String, GraphDraft.DraftNode> nodesById) {
        Map<String, Object> inputConfig = objectMap(node.config().get("inputs"));
        Map<String, String> inputs = new LinkedHashMap<>();
        if (inputConfig.isEmpty()) {
            node.inputs().forEach((key, binding) -> inputs.put(targetInputName(key, binding),
                    bindingToExpression(binding, nodesById)));
        } else {
            inputConfig.forEach((key, value) -> inputs.put(key, expressionFromObject(value, nodesById)));
        }
        if (inputs.isEmpty()) {
            inputs.put("value", "ctx.value");
        }

        StringJoiner inputJoiner = new StringJoiner(",\n");
        inputs.forEach((key, expression) -> inputJoiner.add("    " + key + " = " + expression));

        String hitPolicy = stringValue(node.config().getOrDefault("hitPolicy", "unique"));
        String outputType = stringValue(node.config().getOrDefault("outputType",
                "{ decision: String, ruleId: String }"));
        List<Object> rules = objectList(node.config().get("rules"));
        if (rules.isEmpty()) {
            rules = List.of(Map.of(
                    "otherwise", true,
                    "output", Map.of("decision", "matched", "ruleId", "default")
            ));
        }

        StringBuilder block = new StringBuilder();
        block.append("  decision_table ").append(node.id()).append("(\n")
                .append(inputJoiner).append("\n")
                .append("  ) hit=").append(hitPolicy).append(" -> ").append(outputType).append(" {\n");
        for (Object rawRule : rules) {
            block.append(ruleToDsl(rawRule)).append("\n");
        }
        block.append("  }");
        return block.toString();
    }

    private String transformToDsl(GraphDraft.DraftNode node,
                                  Map<String, GraphDraft.DraftNode> nodesById) {
        Map<String, Object> assignmentConfig = objectMap(node.config().get("assignments"));
        Map<String, String> assignments = new LinkedHashMap<>();
        if (assignmentConfig.isEmpty()) {
            node.inputs().forEach((key, binding) -> assignments.put(targetInputName(key, binding),
                    bindingToExpression(binding, nodesById)));
        } else {
            assignmentConfig.forEach((key, value) -> assignments.put(key, expressionFromObject(value, nodesById)));
        }
        if (assignments.isEmpty()) {
            assignments.put("result", "{}");
        }
        StringBuilder block = new StringBuilder();
        block.append("  transform ").append(node.id()).append(" {\n");
        assignments.forEach((key, expression) -> block.append("    ")
                .append(key).append(" = ").append(expression).append("\n"));
        block.append("  }");
        return block.toString();
    }

    private static String ruleToDsl(Object rawRule) {
        Map<String, Object> rule = objectMap(rawRule);
        Map<String, Object> output = objectMap(rule.get("output"));
        if (output.isEmpty()) {
            output = new LinkedHashMap<>(rule);
            output.remove("conditions");
            output.remove("condition");
            output.remove("otherwise");
            output.remove("id");
        }
        String renderedOutput = renderLiteralMap(output);
        boolean otherwise = Boolean.TRUE.equals(rule.get("otherwise"));
        if (otherwise) {
            return "    otherwise -> " + renderedOutput;
        }
        Object rawConditions = rule.get("conditions");
        String conditions;
        if (rawConditions instanceof String conditionString) {
            conditions = conditionString;
        } else {
            Map<String, Object> conditionMap = objectMap(rawConditions);
            StringJoiner joiner = new StringJoiner(", ");
            conditionMap.forEach((key, value) -> joiner.add(key + ": " + String.valueOf(value)));
            conditions = joiner.toString();
        }
        if (conditions.isBlank()) {
            conditions = "value: value != null";
        }
        return "    rule (" + conditions + ") -> " + renderedOutput;
    }

    private static void appendCommonExecutionConfig(StringBuilder block, Map<String, Object> config) {
        Object timeout = config.get("timeout");
        if (timeout != null && !String.valueOf(timeout).isBlank()) {
            block.append("    timeout = ").append(timeout).append("\n");
        }
        Object retryAttempts = config.get("retryAttempts");
        if (retryAttempts != null) {
            block.append("    retry = { attempts: ").append(retryAttempts).append(", backoff: 200ms }\n");
        }
    }

    private static String renderObjectBindings(Map<String, GraphDraft.Binding> bindings,
                                               Map<String, GraphDraft.DraftNode> nodesById) {
        if (bindings.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        bindings.forEach((key, binding) -> joiner.add(key + ": " + bindingToExpression(binding, nodesById)));
        return joiner.toString();
    }

    private static String bindingToExpression(GraphDraft.Binding binding,
                                              Map<String, GraphDraft.DraftNode> nodesById) {
        return switch (binding.kind()) {
            case "contextPath" -> pathExpression("ctx", binding.path());
            case "nodePath" -> nodePathExpression(binding, nodesById);
            case "expression" -> binding.expr().isBlank() ? "{}" : binding.expr();
            case "objectTemplate" -> renderObjectBindings(binding.fields(), nodesById);
            default -> renderLiteral(binding.value());
        };
    }

    private static String nodePathExpression(GraphDraft.Binding binding,
                                             Map<String, GraphDraft.DraftNode> nodesById) {
        GraphDraft.DraftNode source = nodesById.get(binding.nodeId());
        String base = binding.nodeId() + ".output";
        String sourcePort = binding.sourcePort();
        if (source != null && source.operatorRef().startsWith("resource:")
                && (sourcePort.isBlank() || "payload".equals(sourcePort))) {
            base += ".payload";
        } else if (!sourcePort.isBlank() && !"output".equals(sourcePort)) {
            base += "." + sourcePort;
        }
        return pathExpression(base, binding.path());
    }

    private static String pathExpression(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        String normalized = path.startsWith(".") ? path.substring(1) : path;
        return base + "." + normalized;
    }

    private static String expressionFromObject(Object raw,
                                               Map<String, GraphDraft.DraftNode> nodesById) {
        if (raw instanceof GraphDraft.Binding binding) {
            return bindingToExpression(binding, nodesById);
        }
        if (raw instanceof Map<?, ?> rawMap && rawMap.containsKey("kind")) {
            return bindingToExpression(bindingFromMap(rawMap), nodesById);
        }
        if (raw instanceof String expression) {
            return expression;
        }
        return renderLiteral(raw);
    }

    private static String renderTemplateExpression(String template, Map<String, String> inputExpressions) {
        String expression = template;
        for (Map.Entry<String, String> entry : inputExpressions.entrySet()) {
            expression = expression
                    .replace("{{input." + entry.getKey() + "}}", entry.getValue())
                    .replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return expression;
    }

    private static void addInputExpressionAliases(Map<String, String> inputExpressions,
                                                  String inputKey,
                                                  GraphDraft.Binding binding,
                                                  String expression) {
        String targetName = targetInputName(inputKey, binding);
        inputExpressions.put(targetName, expression);
        if (!binding.targetPort().isBlank()) {
            inputExpressions.put(binding.targetPort() + "." + targetName, expression);
        }
        if (!inputKey.equals(targetName)) {
            inputExpressions.put(inputKey, expression);
        }
    }

    private static String targetInputName(String inputKey, GraphDraft.Binding binding) {
        return binding.targetPath().isBlank() ? inputKey : binding.targetPath();
    }

    private static GraphDraft.Binding bindingFromMap(Map<?, ?> rawMap) {
        Map<String, GraphDraft.Binding> fields = new LinkedHashMap<>();
        Object rawFields = rawMap.get("fields");
        if (rawFields instanceof Map<?, ?> rawFieldMap) {
            rawFieldMap.forEach((key, value) -> {
                if (value instanceof Map<?, ?> nestedMap) {
                    fields.put(String.valueOf(key), bindingFromMap(nestedMap));
                } else {
                    fields.put(String.valueOf(key), GraphDraft.Binding.constant(value));
                }
            });
        }
        return new GraphDraft.Binding(
                stringValue(rawMap.get("kind")),
                rawMap.get("value"),
                stringValue(rawMap.get("path")),
                stringValue(rawMap.get("nodeId")),
                stringValue(rawMap.get("sourcePort")),
                stringValue(rawMap.get("targetPort")),
                stringValue(rawMap.get("targetPath")),
                stringValue(rawMap.get("expr")),
                fields
        );
    }

    private static String renderLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return renderLiteralMap(map);
        }
        if (value instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            collection.forEach(item -> joiner.add(renderLiteral(item)));
            return joiner.toString();
        }
        return quote(String.valueOf(value));
    }

    private static String renderLiteralMap(Map<?, ?> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        map.forEach((key, value) -> joiner.add(key + ": " + renderLiteral(value)));
        return joiner.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, GraphDraft.DraftNode> nodesById(List<GraphDraft.DraftNode> nodes) {
        Map<String, GraphDraft.DraftNode> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        return byId;
    }

    private static List<GraphDraft.DraftNode> orderedNodes(GraphDraft draft) {
        Map<String, GraphDraft.DraftNode> nodesById = nodesById(draft.nodes());
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        draft.nodes().forEach(node -> {
            outgoing.put(node.id(), new LinkedHashSet<>());
            indegree.put(node.id(), 0);
        });
        draft.edges().forEach(edge -> {
            String source = edge.source().nodeId();
            String target = edge.target().nodeId();
            if (nodesById.containsKey(source) && nodesById.containsKey(target)
                    && outgoing.get(source).add(target)) {
                indegree.put(target, indegree.get(target) + 1);
            }
        });
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });
        List<GraphDraft.DraftNode> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String nodeId = ready.removeFirst();
            ordered.add(nodesById.get(nodeId));
            for (String target : outgoing.get(nodeId)) {
                int degree = indegree.compute(target, (ignored, current) -> current == null ? 0 : current - 1);
                if (degree == 0) {
                    ready.add(target);
                }
            }
        }
        if (ordered.size() != draft.nodes().size()) {
            return draft.nodes();
        }
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> map.put(String.valueOf(key), value));
        return map;
    }

    private static List<Object> objectList(Object raw) {
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        return new ArrayList<>(rawList);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
