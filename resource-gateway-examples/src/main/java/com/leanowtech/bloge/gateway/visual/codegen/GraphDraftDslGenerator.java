package com.leanowtech.bloge.gateway.visual.codegen;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencies;

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
import java.util.regex.Pattern;

/**
 * Lowers a visual graph draft into executable BLOGE DSL.
 */
@Service
public class GraphDraftDslGenerator {

    private static final String DSL_IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String ARRAY_INDEX_PATTERN = "\\d+";
    private static final String TEMPLATE_PATH_SEGMENT_PATTERN = "(?:" + DSL_IDENTIFIER_PATTERN + "|"
            + ARRAY_INDEX_PATTERN + ")";
    private static final String TEMPLATE_PATH_PATTERN = TEMPLATE_PATH_SEGMENT_PATTERN
            + "(?:\\." + TEMPLATE_PATH_SEGMENT_PATTERN + ")*";
    private static final Set<String> EXECUTION_CONFIG_KEYS = Set.of("timeout", "retryAttempts");
    private static final Set<String> RESERVED_DSL_FIELD_NAMES = Set.of(
            "graph", "node", "branch", "decision_table", "on", "input", "depends_on",
            "timeout", "retry", "fallback", "execution_mode", "worker_topic", "compensate",
            "saga", "true", "false", "schema", "output", "otherwise", "when", "transform",
            "foreach", "sequential", "in", "loop", "parallel", "until", "carry", "wait",
            "after", "await", "event", "where", "mode", "stream", "streaming", "buffer",
            "let", "import", "as", "script", "exit", "exhausted"
    );
    private static final Pattern DSL_IDENTIFIER = Pattern.compile(DSL_IDENTIFIER_PATTERN);
    private static final Pattern ARRAY_INDEX_SEGMENT = Pattern.compile(ARRAY_INDEX_PATTERN);
    private static final Pattern UNQUOTED_DSL_OPERATOR_REF = Pattern.compile(
            DSL_IDENTIFIER_PATTERN + "(?:\\." + DSL_IDENTIFIER_PATTERN + ")*");
    private static final Pattern TEMPLATE_REFERENCE = Pattern.compile("\\{\\{\\s*((?:input\\.)?"
            + TEMPLATE_PATH_PATTERN + ")\\s*}}");

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
        Map<String, List<String>> dependencyEdges = explicitDependencyEdges(draft);
        Map<String, List<GraphDraft.DraftEdge>> routeEdges = routeEdgesBySource(draft);
        StringBuilder dsl = new StringBuilder();
        if (!isDslFieldName(draft.graphName())) {
            diagnostics.add(VisualDiagnostic.error("visual.codegen.graphName.invalid",
                    "Graph name '%s' cannot be rendered as a BLOGE DSL identifier.".formatted(draft.graphName()),
                    "/graphName"));
        }
        dsl.append("graph ").append(draft.graphName()).append(" {\n\n");
        for (GraphDraft.DraftNode node : orderedNodes(draft)) {
            if (!isDslFieldName(node.id())) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.nodeId.invalid",
                        "Node id '%s' cannot be rendered as a BLOGE DSL identifier.".formatted(node.id()),
                        "/nodes/" + node.id() + "/id"));
            }
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unknown",
                        "Unknown operatorRef: " + node.operatorRef(), "/nodes/" + node.id()));
                continue;
            }
            if ("design".equals(operator.get().lowering().mode())) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.designOnlyOperator",
                        "Operator '%s' on node '%s' is schema-only (lowering.mode=design); it can be authored and validated, but cannot be lowered to executable BLOGE DSL until a runtime lowering is bound."
                                .formatted(operator.get().operatorRef(), node.id()),
                        "/nodes/" + node.id() + "/operatorRef"));
                continue;
            }
            String block = nodeToDsl(node, operator.get(), nodesById,
                    dependencyEdges.getOrDefault(node.id(), List.of()),
                    routeEdges.getOrDefault(node.id(), List.of()),
                    diagnostics);
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
                             List<String> dependencyEdges,
                             List<GraphDraft.DraftEdge> routeEdges,
                             List<VisualDiagnostic> diagnostics) {
        if ("resource-descriptor".equals(operator.source().kind())) {
            return resourceNodeToDsl(node, operator, nodesById, dependencyEdges, diagnostics);
        }
        if ("branch".equals(operator.lowering().mode())) {
            rejectUnsupportedExplicitDependencies(node, operator, dependencyEdges, diagnostics);
            return branchToDsl(node, operator, nodesById, routeEdges, diagnostics);
        }
        if ("transform".equals(operator.lowering().mode())) {
            rejectUnsupportedExplicitDependencies(node, operator, dependencyEdges, diagnostics);
            return loweredTransformToDsl(node, operator, nodesById, diagnostics);
        }
        if ("native".equals(operator.lowering().mode())
                && !List.of("httpResource", "bloge:decisionTable", "bloge:transform")
                .contains(operator.operatorRef())) {
            return nativeOperatorNodeToDsl(node, operator, nodesById, dependencyEdges, diagnostics);
        }
        return switch (operator.operatorRef()) {
            case "httpResource" -> httpResourceNodeToDsl(node, nodesById, dependencyEdges, diagnostics);
            case "bloge:decisionTable" -> {
                rejectUnsupportedExplicitDependencies(node, operator, dependencyEdges, diagnostics);
                yield decisionTableToDsl(node, nodesById, diagnostics);
            }
            case "bloge:transform" -> {
                rejectUnsupportedExplicitDependencies(node, operator, dependencyEdges, diagnostics);
                yield transformToDsl(node, nodesById, diagnostics);
            }
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
                                           Map<String, GraphDraft.DraftNode> nodesById,
                                           List<String> dependencyEdges,
                                           List<VisualDiagnostic> diagnostics) {
        String executableOperatorRef = stringValue(operator.lowering().operatorRef()).isBlank()
                ? operator.operatorRef()
                : operator.lowering().operatorRef();
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : ").append(renderOperatorRef(executableOperatorRef))
                .append(" {\n");
        appendDependsOn(block, dependencyEdges);
        block.append("    input {\n");
        Map<String, String> inputAssignments = renderNativeInputAssignments(node, nodesById, diagnostics);
        inputAssignments.forEach((key, expression) -> block.append("      ").append(key).append(" = ")
                        .append(expression).append("\n"));
        Map<String, Object> config = businessConfig(node.config());
        if ("visual-publication".equals(operator.source().kind())) {
            config = new LinkedHashMap<>(config);
            config.remove("outputNode");
            config.put("publicationId", stringValue(operator.lowering().parameters().get("publicationId")));
        }
        if (!config.isEmpty()) {
            if (inputAssignments.containsKey("config")) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.configInput.conflict",
                        "Native operator node '%s' cannot lower both an input path named 'config' and configSchema values."
                                .formatted(node.id()),
                        "/nodes/" + node.id() + "/config"));
            } else {
                block.append("      config = ")
                        .append(renderConfigObjectLiteral(config, nodesById,
                                "/nodes/" + node.id() + "/config", diagnostics))
                        .append("\n");
            }
        }
        block.append("    }\n");
        appendCommonExecutionConfig(block, node.config(), nodesById, "/nodes/" + node.id() + "/config",
                diagnostics);
        block.append("  }");
        return block.toString();
    }

    private String loweredTransformToDsl(GraphDraft.DraftNode node,
                                         OperatorDefinition operator,
                                         Map<String, GraphDraft.DraftNode> nodesById,
                                         List<VisualDiagnostic> diagnostics) {
        Map<String, String> inputExpressions = new LinkedHashMap<>();
        node.inputs().forEach((key, binding) -> addInputExpressionAliases(inputExpressions, key, binding,
                bindingToExpression(binding, nodesById, "/nodes/" + node.id() + "/inputs/" + key, diagnostics)));

        Map<String, Object> assignments = objectMap(operator.lowering().parameters().get("assignments"));
        if (assignments.isEmpty()) {
            return transformToDsl(node, nodesById, diagnostics);
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
                                     Map<String, GraphDraft.DraftNode> nodesById,
                                     List<String> dependencyEdges,
                                     List<VisualDiagnostic> diagnostics) {
        String resourceId = stringValue(operator.lowering().parameters().get("resourceId"));
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : httpResource {\n");
        appendDependsOn(block, dependencyEdges);
        block.append("    input {\n")
                .append("      resourceId = ").append(quote(resourceId)).append("\n")
                .append("      params = ")
                .append(renderObjectBindings(node.inputs(), nodesById,
                        "/nodes/" + node.id() + "/inputs", diagnostics))
                .append("\n")
                .append("    }\n");
        appendCommonExecutionConfig(block, node.config(), nodesById, "/nodes/" + node.id() + "/config",
                diagnostics);
        block.append("  }");
        return block.toString();
    }

    private String httpResourceNodeToDsl(GraphDraft.DraftNode node,
                                         Map<String, GraphDraft.DraftNode> nodesById,
                                         List<String> dependencyEdges,
                                         List<VisualDiagnostic> diagnostics) {
        String resourceId = stringValue(node.config().get("resourceId"));
        if (resourceId.isBlank() && node.inputs().containsKey("resourceId")) {
            resourceId = bindingToExpression(node.inputs().get("resourceId"), nodesById,
                    "/nodes/" + node.id() + "/inputs/resourceId", diagnostics);
        } else {
            resourceId = quote(resourceId);
        }
        Map<String, GraphDraft.Binding> params = Map.of();
        if (node.inputs().containsKey("params") && "objectTemplate".equals(node.inputs().get("params").kind())) {
            params = node.inputs().get("params").fields();
        }
        StringBuilder block = new StringBuilder();
        block.append("  node ").append(node.id()).append(" : httpResource {\n");
        appendDependsOn(block, dependencyEdges);
        block.append("    input {\n")
                .append("      resourceId = ").append(resourceId).append("\n")
                .append("      params = ")
                .append(renderObjectBindings(params, nodesById,
                        "/nodes/" + node.id() + "/inputs/params/fields", diagnostics))
                .append("\n")
                .append("    }\n");
        appendCommonExecutionConfig(block, node.config(), nodesById, "/nodes/" + node.id() + "/config",
                diagnostics);
        block.append("  }");
        return block.toString();
    }

    private static void appendDependsOn(StringBuilder block, List<String> dependencies) {
        if (dependencies.isEmpty()) {
            return;
        }
        block.append("    depends_on = [")
                .append(String.join(", ", dependencies))
                .append("]\n");
    }

    private static void rejectUnsupportedExplicitDependencies(GraphDraft.DraftNode node,
                                                             OperatorDefinition operator,
                                                             List<String> dependencies,
                                                             List<VisualDiagnostic> diagnostics) {
        if (dependencies.isEmpty()) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.codegen.dependencyTargetUnsupported",
                "Node '%s' using operator '%s' has explicit dependency edges, but this generated DSL block cannot declare depends_on."
                        .formatted(node.id(), operator.operatorRef()),
                "/nodes/" + node.id()));
    }

    private String decisionTableToDsl(GraphDraft.DraftNode node,
                                      Map<String, GraphDraft.DraftNode> nodesById,
                                      List<VisualDiagnostic> diagnostics) {
        Map<String, Object> inputConfig = objectMap(node.config().get("inputs"));
        Map<String, String> inputs = new LinkedHashMap<>();
        if (inputConfig.isEmpty()) {
            node.inputs().forEach((key, binding) -> inputs.put(targetInputName(key, binding),
                    bindingToExpression(binding, nodesById,
                            "/nodes/" + node.id() + "/inputs/" + key, diagnostics)));
        } else {
            inputConfig.forEach((key, value) -> inputs.put(key, expressionFromObject(value, nodesById,
                    "/nodes/" + node.id() + "/config/inputs/" + key, diagnostics)));
        }
        if (inputs.isEmpty()) {
            inputs.put("value", "ctx.value");
        }

        StringJoiner inputJoiner = new StringJoiner(",\n");
        inputs.forEach((key, expression) -> {
            if (!isDslFieldName(key)) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.decisionTableInputKey.invalid",
                        "Decision table input key '%s' cannot be rendered as a BLOGE DSL field."
                                .formatted(key),
                        "/nodes/" + node.id() + "/config/inputs/" + key));
            }
            inputJoiner.add("    " + key + " = " + expression);
        });

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
        for (int i = 0; i < rules.size(); i++) {
            block.append(ruleToDsl(rules.get(i), "/nodes/" + node.id() + "/config/rules/" + i, diagnostics))
                    .append("\n");
        }
        block.append("  }");
        return block.toString();
    }

    private String transformToDsl(GraphDraft.DraftNode node,
                                  Map<String, GraphDraft.DraftNode> nodesById,
                                  List<VisualDiagnostic> diagnostics) {
        Map<String, Object> assignmentConfig = objectMap(node.config().get("assignments"));
        Map<String, String> assignments = new LinkedHashMap<>();
        if (assignmentConfig.isEmpty()) {
            node.inputs().forEach((key, binding) -> assignments.put(targetInputName(key, binding),
                    bindingToExpression(binding, nodesById,
                            "/nodes/" + node.id() + "/inputs/" + key, diagnostics)));
        } else {
            assignmentConfig.forEach((key, value) -> {
                if (!isDslFieldName(key)) {
                    diagnostics.add(VisualDiagnostic.error("visual.codegen.transformAssignmentKey.invalid",
                            "Transform assignment key '%s' cannot be rendered as a BLOGE DSL object field."
                                    .formatted(key),
                            "/nodes/" + node.id() + "/config/assignments/" + key));
                }
                assignments.put(key, expressionFromObject(value, nodesById,
                        "/nodes/" + node.id() + "/config/assignments/" + key, diagnostics));
            });
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

    private static String ruleToDsl(Object rawRule,
                                    String path,
                                    List<VisualDiagnostic> diagnostics) {
        Map<String, Object> rule = objectMap(rawRule);
        Map<String, Object> output = objectMap(rule.get("output"));
        String outputPath = path + "/output";
        if (output.isEmpty()) {
            output = decisionTableImplicitOutput(rule);
            outputPath = path;
        }
        String renderedOutput = renderDecisionTableOutput(output, outputPath, diagnostics);
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

    private static Map<String, Object> decisionTableImplicitOutput(Map<String, Object> rule) {
        Map<String, Object> output = new LinkedHashMap<>(rule);
        output.remove("conditions");
        output.remove("condition");
        output.remove("otherwise");
        output.remove("id");
        return output;
    }

    private static String renderDecisionTableOutput(Map<?, ?> output,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        if (output.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        output.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!isDslFieldName(name)) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.decisionTableOutputKey.invalid",
                        "Decision table output key '%s' cannot be rendered as a BLOGE DSL object field."
                                .formatted(name),
                        path + "/" + name));
            }
            if (value instanceof Map<?, ?> nested) {
                joiner.add(name + ": " + renderDecisionTableOutput(nested, path + "/" + name, diagnostics));
            } else {
                joiner.add(name + ": " + renderLiteral(value));
            }
        });
        return joiner.toString();
    }

    private static void appendCommonExecutionConfig(StringBuilder block,
                                                    Map<String, Object> config,
                                                    Map<String, GraphDraft.DraftNode> nodesById,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        Object timeout = config.get("timeout");
        if (timeout != null && !String.valueOf(timeout).isBlank()) {
            block.append("    timeout = ")
                    .append(expressionFromObject(timeout, nodesById, path + "/timeout", diagnostics))
                    .append("\n");
        }
        Object retryAttempts = config.get("retryAttempts");
        if (retryAttempts != null) {
            block.append("    retry = { attempts: ")
                    .append(expressionFromObject(retryAttempts, nodesById, path + "/retryAttempts", diagnostics))
                    .append(", backoff: 200ms }\n");
        }
    }

    private String branchToDsl(GraphDraft.DraftNode node,
                               OperatorDefinition operator,
                               Map<String, GraphDraft.DraftNode> nodesById,
                               List<GraphDraft.DraftEdge> routeEdges,
                               List<VisualDiagnostic> diagnostics) {
        if (routeEdges.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.codegen.branch.routesRequired",
                    "Branch node '%s' must have at least one route edge.".formatted(node.id()),
                    "/nodes/" + node.id()));
        }
        Map<String, String> inputExpressions = new LinkedHashMap<>();
        node.inputs().forEach((key, binding) -> addInputExpressionAliases(inputExpressions, key, binding,
                bindingToExpression(binding, nodesById, "/nodes/" + node.id() + "/inputs/" + key, diagnostics)));
        String selectorTemplate = stringValue(operator.lowering().parameters().get("expression"));
        String selector = renderTemplateExpression(selectorTemplate, inputExpressions);
        if (selector.isBlank() || selector.equals(selectorTemplate)) {
            diagnostics.add(VisualDiagnostic.error("visual.codegen.branch.expressionUnresolved",
                    "Branch node '%s' selector expression could not be resolved from its input bindings."
                            .formatted(node.id()),
                    "/nodes/" + node.id() + "/inputs"));
        }

        StringBuilder block = new StringBuilder();
        block.append("  transform ").append(node.id()).append(" {\n")
                .append("    value = ").append(selector.isBlank() ? "null" : selector).append("\n")
                .append("  }\n\n");
        block.append("  branch on ").append(node.id()).append(".output.value {\n");
        for (GraphDraft.DraftEdge edge : routeEdges) {
            block.append("    ")
                    .append(renderRouteCondition(edge.condition()))
                    .append(" -> ")
                    .append(edge.target().nodeId())
                    .append("\n");
        }
        block.append("  }");
        return block.toString();
    }

    private static String renderRouteCondition(String condition) {
        String trimmed = condition == null ? "" : condition.trim();
        if (trimmed.isBlank() || "otherwise".equalsIgnoreCase(trimmed)) {
            return "otherwise";
        }
        if ("true".equals(trimmed) || "false".equals(trimmed) || "null".equals(trimmed)
                || isNumberLiteral(trimmed) || isQuotedString(trimmed)) {
            return trimmed;
        }
        return quote(trimmed);
    }

    private static boolean isNumberLiteral(String value) {
        return value.matches("[-+]?(?:\\d+|\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?");
    }

    private static boolean isQuotedString(String value) {
        return value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")));
    }

    private static Map<String, Object> businessConfig(Map<String, Object> config) {
        Map<String, Object> business = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (!EXECUTION_CONFIG_KEYS.contains(key)) {
                business.put(key, value);
            }
        });
        return business;
    }

    private static String renderObjectBindings(Map<String, GraphDraft.Binding> bindings,
                                               Map<String, GraphDraft.DraftNode> nodesById,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        if (bindings.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        bindings.forEach((key, binding) -> {
            if (!isDslFieldName(key)) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.objectBindingKey.invalid",
                        "Object binding key '%s' cannot be rendered as a BLOGE DSL object field."
                                .formatted(key),
                        path + "/" + key));
            }
            joiner.add(key + ": " + bindingToExpression(binding, nodesById, path + "/" + key, diagnostics));
        });
        return joiner.toString();
    }

    private static Map<String, String> renderNativeInputAssignments(GraphDraft.DraftNode node,
                                                                    Map<String, GraphDraft.DraftNode> nodesById,
                                                                    List<VisualDiagnostic> diagnostics) {
        Map<String, Object> inputTree = new LinkedHashMap<>();
        node.inputs().forEach((key, binding) -> putNativeInput(inputTree, nativeInputPath(key, binding),
                bindingToExpression(binding, nodesById, "/nodes/" + node.id() + "/inputs/" + key, diagnostics),
                "/nodes/" + node.id() + "/inputs/" + key, diagnostics));
        Map<String, String> rendered = new LinkedHashMap<>();
        inputTree.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                rendered.put(key, renderExpressionObjectLiteral(nested));
            } else {
                rendered.put(key, String.valueOf(value));
            }
        });
        return rendered;
    }

    private static void putNativeInput(Map<String, Object> inputTree,
                                       String inputPath,
                                       String expression,
                                       String diagnosticPath,
                                       List<VisualDiagnostic> diagnostics) {
        String normalized = inputPath == null ? "" : inputPath.trim();
        if (normalized.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.codegen.inputPath.required",
                    "Native operator input path is required.", diagnosticPath));
            return;
        }
        String[] segments = normalized.split("\\.");
        Map<String, Object> current = inputTree;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (!isDslFieldName(segment)) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.inputPath.invalid",
                        "Native operator input path '%s' contains segment '%s' that cannot be rendered as BLOGE DSL."
                                .formatted(inputPath, segment),
                        diagnosticPath));
                return;
            }
            boolean leaf = i == segments.length - 1;
            Object existing = current.get(segment);
            if (leaf) {
                if (existing instanceof Map<?, ?>) {
                    diagnostics.add(VisualDiagnostic.error("visual.codegen.inputPath.conflict",
                            "Native operator input path '%s' conflicts with nested inputs.".formatted(inputPath),
                            diagnosticPath));
                    return;
                }
                if (existing instanceof String) {
                    diagnostics.add(VisualDiagnostic.error("visual.codegen.inputPath.duplicate",
                            "Native operator input path '%s' is assigned more than once.".formatted(inputPath),
                            diagnosticPath));
                    return;
                }
                current.put(segment, expression);
                return;
            }
            if (existing instanceof String) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.inputPath.conflict",
                        "Native operator input path '%s' conflicts with a scalar input.".formatted(inputPath),
                        diagnosticPath));
                return;
            }
            if (!(existing instanceof Map<?, ?>)) {
                Map<String, Object> child = new LinkedHashMap<>();
                current.put(segment, child);
                current = child;
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) existing;
                current = child;
            }
        }
    }

    private static String nativeInputPath(String inputKey, GraphDraft.Binding binding) {
        String inputName = targetInputName(inputKey, binding);
        if (binding.targetPort().isBlank() || "inputs".equals(binding.targetPort())
                || inputName.equals(binding.targetPort()) || inputName.startsWith(binding.targetPort() + ".")) {
            return inputName;
        }
        return inputName.isBlank() ? binding.targetPort() : binding.targetPort() + "." + inputName;
    }

    private static String renderExpressionObjectLiteral(Map<?, ?> fields) {
        if (fields.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        fields.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) {
                joiner.add(key + ": " + renderExpressionObjectLiteral(nested));
            } else {
                joiner.add(key + ": " + value);
            }
        });
        return joiner.toString();
    }

    private static String renderConfigObjectLiteral(Map<?, ?> fields,
                                                    Map<String, GraphDraft.DraftNode> nodesById,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        if (fields.isEmpty()) {
            return "{}";
        }
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        fields.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!isDslFieldName(name)) {
                diagnostics.add(VisualDiagnostic.error("visual.codegen.configKey.invalid",
                        "Config key '%s' cannot be rendered as a BLOGE DSL object field.".formatted(name),
                        path + "/" + name));
            }
            joiner.add(name + ": " + renderConfigValue(value, nodesById, path + "/" + name, diagnostics));
        });
        return joiner.toString();
    }

    private static String renderConfigValue(Object raw,
                                            Map<String, GraphDraft.DraftNode> nodesById,
                                            String path,
                                            List<VisualDiagnostic> diagnostics) {
        if (raw instanceof GraphDraft.Binding binding) {
            return bindingToExpression(binding, nodesById, path, diagnostics);
        }
        if (raw instanceof Map<?, ?> rawMap) {
            if (rawMap.containsKey("kind")) {
                return bindingToExpression(bindingFromMap(rawMap), nodesById, path, diagnostics);
            }
            return renderConfigObjectLiteral(rawMap, nodesById, path, diagnostics);
        }
        if (raw instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            int index = 0;
            for (Object item : collection) {
                joiner.add(renderConfigValue(item, nodesById, path + "/" + index, diagnostics));
                index++;
            }
            return joiner.toString();
        }
        return renderLiteral(raw);
    }

    private static boolean isDslFieldName(String value) {
        return DSL_IDENTIFIER.matcher(value).matches() && !RESERVED_DSL_FIELD_NAMES.contains(value);
    }

    private static String bindingToExpression(GraphDraft.Binding binding,
                                              Map<String, GraphDraft.DraftNode> nodesById,
                                              String path,
                                              List<VisualDiagnostic> diagnostics) {
        return switch (binding.kind()) {
            case "contextPath" -> pathExpression("ctx", binding.path(), path + "/path", diagnostics);
            case "nodePath" -> nodePathExpression(binding, nodesById, path, diagnostics);
            case "expression" -> binding.expr().isBlank() ? "{}" : binding.expr();
            case "objectTemplate" -> renderObjectBindings(binding.fields(), nodesById, path + "/fields",
                    diagnostics);
            default -> renderLiteral(binding.value());
        };
    }

    private static String nodePathExpression(GraphDraft.Binding binding,
                                             Map<String, GraphDraft.DraftNode> nodesById,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        GraphDraft.DraftNode source = nodesById.get(binding.nodeId());
        String base = binding.nodeId() + ".output";
        String sourcePort = binding.sourcePort();
        if (source != null && source.operatorRef().startsWith("resource:")
                && (sourcePort.isBlank() || "payload".equals(sourcePort))) {
            base += ".payload";
        } else if (!sourcePort.isBlank() && !"output".equals(sourcePort)) {
            base += "." + sourcePort;
        }
        return pathExpression(base, binding.path(), path + "/path", diagnostics);
    }

    private static String pathExpression(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        String normalized = path.startsWith(".") ? path.substring(1) : path;
        StringBuilder expression = new StringBuilder(base);
        for (String segment : normalized.split("\\.")) {
            Integer index = arrayIndexSegment(segment);
            if (index != null) {
                expression.append("[").append(index).append("]");
            } else {
                expression.append(".").append(segment);
            }
        }
        return expression.toString();
    }

    private static String pathExpression(String base,
                                         String path,
                                         String diagnosticPath,
                                         List<VisualDiagnostic> diagnostics) {
        validateDslPathSegments(path, diagnosticPath, diagnostics);
        return pathExpression(base, path);
    }

    private static void validateDslPathSegments(String path,
                                                String diagnosticPath,
                                                List<VisualDiagnostic> diagnostics) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalized = path.startsWith(".") ? path.substring(1) : path;
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank() || isDslFieldName(segment) || arrayIndexSegment(segment) != null) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.error("visual.codegen.pathSegment.invalid",
                    "Binding path segment '%s' in '%s' cannot be rendered as a BLOGE DSL path segment."
                            .formatted(segment, path),
                    diagnosticPath));
        }
    }

    private static Integer arrayIndexSegment(String segment) {
        if (!ARRAY_INDEX_SEGMENT.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String expressionFromObject(Object raw,
                                               Map<String, GraphDraft.DraftNode> nodesById,
                                               String path,
                                               List<VisualDiagnostic> diagnostics) {
        if (raw instanceof GraphDraft.Binding binding) {
            return bindingToExpression(binding, nodesById, path, diagnostics);
        }
        if (raw instanceof Map<?, ?> rawMap && rawMap.containsKey("kind")) {
            return bindingToExpression(bindingFromMap(rawMap), nodesById, path, diagnostics);
        }
        if (raw instanceof String expression) {
            return expression;
        }
        return renderLiteral(raw);
    }

    private static String renderTemplateExpression(String template, Map<String, String> inputExpressions) {
        java.util.regex.Matcher matcher = TEMPLATE_REFERENCE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String reference = matcher.group(1);
            String inputPath = reference.startsWith("input.") ? reference.substring("input.".length()) : reference;
            Optional<String> replacement = templateReplacement(inputPath, inputExpressions);
            if (replacement.isPresent()) {
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement.get()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Optional<String> templateReplacement(String inputPath, Map<String, String> inputExpressions) {
        String exact = inputExpressions.get(inputPath);
        if (exact != null) {
            return Optional.of(exact);
        }
        String bestPrefix = "";
        for (String candidate : inputExpressions.keySet()) {
            if (candidate.isBlank() || !inputPath.startsWith(candidate + ".")) {
                continue;
            }
            if (candidate.length() > bestPrefix.length()) {
                bestPrefix = candidate;
            }
        }
        if (bestPrefix.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(pathExpression(inputExpressions.get(bestPrefix),
                inputPath.substring(bestPrefix.length() + 1)));
    }

    private static void addInputExpressionAliases(Map<String, String> inputExpressions,
                                                  String inputKey,
                                                  GraphDraft.Binding binding,
                                                  String expression) {
        String targetName = targetInputName(inputKey, binding);
        inputExpressions.put(targetName, expression);
        if (!binding.targetPort().isBlank()) {
            inputExpressions.put(targetName.isBlank()
                    ? binding.targetPort()
                    : binding.targetPort() + "." + targetName, expression);
        }
        if (!inputKey.equals(targetName)) {
            inputExpressions.put(inputKey, expression);
        }
    }

    private static String targetInputName(String inputKey, GraphDraft.Binding binding) {
        if (!binding.targetPath().isBlank()) {
            return binding.targetPath();
        }
        if (!binding.targetPort().isBlank() && binding.targetPort().equals(inputKey)) {
            return "";
        }
        return inputKey;
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

    private static String renderOperatorRef(String operatorRef) {
        if (UNQUOTED_DSL_OPERATOR_REF.matcher(operatorRef).matches()) {
            return operatorRef;
        }
        return quote(operatorRef);
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
        draft.nodes().forEach(node -> GraphDraftDependencies.nodeDependencies(node).forEach(source -> {
            String target = node.id();
            if (nodesById.containsKey(source) && nodesById.containsKey(target)
                    && outgoing.get(source).add(target)) {
                indegree.put(target, indegree.get(target) + 1);
            }
        }));
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

    private static Map<String, List<String>> explicitDependencyEdges(GraphDraft draft) {
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        draft.nodes().forEach(node -> dependencies.put(node.id(), new ArrayList<>()));
        draft.edges().forEach(edge -> {
            if (!"dependency".equals(edge.kind()) || edge.source().nodeId().isBlank()
                    || edge.target().nodeId().isBlank()) {
                return;
            }
            dependencies.computeIfAbsent(edge.target().nodeId(), ignored -> new ArrayList<>());
            List<String> targetDependencies = dependencies.get(edge.target().nodeId());
            if (!targetDependencies.contains(edge.source().nodeId())) {
                targetDependencies.add(edge.source().nodeId());
            }
        });
        return dependencies;
    }

    private static Map<String, List<GraphDraft.DraftEdge>> routeEdgesBySource(GraphDraft draft) {
        Map<String, List<GraphDraft.DraftEdge>> routes = new LinkedHashMap<>();
        draft.nodes().forEach(node -> routes.put(node.id(), new ArrayList<>()));
        draft.edges().forEach(edge -> {
            if (!"route".equals(edge.kind()) || edge.source().nodeId().isBlank()
                    || edge.target().nodeId().isBlank()) {
                return;
            }
            routes.computeIfAbsent(edge.source().nodeId(), ignored -> new ArrayList<>()).add(edge);
        });
        return routes;
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
