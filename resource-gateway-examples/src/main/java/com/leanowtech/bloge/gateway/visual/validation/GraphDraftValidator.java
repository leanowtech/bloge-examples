package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencies;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.StaticExpressionLiteral;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.compatibilityReason;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemaCompatibilityIssue;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemaTypeLabel;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.schemasCompatible;
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.staticExpressionLiteral;

/**
 * Schema-aware validator for visual graph drafts.
 */
@Service
public class GraphDraftValidator {

    private static final ValidationOptions STRICT_VALIDATION = new ValidationOptions(true);
    private static final ValidationOptions CONNECTION_PREVIEW_VALIDATION = new ValidationOptions(false);
    private static final Set<String> SUPPORTED_DRAFT_SCHEMA_VERSIONS = Set.of(
            GraphDraft.SCHEMA_VERSION
    );
    private static final Set<String> SUPPORTED_EDGE_KINDS = Set.of("data", "dependency", "route");
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String PATH_PATTERN = IDENTIFIER_PATTERN + "(?:\\." + IDENTIFIER_PATTERN + ")*";
    private static final Pattern PURE_CONTEXT_REFERENCE = Pattern.compile("^ctx(?:\\.(" + PATH_PATTERN + "))?$");
    private static final Pattern PURE_NODE_REFERENCE = Pattern.compile(
            "^(" + IDENTIFIER_PATTERN + ")\\.output(?:\\.(" + PATH_PATTERN + "))?$");
    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])ctx(?:\\.(" + PATH_PATTERN + "))?(?![A-Za-z0-9_])");
    private static final Pattern NODE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(" + IDENTIFIER_PATTERN + ")\\.output(?:\\.(" + PATH_PATTERN + "))?"
                    + "(?![A-Za-z0-9_])");
    private static final Pattern BRANCH_SELECTOR_TEMPLATE = Pattern.compile("^\\{\\{\\s*((?:input\\.)?"
            + PATH_PATTERN + ")\\s*}}$");
    private static final Pattern INTEGER_LITERAL = Pattern.compile("[-+]?\\d+");
    private static final Pattern NUMBER_LITERAL = Pattern.compile(
            "[-+]?(?:\\d+|\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Set<String> SUPPORTED_STRING_FORMATS = Set.of(
            "date",
            "date-time",
            "duration",
            "email",
            "uri",
            "uuid"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final VisualOperatorCatalog catalog;

    /**
     * @param catalog visual operator catalog
     */
    public GraphDraftValidator(VisualOperatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Validates a graph draft before code generation.
     *
     * @param draft graph draft
     * @return validation result
     */
    public VisualValidationResult validate(GraphDraft draft) {
        return validate(draft, STRICT_VALIDATION);
    }

    /**
     * Validates a draft while checking one transient canvas connection that has not written its binding yet.
     *
     * @param draft graph draft carrying the preview edge
     * @return validation result
     */
    public VisualValidationResult validateConnectionPreview(GraphDraft draft) {
        return validate(draft, CONNECTION_PREVIEW_VALIDATION);
    }

    private VisualValidationResult validate(GraphDraft draft, ValidationOptions options) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (draft == null) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.missing", "Graph draft is required.", "/"));
            return new VisualValidationResult(false, diagnostics);
        }
        if (!SUPPORTED_DRAFT_SCHEMA_VERSIONS.contains(draft.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.schemaVersion.unsupported",
                    "Graph draft schemaVersion '%s' is unsupported; visual authoring supports %s."
                            .formatted(draft.schemaVersion(), SUPPORTED_DRAFT_SCHEMA_VERSIONS),
                    "/schemaVersion"));
        }
        if (!GraphDraft.isSupportedStatus(draft.status())) {
            diagnostics.add(VisualDiagnostic.error("visual.draft.status.unsupported",
                    "Graph draft status '%s' is unsupported; visual authoring supports [%s]."
                            .formatted(draft.status(), GraphDraft.STATUS_DRAFT),
                    "/status"));
        }
        if (draft.nodes().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.graph.empty", "Graph must contain at least one node.", "/nodes"));
        }
        diagnostics.addAll(VisualSecretGuard.detectDraftSecrets(draft));
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(draft.inputSchema(), "/inputSchema"));

        Set<String> nodeIds = new HashSet<>();
        Map<String, GraphDraft.DraftNode> nodesById = new LinkedHashMap<>();
        Map<String, OperatorDefinition> operatorsByNodeId = new LinkedHashMap<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            String nodePath = "/nodes/" + i;
            if (!nodeIds.add(node.id())) {
                diagnostics.add(VisualDiagnostic.error("visual.node.duplicateId",
                        "Duplicate node id: " + node.id(), nodePath + "/id"));
            }
            nodesById.put(node.id(), node);
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unknown",
                        "Unknown operatorRef: " + node.operatorRef(), nodePath + "/operatorRef"));
                continue;
            }
            operatorsByNodeId.put(node.id(), operator.get());
            validateOperatorFingerprint(node, operator.get(), draft.operatorFingerprints(), nodePath, diagnostics);
            validateOperatorPolicy(draft, node, operator.get(), nodePath, diagnostics);
            validateDuplicateInputTargets(node, operator.get(), nodePath, diagnostics);
            validateRequiredInputs(node, operator.get(), nodePath, diagnostics);
            validateUnknownInputs(node, operator.get(), nodePath, diagnostics);
            validateConfig(node, operator.get(), nodePath, diagnostics);
        }

        validateNodePathBindings(draft, nodesById, operatorsByNodeId, diagnostics);
        validateConfigReferences(draft, nodesById, operatorsByNodeId, diagnostics);
        validateEdgeIdentity(draft, diagnostics);
        validateEdges(draft, nodesById, operatorsByNodeId, diagnostics);
        if (options.requireEdgeBindingConsistency()) {
            validateDataEdgeBindingConsistency(draft, nodesById, operatorsByNodeId, diagnostics);
        }
        validateAcyclic(draft, nodesById, diagnostics);
        validateOutputSelection(draft, nodeIds, operatorsByNodeId, diagnostics);
        validateOutputReachability(draft, nodesById, diagnostics);
        return new VisualValidationResult(diagnostics.stream().noneMatch(VisualDiagnostic::error), diagnostics);
    }

    private static void validateOperatorFingerprint(GraphDraft.DraftNode node,
                                                    OperatorDefinition operator,
                                                    Map<String, String> operatorFingerprints,
                                                    String nodePath,
                                                    List<VisualDiagnostic> diagnostics) {
        if (operatorFingerprints.isEmpty()) {
            return;
        }
        String expected = operatorFingerprints.get(node.id());
        if (expected == null || expected.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.operator.fingerprintMissing",
                    "Node '%s' using operator '%s' is missing an operator fingerprint snapshot."
                            .formatted(node.id(), operator.operatorRef()),
                    nodePath + "/operatorRef"));
            return;
        }
        if (expected.equals(operator.fingerprint())) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.fingerprintMismatch",
                "Node '%s' was authored against operator '%s' fingerprint '%s', but the catalog now exposes '%s'."
                        .formatted(node.id(), operator.operatorRef(), expected, operator.fingerprint()),
                nodePath + "/operatorRef"));
    }

    private static void validateOperatorPolicy(GraphDraft draft,
                                               GraphDraft.DraftNode node,
                                               OperatorDefinition operator,
                                               String nodePath,
                                               List<VisualDiagnostic> diagnostics) {
        List<String> violations = operator.policy().violations(draft.tenantId(), draft.namespace(),
                draft.environment());
        if (violations.isEmpty()) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.operator.policyDenied",
                "Operator '%s' is not available for draft scope tenant='%s', namespace='%s', environment='%s': %s."
                        .formatted(operator.operatorRef(), draft.tenantId(), draft.namespace(), draft.environment(),
                                String.join("; ", violations)),
                nodePath + "/operatorRef"));
    }

    private static void validateOutputSelection(GraphDraft draft,
                                                Set<String> nodeIds,
                                                Map<String, OperatorDefinition> operatorsByNodeId,
                                                List<VisualDiagnostic> diagnostics) {
        GraphDraft.OutputSelection output = draft.output();
        if (output.nodeId().isBlank()) {
            return;
        }
        if (!nodeIds.contains(output.nodeId())) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unknownNode",
                    "Output node does not exist: " + output.nodeId(), "/output/nodeId"));
            return;
        }

        OperatorDefinition operator = operatorsByNodeId.get(output.nodeId());
        if (operator == null) {
            return;
        }
        if (operator.ports().outputs().isEmpty()
                && ("branch".equals(operator.lowering().mode()) || !output.path().isBlank())) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unselectableNode",
                    "Output node '%s' using operator '%s' does not expose output ports."
                            .formatted(output.nodeId(), operator.operatorRef()),
                    "/output/nodeId"));
            return;
        }
        if (operator.ports().outputs().isEmpty()) {
            return;
        }
        if (output.path().isBlank()) {
            return;
        }
        OutputReference outputReference = outputReference(operator, output.path());
        Optional<OperatorDefinition.Port> outputPort = resolveOutputPort(operator, outputReference.port());
        if (outputPort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unknownPort",
                    "Output path '%s' must start with a declared output port on operator '%s'."
                            .formatted(output.path(), operator.operatorRef()),
                    "/output/path"));
            return;
        }
        if (propertyAtPath(outputPort.get().schema(), outputReference.path()) == null) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unknownPath",
                    "Output node '%s' port '%s' does not expose path '%s'."
                            .formatted(output.nodeId(), outputPort.get().name(), outputReference.path()),
                    "/output/path"));
        }
    }

    private static void validateOutputReachability(GraphDraft draft,
                                                   Map<String, GraphDraft.DraftNode> nodesById,
                                                   List<VisualDiagnostic> diagnostics) {
        String outputNodeId = draft.output().nodeId();
        if (outputNodeId.isBlank() || !nodesById.containsKey(outputNodeId) || nodesById.size() <= 1) {
            return;
        }

        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        draft.nodes().forEach(node -> predecessors.put(node.id(), new LinkedHashSet<>()));
        draft.edges().forEach(edge -> {
            String source = edge.source().nodeId();
            String target = edge.target().nodeId();
            if (nodesById.containsKey(source) && nodesById.containsKey(target)) {
                predecessors.get(target).add(source);
            }
        });
        draft.nodes().forEach(node -> GraphDraftDependencies.nodeDependencies(node).forEach(source -> {
            if (nodesById.containsKey(source)) {
                predecessors.get(node.id()).add(source);
            }
        }));

        Set<String> reachable = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        reachable.add(outputNodeId);
        queue.add(outputNodeId);
        for (int i = 0; i < queue.size(); i++) {
            String nodeId = queue.get(i);
            for (String predecessor : predecessors.getOrDefault(nodeId, Set.of())) {
                if (reachable.add(predecessor)) {
                    queue.add(predecessor);
                }
            }
        }

        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            if (!reachable.contains(node.id())) {
                diagnostics.add(VisualDiagnostic.warning("visual.graph.unreachableNode",
                        "Node '%s' is not on any data, dependency, route, input, or config reference path leading to selected output node '%s'."
                                .formatted(node.id(), outputNodeId),
                        "/nodes/" + i));
            }
        }
    }

    private static void validateRequiredInputs(GraphDraft.DraftNode node,
                                               OperatorDefinition operator,
                                               String nodePath,
                                               List<VisualDiagnostic> diagnostics) {
        for (OperatorDefinition.Port port : operator.ports().inputs()) {
            for (String required : requiredPaths(port.schema())) {
                if (!hasInputForPort(node, operator, port.name(), required)) {
                    diagnostics.add(VisualDiagnostic.error("visual.input.required",
                            "Node '%s' requires input '%s' on port '%s'."
                                    .formatted(node.id(), required, port.name()),
                            nodePath + "/inputs/" + required));
                }
            }
        }
    }

    private static void validateUnknownInputs(GraphDraft.DraftNode node,
                                              OperatorDefinition operator,
                                              String nodePath,
                                              List<VisualDiagnostic> diagnostics) {
        for (Map.Entry<String, GraphDraft.Binding> input : node.inputs().entrySet()) {
            String inputName = targetInputName(input.getKey(), input.getValue());
            Optional<OperatorDefinition.Port> targetPort = resolveInputPort(operator, input.getValue().targetPort(),
                    inputName);
            if (targetPort.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.input.unknownTargetPort",
                        "Input '%s' must target a declared input port on operator '%s'."
                                .formatted(inputName, operator.operatorRef()),
                        nodePath + "/inputs/" + input.getKey()));
                continue;
            }
            Map<String, Object> properties = targetPort.get().schema().properties();
            if (properties.isEmpty()) {
                continue;
            }
            if (propertyAtPath(targetPort.get().schema(), inputName) == null) {
                diagnostics.add(VisualDiagnostic.warning("visual.input.unknown",
                        "Input '%s' is not declared by operator '%s' port '%s'."
                                .formatted(inputName, operator.operatorRef(), targetPort.get().name()),
                        nodePath + "/inputs/" + input.getKey()));
            }
        }
    }

    private static boolean hasInputForPort(GraphDraft.DraftNode node,
                                           OperatorDefinition operator,
                                           String portName,
                                           String inputName) {
        return node.inputs().entrySet().stream()
                .anyMatch(entry -> bindingSatisfiesRequiredPath(entry.getKey(), entry.getValue(),
                        operator, portName, inputName));
    }

    private static boolean bindingSatisfiesRequiredPath(String inputKey,
                                                        GraphDraft.Binding binding,
                                                        OperatorDefinition operator,
                                                        String portName,
                                                        String requiredPath) {
        String inputName = targetInputName(inputKey, binding);
        if (!"objectTemplate".equals(binding.kind())) {
            return satisfiesRequiredPath(inputName, requiredPath)
                    && bindingTargetsPort(operator, binding, portName, inputName);
        }
        if (inputName.equals(requiredPath)
                && bindingTargetsPort(operator, binding, portName, inputName)) {
            return true;
        }
        return binding.fields().entrySet().stream()
                .anyMatch(entry -> {
                    String nestedInputKey = inputName.isBlank() ? entry.getKey() : inputName + "." + entry.getKey();
                    return bindingSatisfiesRequiredPath(nestedInputKey, entry.getValue(),
                            operator, portName, requiredPath);
                });
    }

    private static boolean satisfiesRequiredPath(String inputName, String requiredPath) {
        if (inputName == null || inputName.isBlank()) {
            return true;
        }
        return inputName.equals(requiredPath)
                || inputName.startsWith(requiredPath + ".")
                || requiredPath.startsWith(inputName + ".");
    }

    private static boolean bindingTargetsPort(OperatorDefinition operator,
                                              GraphDraft.Binding binding,
                                              String portName,
                                              String inputName) {
        if (!binding.targetPort().isBlank()) {
            return binding.targetPort().equals(portName);
        }
        Optional<OperatorDefinition.Port> resolved = resolveInputPort(operator, "", inputName);
        return resolved.map(port -> port.name().equals(portName)).orElse(false);
    }

    private static void validateDuplicateInputTargets(GraphDraft.DraftNode node,
                                                      OperatorDefinition operator,
                                                      String nodePath,
                                                      List<VisualDiagnostic> diagnostics) {
        Map<InputTarget, String> claimedTargets = new LinkedHashMap<>();
        node.inputs().forEach((inputKey, binding) -> collectInputTargets(inputKey, binding, operator,
                "", "", nodePath + "/inputs/" + inputKey, node.id(), claimedTargets, diagnostics));
    }

    private static void collectInputTargets(String inputKey,
                                            GraphDraft.Binding binding,
                                            OperatorDefinition operator,
                                            String inheritedPort,
                                            String parentPath,
                                            String targetPath,
                                            String nodeId,
                                            Map<InputTarget, String> claimedTargets,
                                            List<VisualDiagnostic> diagnostics) {
        String targetPort = binding.targetPort().isBlank() ? inheritedPort : binding.targetPort();
        String inputName = targetInputName(inputKey, binding);
        String inputPath = joinInputPath(parentPath, inputName);
        if ("objectTemplate".equals(binding.kind()) && !binding.fields().isEmpty()) {
            binding.fields().forEach((fieldName, nested) -> collectInputTargets(fieldName, nested, operator,
                    targetPort, inputPath, targetPath + "/" + fieldName, nodeId, claimedTargets, diagnostics));
            return;
        }
        claimInputTarget(operator, targetPort, inputPath, targetPath, nodeId, claimedTargets, diagnostics);
    }

    private static void claimInputTarget(OperatorDefinition operator,
                                         String targetPort,
                                         String inputPath,
                                         String targetPath,
                                         String nodeId,
                                         Map<InputTarget, String> claimedTargets,
                                         List<VisualDiagnostic> diagnostics) {
        Optional<OperatorDefinition.Port> resolvedPort = resolveInputPort(operator, targetPort, inputPath);
        if (resolvedPort.isEmpty()) {
            return;
        }
        InputTarget target = new InputTarget(resolvedPort.get().name(), inputPath);
        for (Map.Entry<InputTarget, String> claimed : claimedTargets.entrySet()) {
            if (claimed.getKey().overlaps(target)) {
                diagnostics.add(VisualDiagnostic.error("visual.input.duplicateTarget",
                        "Input target '%s' on node '%s' is assigned more than once; first assignment is at '%s'."
                                .formatted(target.label(), nodeId, claimed.getValue()),
                        targetPath));
                return;
            }
        }
        claimedTargets.put(target, targetPath);
    }

    private static String joinInputPath(String parentPath, String childPath) {
        if (parentPath == null || parentPath.isBlank()) {
            return childPath == null ? "" : childPath;
        }
        if (childPath == null || childPath.isBlank()) {
            return parentPath;
        }
        return parentPath + "." + childPath;
    }

    private static void validateNodePathBindings(GraphDraft draft,
                                                 Map<String, GraphDraft.DraftNode> nodesById,
                                                 Map<String, OperatorDefinition> operatorsByNodeId,
                                                 List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            OperatorDefinition targetOperator = operatorsByNodeId.get(node.id());
            if (targetOperator == null) {
                continue;
            }
            for (Map.Entry<String, GraphDraft.Binding> input : node.inputs().entrySet()) {
                String inputName = targetInputName(input.getKey(), input.getValue());
                validateBinding(input.getValue(), inputName, targetOperator, draft.inputSchema(),
                        nodesById, operatorsByNodeId,
                        "/nodes/" + i + "/inputs/" + input.getKey(), diagnostics);
            }
        }
    }

    private static void validateConfigReferences(GraphDraft draft,
                                                 Map<String, GraphDraft.DraftNode> nodesById,
                                                 Map<String, OperatorDefinition> operatorsByNodeId,
                                                 List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            OperatorDefinition operator = operatorsByNodeId.get(node.id());
            if (operator == null) {
                continue;
            }
            validateConfigReferenceValue(node.config(), operator.configSchema().schema(), draft.inputSchema(),
                    nodesById, operatorsByNodeId, "/nodes/" + i + "/config", diagnostics);
        }
    }

    private static void validateConfigReferenceValue(Object value,
                                                     Map<String, Object> configSchema,
                                                     SchemaEnvelope inputSchema,
                                                     Map<String, GraphDraft.DraftNode> nodesById,
                                                     Map<String, OperatorDefinition> operatorsByNodeId,
                                                     String targetPath,
                                                     List<VisualDiagnostic> diagnostics) {
        if (value instanceof String expression) {
            if (!looksLikeReferenceExpression(expression)) {
                return;
            }
            validateConfigExpressionValue(expression, configSchema, inputSchema, nodesById, operatorsByNodeId,
                    targetPath, diagnostics);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if ("expression".equals(map.get("kind"))) {
                Object expression = map.get("expr");
                validateConfigExpressionValue(expression == null ? "" : String.valueOf(expression), configSchema,
                        inputSchema, nodesById, operatorsByNodeId, targetPath + "/expr", diagnostics);
                return;
            }
            if ("objectTemplate".equals(map.get("kind")) && map.get("fields") instanceof Map<?, ?> fields) {
                fields.forEach((key, item) -> validateConfigReferenceValue(item,
                        configChildSchema(configSchema, String.valueOf(key)), inputSchema, nodesById,
                        operatorsByNodeId, targetPath + "/fields/" + key, diagnostics));
                return;
            }
            map.forEach((key, item) -> validateConfigReferenceValue(item,
                    configChildSchema(configSchema, String.valueOf(key)), inputSchema, nodesById,
                    operatorsByNodeId, targetPath + "/" + key, diagnostics));
            return;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> items = objectProperty(configSchema.get("items"));
            for (int i = 0; i < list.size(); i++) {
                validateConfigReferenceValue(list.get(i), items == null ? Map.of() : items, inputSchema,
                        nodesById, operatorsByNodeId,
                        targetPath + "/" + i, diagnostics);
            }
        }
    }

    private static void validateConfigExpressionValue(String expression,
                                                      Map<String, Object> targetSchema,
                                                      SchemaEnvelope inputSchema,
                                                      Map<String, GraphDraft.DraftNode> nodesById,
                                                      Map<String, OperatorDefinition> operatorsByNodeId,
                                                      String targetPath,
                                                      List<VisualDiagnostic> diagnostics) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        ExpressionReference pureReference = resolvePureExpressionReference(expression.trim(), inputSchema,
                nodesById, operatorsByNodeId, targetPath, diagnostics);
        if (pureReference.matched()) {
            validateConfigReferenceType(pureReference.schema(), targetSchema, pureReference.label(), targetPath,
                    diagnostics);
            return;
        }
        Optional<StaticExpressionLiteral> literal = staticExpressionLiteral(expression);
        if (literal.isPresent()) {
            validateConfigReferenceType(literal.get().schema(), targetSchema, literal.get().label(), targetPath,
                    diagnostics);
            return;
        }
        validateExpressionReferences(expression, inputSchema, nodesById, operatorsByNodeId, targetPath,
                diagnostics);
    }

    private static void validateConfigReferenceType(Map<String, Object> sourceSchema,
                                                    Map<String, Object> targetSchema,
                                                    String label,
                                                    String targetPath,
                                                    List<VisualDiagnostic> diagnostics) {
        if (sourceSchema != null && targetSchema != null && !schemasCompatible(sourceSchema, targetSchema)) {
            String reason = schemaCompatibilityIssue(sourceSchema, targetSchema)
                    .map(VisualSchemaCompatibility::compatibilityReason)
                    .orElse("");
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Cannot assign config expression '%s' %s to config %s."
                            .formatted(label, schemaTypeLabel(sourceSchema), schemaTypeLabel(targetSchema))
                            + reason,
                    targetPath));
        }
    }

    private static boolean looksLikeReferenceExpression(String expression) {
        return expression.contains("ctx.") || expression.contains(".output");
    }

    private static void validateBinding(GraphDraft.Binding binding,
                                        String inputName,
                                        OperatorDefinition targetOperator,
                                        SchemaEnvelope inputSchema,
                                        Map<String, GraphDraft.DraftNode> nodesById,
                                        Map<String, OperatorDefinition> operatorsByNodeId,
                                        String targetPath,
        List<VisualDiagnostic> diagnostics) {
        if ("objectTemplate".equals(binding.kind())) {
            Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                    inputName);
            if (targetPort.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                        "Binding target input '%s' must target a declared port on operator '%s'."
                                .formatted(inputName, targetOperator.operatorRef()),
                        targetPath));
                return;
            }
            Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), inputName);
            if (targetProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                        "Target port '%s' does not accept path '%s'."
                                .formatted(targetPort.get().name(), inputName),
                        targetPath));
                return;
            }
            String targetType = schemaType(targetProperty);
            if (!targetType.isBlank()
                    && !"object".equals(targetType)
                    && !"any".equals(targetType)
                    && !"opaque".equals(targetType)) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                        "Object template for input '%s.%s' must target object-compatible schema, but target is %s."
                                .formatted(targetPort.get().name(), inputName, schemaTypeLabel(targetProperty)),
                        targetPath));
                return;
            }
            binding.fields().forEach((key, nested) -> {
                String nestedInputName = inputName.isBlank() ? key : inputName + "." + key;
                validateBinding(nested, nestedInputName, targetOperator, inputSchema, nodesById,
                        operatorsByNodeId, targetPath + "/" + key, diagnostics);
            });
            return;
        }
        if ("contextPath".equals(binding.kind())) {
            validateContextPathBinding(binding, inputName, targetOperator, inputSchema, targetPath, diagnostics);
            return;
        }
        if ("expression".equals(binding.kind())) {
            validateExpressionBinding(binding, inputName, targetOperator, inputSchema,
                    nodesById, operatorsByNodeId, targetPath, diagnostics);
            return;
        }
        if ("constant".equals(binding.kind())) {
            validateConstantBinding(binding, inputName, targetOperator, targetPath, diagnostics);
            return;
        }
        if (!"nodePath".equals(binding.kind())) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.kindUnsupported",
                    "Binding kind '%s' is not supported. Use constant, contextPath, nodePath, expression, or objectTemplate."
                            .formatted(binding.kind()),
                    targetPath + "/kind"));
            return;
        }

        GraphDraft.DraftNode sourceNode = nodesById.get(binding.nodeId());
        OperatorDefinition sourceOperator = operatorsByNodeId.get(binding.nodeId());
        if (sourceNode == null || sourceOperator == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownSource",
                    "Binding source node does not exist: " + binding.nodeId(), targetPath));
            return;
        }

        Optional<OperatorDefinition.Port> sourcePort = resolveOutputPort(sourceOperator, binding.sourcePort());
        if (sourcePort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownSourcePort",
                    "Binding source port '%s' is not declared by operator '%s'."
                            .formatted(binding.sourcePort(), sourceOperator.operatorRef()),
                    targetPath));
            return;
        }

        Map<String, Object> sourceProperty = propertyAtPath(sourcePort.get().schema(), binding.path());
        if (sourceProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownOutputPath",
                    "Source node '%s' port '%s' output path does not exist: %s"
                            .formatted(binding.nodeId(), sourcePort.get().name(), binding.path()),
                    targetPath));
            return;
        }

        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        if (targetPort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                    "Binding target input '%s' must target a declared port on operator '%s'."
                            .formatted(inputName, targetOperator.operatorRef()),
                    targetPath));
            return;
        }

        Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), inputName);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }
        Optional<String> compatibilityIssue = schemaCompatibilityIssue(sourceProperty, targetProperty);
        if (compatibilityIssue.isPresent()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                    "Cannot bind %s output '%s.%s' to %s input '%s.%s'."
                            .formatted(schemaTypeLabel(sourceProperty), sourcePort.get().name(), binding.path(),
                                    schemaTypeLabel(targetProperty), targetPort.get().name(), inputName)
                            + compatibilityReason(compatibilityIssue.get()),
                    targetPath));
        }
    }

    private static void validateContextPathBinding(GraphDraft.Binding binding,
                                                   String inputName,
                                                   OperatorDefinition targetOperator,
                                                   SchemaEnvelope inputSchema,
                                                   String targetPath,
                                                   List<VisualDiagnostic> diagnostics) {
        Map<String, Object> sourceProperty = propertyAtPath(inputSchema, binding.path());
        if (sourceProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownContextPath",
                    "Graph input path does not exist: ctx.%s".formatted(binding.path()),
                    targetPath));
            return;
        }

        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        if (targetPort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                    "Binding target input '%s' must target a declared port on operator '%s'."
                            .formatted(inputName, targetOperator.operatorRef()),
                    targetPath));
            return;
        }

        Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), inputName);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }
        Optional<String> compatibilityIssue = schemaCompatibilityIssue(sourceProperty, targetProperty);
        if (compatibilityIssue.isPresent()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                    "Cannot bind graph input %s 'ctx.%s' to %s input '%s.%s'."
                            .formatted(schemaTypeLabel(sourceProperty), binding.path(),
                                    schemaTypeLabel(targetProperty), targetPort.get().name(), inputName)
                            + compatibilityReason(compatibilityIssue.get()),
                    targetPath));
        }
    }

    private static void validateConstantBinding(GraphDraft.Binding binding,
                                                String inputName,
                                                OperatorDefinition targetOperator,
                                                String targetPath,
                                                List<VisualDiagnostic> diagnostics) {
        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        if (targetPort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                    "Binding target input '%s' must target a declared port on operator '%s'."
                            .formatted(inputName, targetOperator.operatorRef()),
                    targetPath));
            return;
        }

        Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), inputName);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }

        if (!constantValueMatchesSchema(binding.value(), targetProperty)) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                    "Constant value for input '%s.%s' must be %s."
                            .formatted(targetPort.get().name(), inputName, schemaTypeLabel(targetProperty)),
                    targetPath));
        }
    }

    private static void validateExpressionBinding(GraphDraft.Binding binding,
                                                  String inputName,
                                                  OperatorDefinition targetOperator,
                                                  SchemaEnvelope inputSchema,
                                                  Map<String, GraphDraft.DraftNode> nodesById,
                                                  Map<String, OperatorDefinition> operatorsByNodeId,
                                                  String targetPath,
                                                  List<VisualDiagnostic> diagnostics) {
        String expression = binding.expr().trim();
        if (expression.isBlank()) {
            return;
        }

        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        if (targetPort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                    "Binding target input '%s' must target a declared port on operator '%s'."
                            .formatted(inputName, targetOperator.operatorRef()),
                    targetPath));
            return;
        }

        Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), inputName);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }

        ExpressionReference pureReference = resolvePureExpressionReference(expression, inputSchema,
                nodesById, operatorsByNodeId, targetPath, diagnostics);
        if (pureReference.matched()) {
            Optional<String> compatibilityIssue = pureReference.schema() == null
                    ? Optional.empty()
                    : schemaCompatibilityIssue(pureReference.schema(), targetProperty);
            if (compatibilityIssue.isPresent()) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                        "Cannot bind expression '%s' %s to %s input '%s.%s'."
                                .formatted(pureReference.label(), schemaTypeLabel(pureReference.schema()),
                                        schemaTypeLabel(targetProperty), targetPort.get().name(), inputName)
                                + compatibilityReason(compatibilityIssue.get()),
                        targetPath));
            }
            return;
        }

        Optional<StaticExpressionLiteral> literal = staticExpressionLiteral(expression);
        if (literal.isPresent()) {
            Optional<String> compatibilityIssue = schemaCompatibilityIssue(literal.get().schema(), targetProperty);
            if (compatibilityIssue.isPresent()) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                        "Cannot bind expression '%s' %s to %s input '%s.%s'."
                                .formatted(literal.get().label(), schemaTypeLabel(literal.get().schema()),
                                        schemaTypeLabel(targetProperty), targetPort.get().name(), inputName)
                                + compatibilityReason(compatibilityIssue.get()),
                        targetPath));
            }
            return;
        }

        validateExpressionReferences(expression, inputSchema, nodesById, operatorsByNodeId, targetPath, diagnostics);
    }

    private static ExpressionReference resolvePureExpressionReference(String expression,
                                                                      SchemaEnvelope inputSchema,
                                                                      Map<String, GraphDraft.DraftNode> nodesById,
                                                                      Map<String, OperatorDefinition> operatorsByNodeId,
                                                                      String targetPath,
                                                                      List<VisualDiagnostic> diagnostics) {
        Matcher context = PURE_CONTEXT_REFERENCE.matcher(expression);
        if (context.matches()) {
            String path = context.group(1) == null ? "" : context.group(1);
            return new ExpressionReference(true, resolveContextReference(path, inputSchema, targetPath, diagnostics),
                    path.isBlank() ? "ctx" : "ctx." + path);
        }

        Matcher node = PURE_NODE_REFERENCE.matcher(expression);
        if (node.matches()) {
            String nodeId = node.group(1);
            String outputPath = node.group(2) == null ? "" : node.group(2);
            return new ExpressionReference(true,
                    resolveNodeReference(nodeId, outputPath, nodesById, operatorsByNodeId, targetPath, diagnostics),
                    outputPath.isBlank() ? nodeId + ".output" : nodeId + ".output." + outputPath);
        }

        return new ExpressionReference(false, null, "");
    }

    private static void validateExpressionReferences(String expression,
                                                     SchemaEnvelope inputSchema,
                                                     Map<String, GraphDraft.DraftNode> nodesById,
                                                     Map<String, OperatorDefinition> operatorsByNodeId,
                                                     String targetPath,
                                                     List<VisualDiagnostic> diagnostics) {
        String searchable = withoutQuotedStrings(expression);
        Set<String> seenReferences = new HashSet<>();

        Matcher context = CONTEXT_REFERENCE.matcher(searchable);
        while (context.find()) {
            String path = context.group(1) == null ? "" : context.group(1);
            if (seenReferences.add("ctx:" + path)) {
                resolveContextReference(path, inputSchema, targetPath, diagnostics);
            }
        }

        Matcher node = NODE_REFERENCE.matcher(searchable);
        while (node.find()) {
            String nodeId = node.group(1);
            String outputPath = node.group(2) == null ? "" : node.group(2);
            if (seenReferences.add("node:" + nodeId + ":" + outputPath)) {
                resolveNodeReference(nodeId, outputPath, nodesById, operatorsByNodeId, targetPath, diagnostics);
            }
        }
    }

    private static Map<String, Object> resolveContextReference(String path,
                                                               SchemaEnvelope inputSchema,
                                                               String targetPath,
                                                               List<VisualDiagnostic> diagnostics) {
        Map<String, Object> sourceProperty = propertyAtPath(inputSchema, path);
        if (sourceProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownContextPath",
                    "Graph input path does not exist: %s".formatted(path.isBlank() ? "ctx" : "ctx." + path),
                    targetPath));
        }
        return sourceProperty;
    }

    private static Map<String, Object> resolveNodeReference(String nodeId,
                                                            String outputPath,
                                                            Map<String, GraphDraft.DraftNode> nodesById,
                                                            Map<String, OperatorDefinition> operatorsByNodeId,
                                                            String targetPath,
                                                            List<VisualDiagnostic> diagnostics) {
        GraphDraft.DraftNode sourceNode = nodesById.get(nodeId);
        OperatorDefinition sourceOperator = operatorsByNodeId.get(nodeId);
        if (sourceNode == null || sourceOperator == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownSource",
                    "Expression source node does not exist: " + nodeId, targetPath));
            return null;
        }

        OutputReference outputReference = outputReference(sourceOperator, outputPath);
        Optional<OperatorDefinition.Port> sourcePort = resolveOutputPort(sourceOperator, outputReference.port());
        if (sourcePort.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownSourcePort",
                    "Expression source port '%s' is not declared by operator '%s'."
                            .formatted(outputReference.port(), sourceOperator.operatorRef()),
                    targetPath));
            return null;
        }

        Map<String, Object> sourceProperty = propertyAtPath(sourcePort.get().schema(), outputReference.path());
        if (sourceProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownOutputPath",
                    "Expression source node '%s' port '%s' output path does not exist: %s"
                            .formatted(nodeId, sourcePort.get().name(), outputReference.path()),
                    targetPath));
        }
        return sourceProperty;
    }

    private static OutputReference outputReference(OperatorDefinition operator, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return new OutputReference("", "");
        }
        String[] segments = outputPath.split("\\.", 2);
        String first = segments[0];
        String rest = segments.length == 2 ? segments[1] : "";
        boolean firstNamesPort = operator.ports().outputs().stream()
                .anyMatch(port -> port.name().equals(first));
        return firstNamesPort ? new OutputReference(first, rest) : new OutputReference("", outputPath);
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

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema, String path) {
        if (path == null || path.isBlank()) {
            Map<String, Object> root = new LinkedHashMap<>(schema.schema());
            if (!root.containsKey("type") && !root.containsKey("kind")) {
                root.put("type", "object");
            }
            return root;
        }
        Map<String, Object> currentSchema = schema.schema();
        Map<String, Object> properties = propertiesOf(currentSchema);
        Map<String, Object> current = null;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if ("array".equals(schemaType(currentSchema))) {
                Integer index = arrayIndexSegment(segment);
                if (index == null) {
                    return null;
                }
                current = arrayItemSchemaForIndex(currentSchema, index);
                if (current == null) {
                    return null;
                }
                currentSchema = current;
                properties = propertiesOf(currentSchema);
                continue;
            }
            current = objectProperty(properties.get(segment));
            if (current == null) {
                current = patternPropertySchema(currentSchema, segment);
            }
            if (current == null) {
                current = additionalPropertySchema(currentSchema);
                if (current == null) {
                    return null;
                }
            }
            currentSchema = current;
            properties = propertiesOf(currentSchema);
        }
        return current;
    }

    private static Integer arrayIndexSegment(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static List<String> requiredPaths(SchemaEnvelope schema) {
        List<String> paths = new ArrayList<>();
        collectRequiredPaths(schema.schema(), "", paths);
        return paths;
    }

    private static void collectRequiredPaths(Map<String, Object> schema,
                                             String prefix,
                                             List<String> paths) {
        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            Map<String, Object> child = objectProperty(properties.get(required));
            String path = prefix.isBlank() ? required : prefix + "." + required;
            if (child != null && !requiredNamesOf(child).isEmpty()) {
                collectRequiredPaths(child, path, paths);
            } else {
                paths.add(path);
            }
        }
    }

    private static List<String> requiredNamesOf(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> required = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                required.add(String.valueOf(item));
            }
        }
        return required;
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> schema) {
        Object nested = schema.get("properties");
        if (!(nested instanceof Map<?, ?> rawNested)) {
            return Map.of();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawNested.entrySet()) {
            properties.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectProperty(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String schemaType(Map<String, Object> property) {
        if (property == null) {
            return "";
        }
        Object type = property.get("kind");
        if (type == null) {
            type = property.get("type");
        }
        if (type instanceof List<?> types) {
            return nullableTypePrimary(types);
        }
        if (type == null && property.containsKey("properties")) {
            return "object";
        }
        if (type == null && property.containsKey("items")) {
            return "array";
        }
        if (type == null && property.containsKey("const")) {
            return schemaTypeForValue(property.get("const"));
        }
        return type == null ? "" : String.valueOf(type);
    }

    private static String nullableTypePrimary(List<?> types) {
        String primary = "";
        int concreteTypes = 0;
        for (Object item : types) {
            if (!(item instanceof String type) || type.isBlank()) {
                return String.valueOf(types);
            }
            if (!"null".equals(type)) {
                primary = type;
                concreteTypes++;
            }
        }
        if (concreteTypes > 1) {
            return String.valueOf(types);
        }
        return primary.isBlank() ? "null" : primary;
    }

    private static List<Object> enumValues(Map<String, Object> schema) {
        if (schema.containsKey("const")) {
            List<Object> values = new ArrayList<>();
            values.add(schema.get("const"));
            return values;
        }
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        if ("enum".equals(schemaType(schema)) && schema.get("values") instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        return List.of();
    }

    private static String schemaTypeForValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (isIntegerValue(value)) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return "";
    }

    private static void validateEdges(GraphDraft draft,
                                      Map<String, GraphDraft.DraftNode> nodesById,
                                      Map<String, OperatorDefinition> operatorsByNodeId,
                                      List<VisualDiagnostic> diagnostics) {
        Map<String, Set<String>> routeConditionsBySource = new LinkedHashMap<>();
        for (int i = 0; i < draft.edges().size(); i++) {
            GraphDraft.DraftEdge edge = draft.edges().get(i);
            String edgePath = "/edges/" + i;
            GraphDraft.DraftNode sourceNode = nodesById.get(edge.source().nodeId());
            GraphDraft.DraftNode targetNode = nodesById.get(edge.target().nodeId());
            if (sourceNode == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownSource",
                        "Edge source node does not exist: " + edge.source().nodeId(),
                        edgePath + "/source/nodeId"));
            }
            if (targetNode == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownTarget",
                        "Edge target node does not exist: " + edge.target().nodeId(),
                        edgePath + "/target/nodeId"));
            }
            OperatorDefinition sourceOperator = operatorsByNodeId.get(edge.source().nodeId());
            OperatorDefinition targetOperator = operatorsByNodeId.get(edge.target().nodeId());
            if (sourceOperator == null || targetOperator == null) {
                continue;
            }
            if ("dependency".equals(edge.kind())) {
                validateDependencyEdge(edge, targetOperator, edgePath, diagnostics);
                continue;
            }
            if ("route".equals(edge.kind())) {
                validateRouteEdge(edge, sourceOperator, edgePath, routeConditionsBySource, diagnostics);
                continue;
            }
            if (!"data".equals(edge.kind())) {
                continue;
            }
            Optional<OperatorDefinition.Port> sourcePort = findPort(sourceOperator.ports().outputs(),
                    edge.source().port());
            Optional<OperatorDefinition.Port> targetPort = findPort(targetOperator.ports().inputs(),
                    edge.target().port());
            if (sourcePort.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownSourcePort",
                        "Source port '%s' is not declared by operator '%s'."
                                .formatted(edge.source().port(), sourceOperator.operatorRef()),
                        edgePath + "/source/port"));
                continue;
            }
            if (targetPort.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownTargetPort",
                        "Target port '%s' is not declared by operator '%s'."
                                .formatted(edge.target().port(), targetOperator.operatorRef()),
                        edgePath + "/target/port"));
                continue;
            }
            Map<String, Object> sourceProperty = propertyAtPath(sourcePort.get().schema(), edge.source().path());
            if (sourceProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownSourcePath",
                        "Source port '%s' does not expose path '%s'."
                                .formatted(edge.source().port(), edge.source().path()),
                        edgePath + "/source/path"));
                continue;
            }
            Map<String, Object> targetProperty = propertyAtPath(targetPort.get().schema(), edge.target().path());
            if (targetProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownTargetPath",
                        "Target port '%s' does not accept path '%s'."
                                .formatted(edge.target().port(), edge.target().path()),
                        edgePath + "/target/path"));
                continue;
            }
            Optional<String> compatibilityIssue = schemaCompatibilityIssue(sourceProperty, targetProperty);
            if (compatibilityIssue.isPresent()) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.typeMismatch",
                        "Cannot connect %s output '%s' to %s input '%s'."
                                .formatted(schemaTypeLabel(sourceProperty), edge.source().path(),
                                        schemaTypeLabel(targetProperty), edge.target().path())
                                + compatibilityReason(compatibilityIssue.get()),
                        edgePath));
            }
        }
    }

    private static void validateDependencyEdge(GraphDraft.DraftEdge edge,
                                               OperatorDefinition targetOperator,
                                               String edgePath,
                                               List<VisualDiagnostic> diagnostics) {
        if (!supportsExplicitDependencyTarget(targetOperator)) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.dependencyTargetUnsupported",
                    "Dependency edges can target operators lowered as BLOGE node blocks; operator '%s' lowers to a block that cannot declare depends_on."
                            .formatted(targetOperator.operatorRef()),
                    edgePath + "/target/nodeId"));
        }
    }

    private static boolean supportsExplicitDependencyTarget(OperatorDefinition operator) {
        if ("resource-descriptor".equals(operator.source().kind()) || "httpResource".equals(operator.operatorRef())) {
            return true;
        }
        if (!"native".equals(operator.lowering().mode())) {
            return false;
        }
        return !List.of("bloge:decisionTable", "bloge:transform").contains(operator.operatorRef());
    }

    private static void validateRouteEdge(GraphDraft.DraftEdge edge,
                                          OperatorDefinition sourceOperator,
                                          String edgePath,
                                          Map<String, Set<String>> routeConditionsBySource,
                                          List<VisualDiagnostic> diagnostics) {
        if (!"branch".equals(sourceOperator.lowering().mode())) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.routeSourceUnsupported",
                    "Route edges must start from a branch-lowered operator; operator '%s' lowers as '%s'."
                            .formatted(sourceOperator.operatorRef(), sourceOperator.lowering().mode()),
                    edgePath + "/source/nodeId"));
            return;
        }
        String condition = edge.condition().trim();
        if (condition.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.routeConditionRequired",
                    "Route edge from branch node '%s' must declare a condition such as 'true', '\"physical\"', or 'otherwise'."
                            .formatted(edge.source().nodeId()),
                    edgePath + "/condition"));
            return;
        }
        if (containsRouteControlSyntax(condition)) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.routeConditionInvalid",
                    "Route edge condition '%s' contains unsupported branch control syntax."
                            .formatted(condition),
                    edgePath + "/condition"));
            return;
        }
        validateRouteConditionDomain(edge, sourceOperator, condition, edgePath, diagnostics);
        String normalizedCondition = normalizedRouteCondition(condition);
        Set<String> conditions = routeConditionsBySource.computeIfAbsent(edge.source().nodeId(),
                ignored -> new LinkedHashSet<>());
        if (!conditions.add(normalizedCondition)) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.routeConditionDuplicate",
                    "Branch node '%s' declares duplicate route condition '%s'."
                            .formatted(edge.source().nodeId(), condition),
                    edgePath + "/condition"));
        }
    }

    private static void validateRouteConditionDomain(GraphDraft.DraftEdge edge,
                                                     OperatorDefinition sourceOperator,
                                                     String condition,
                                                     String edgePath,
                                                     List<VisualDiagnostic> diagnostics) {
        if ("otherwise".equalsIgnoreCase(condition)) {
            return;
        }
        Optional<Map<String, Object>> selectorSchema = branchSelectorSchema(sourceOperator);
        if (selectorSchema.isEmpty()) {
            return;
        }
        Object value = routeConditionLiteral(condition);
        if (constantValueMatchesSchema(value, selectorSchema.get())) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.edge.routeConditionTypeMismatch",
                "Route condition '%s' on branch node '%s' must match selector schema %s."
                        .formatted(condition, edge.source().nodeId(), schemaTypeLabel(selectorSchema.get())),
                edgePath + "/condition"));
    }

    private static Optional<Map<String, Object>> branchSelectorSchema(OperatorDefinition operator) {
        Object rawExpression = operator.lowering().parameters().get("expression");
        if (!(rawExpression instanceof String expression)) {
            return Optional.empty();
        }
        Matcher matcher = BRANCH_SELECTOR_TEMPLATE.matcher(expression.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String inputPath = matcher.group(1);
        if (inputPath.startsWith("input.")) {
            inputPath = inputPath.substring("input.".length());
        }
        return Optional.ofNullable(operatorInputPropertyAtPath(operator, inputPath));
    }

    private static Map<String, Object> operatorInputPropertyAtPath(OperatorDefinition operator, String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            return null;
        }
        String[] segments = inputPath.split("\\.", 2);
        String first = segments[0];
        String rest = segments.length == 2 ? segments[1] : "";
        for (OperatorDefinition.Port port : operator.ports().inputs()) {
            if (port.name().equals(first)) {
                return rest.isBlank() ? propertyAtPath(port.schema(), "") : propertyAtPath(port.schema(), rest);
            }
        }
        if (operator.ports().inputs().size() == 1) {
            return propertyAtPath(operator.ports().inputs().getFirst().schema(), inputPath);
        }
        return null;
    }

    private static Object routeConditionLiteral(String condition) {
        String trimmed = condition.trim();
        if ("true".equals(trimmed)) {
            return true;
        }
        if ("false".equals(trimmed)) {
            return false;
        }
        if ("null".equals(trimmed)) {
            return null;
        }
        if (INTEGER_LITERAL.matcher(trimmed).matches()) {
            try {
                long value = Long.parseLong(trimmed);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
                return value;
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if (NUMBER_LITERAL.matcher(trimmed).matches()) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return trimmed;
            }
        }
        if (quotedRouteString(trimmed)) {
            return unescapeRouteString(trimmed.substring(1, trimmed.length() - 1));
        }
        return trimmed;
    }

    private static boolean quotedRouteString(String value) {
        return value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")));
    }

    private static String unescapeRouteString(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!escaped) {
                if (current == '\\') {
                    escaped = true;
                } else {
                    result.append(current);
                }
                continue;
            }
            result.append(switch (current) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                default -> current;
            });
            escaped = false;
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    private static boolean containsRouteControlSyntax(String condition) {
        return condition.contains("\n")
                || condition.contains("\r")
                || condition.contains("->")
                || condition.contains("{")
                || condition.contains("}");
    }

    private static String normalizedRouteCondition(String condition) {
        String trimmed = condition.trim();
        if ("otherwise".equalsIgnoreCase(trimmed)) {
            return "otherwise";
        }
        return routeConditionKey(routeConditionLiteral(trimmed));
    }

    private static String routeConditionKey(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "string:" + string;
        }
        if (value instanceof Boolean bool) {
            return "boolean:" + bool;
        }
        if (value instanceof Number number) {
            return "number:" + numberLabel(number.doubleValue());
        }
        return value.getClass().getSimpleName() + ":" + value;
    }

    private static String numberLabel(double value) {
        if (Double.isFinite(value)
                && value >= Long.MIN_VALUE
                && value <= Long.MAX_VALUE
                && Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static Optional<OperatorDefinition.Port> findPort(List<OperatorDefinition.Port> ports, String name) {
        if ((name == null || name.isBlank()) && ports.size() == 1) {
            return Optional.of(ports.getFirst());
        }
        return ports.stream()
                .filter(port -> port.name().equals(name))
                .findFirst();
    }

    private static Optional<OperatorDefinition.Port> resolveOutputPort(OperatorDefinition operator,
                                                                       String portName) {
        if ((portName == null || portName.isBlank()) && operator.ports().outputs().isEmpty()) {
            return Optional.of(opaquePort("output"));
        }
        return findPort(operator.ports().outputs(), portName);
    }

    private static Optional<OperatorDefinition.Port> resolveInputPort(OperatorDefinition operator,
                                                                      String portName,
                                                                      String inputName) {
        if (portName != null && !portName.isBlank()) {
            return findPort(operator.ports().inputs(), portName);
        }
        List<OperatorDefinition.Port> ports = operator.ports().inputs();
        if (ports.isEmpty()) {
            return Optional.of(opaquePort("inputs"));
        }
        if (ports.size() == 1) {
            return Optional.of(ports.getFirst());
        }
        List<OperatorDefinition.Port> matches = ports.stream()
                .filter(port -> propertyAtPath(port.schema(), inputName) != null)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
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

    private static OperatorDefinition.Port opaquePort(String name) {
        return new OperatorDefinition.Port(name, SchemaEnvelope.opaque(), false, "Implicit opaque port.");
    }

    private static Map<String, Object> additionalPropertySchema(Map<String, Object> schema) {
        Object residual = residualPropertiesPolicy(schema);
        if (Boolean.TRUE.equals(residual)) {
            return Map.of();
        }
        if (residual instanceof Map<?, ?> residualSchema) {
            return objectProperty(residualSchema);
        }
        return null;
    }

    private static Map<String, Object> patternPropertySchema(Map<String, Object> schema, String propertyName) {
        List<Map<String, Object>> matches = matchingPatternPropertySchemas(schema, propertyName);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean constantValueMatchesSchema(Object value, Map<String, Object> schema) {
        List<Object> domainValues = enumValues(schema);
        if (!domainValues.isEmpty() && !domainValues.contains(value)) {
            return false;
        }
        if (value == null && schemaAllowsNull(schema)) {
            return true;
        }

        String type = schemaType(schema);
        if (type.isBlank() || "any".equals(type) || "opaque".equals(type)) {
            return true;
        }
        if ("object".equals(type)) {
            return constantObjectMatchesSchema(value, schema);
        }
        if ("array".equals(type)) {
            return constantArrayMatchesSchema(value, schema);
        }
        if ("enum".equals(type)) {
            Object rawValues = schema.get("values");
            return !(rawValues instanceof List<?> values) || values.isEmpty() || values.contains(value);
        }
		return configValueMatchesType(value, type)
		                && numericValueMatchesBounds(value, schema)
		                && numericValueMatchesMultipleOf(value, schema)
		                && stringValueMatchesLengthBounds(value, schema)
		                && stringValueMatchesPattern(value, schema)
		                && stringValueMatchesFormat(value, schema);
	    }

    private static boolean constantObjectMatchesSchema(Object value, Map<String, Object> schema) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return false;
        }
	        Map<String, Object> object = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
	        if (!objectValueMatchesPropertyBounds(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPropertyNames(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesPatternProperties(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesDependentRequired(object, schema)) {
	            return false;
	        }
	        if (!objectValueMatchesDependentSchemas(object, schema)) {
	            return false;
	        }

	        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                return false;
            }
        }

        Map<String, Object> properties = propertiesOf(schema);
        Object residual = residualPropertiesPolicy(schema);
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
            if (property != null) {
                if (!constantValueMatchesSchema(entry.getValue(), property)) {
                    return false;
                }
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                if (!constantValueMatchesSchema(entry.getValue(), patternSchema)) {
                    return false;
                }
            }
            if (property != null || !patternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(residual)) {
                return false;
            } else if (residual instanceof Map<?, ?> residualSchema
                    && !constantValueMatchesSchema(entry.getValue(), objectProperty(residualSchema))) {
                return false;
            }
        }
        return true;
    }

		    private static boolean constantArrayMatchesSchema(Object value, Map<String, Object> schema) {
		        if (!(value instanceof List<?> list)) {
		            return false;
		        }
	        if (!arrayValueMatchesItemBounds(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesUniqueItems(list, schema)) {
	            return false;
	        }
	        if (!arrayValueMatchesContains(list, schema)) {
	            return false;
	        }
	        for (int i = 0; i < list.size(); i++) {
	            Map<String, Object> itemSchema = arrayItemSchemaForIndex(schema, i);
	            if (itemSchema != null && !constantValueMatchesSchema(list.get(i), itemSchema)) {
	                return false;
	            }
	        }
	        return true;
		    }

	    private static boolean objectValueMatchesPropertyBounds(Map<?, ?> value, Map<String, Object> schema) {
	        long size = value.size();
	        Long minimum = objectPropertyBoundary(schema.get("minProperties"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = objectPropertyBoundary(schema.get("maxProperties"));
	        return maximum == null || size <= maximum;
	    }

	    private static boolean objectValueMatchesPropertyNames(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, Object> propertyNameSchema = propertyNameSchema(schema);
	        if (propertyNameSchema == null) {
	            return true;
	        }
	        Map<String, Object> effectiveSchema = effectivePropertyNameSchema(propertyNameSchema);
	        return value.keySet().stream()
	                .map(String::valueOf)
	                .allMatch(name -> constantValueMatchesSchema(name, effectiveSchema));
	    }

	    private static boolean objectValueMatchesPatternProperties(Map<?, ?> value, Map<String, Object> schema) {
	        for (Map.Entry<?, ?> entry : value.entrySet()) {
	            for (Map<String, Object> patternSchema : matchingPatternPropertySchemas(schema,
	                    String.valueOf(entry.getKey()))) {
	                if (!constantValueMatchesSchema(entry.getValue(), patternSchema)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static boolean objectValueMatchesDependentRequired(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, List<String>> dependencies = dependentRequiredOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            for (String dependency : entry.getValue()) {
	                if (!presentObjectProperty(value, dependency)) {
	                    return false;
	                }
	            }
	        }
	        return true;
	    }

	    private static boolean objectValueMatchesDependentSchemas(Map<?, ?> value, Map<String, Object> schema) {
	        Map<String, Map<String, Object>> dependencies = dependentSchemasOf(schema);
	        if (dependencies.isEmpty()) {
	            return true;
	        }
	        for (Map.Entry<String, Map<String, Object>> entry : dependencies.entrySet()) {
	            if (!presentObjectProperty(value, entry.getKey())) {
	                continue;
	            }
	            if (!constantValueMatchesSchema(value, effectiveDependentObjectSchema(entry.getValue()))) {
	                return false;
	            }
	        }
	        return true;
	    }

	    private static Map<String, List<String>> dependentRequiredOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentRequired");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, List<String>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof List<?> rawDependencies)) {
	                continue;
	            }
	            List<String> names = new ArrayList<>();
	            for (Object dependency : rawDependencies) {
	                if (dependency instanceof String name && !name.isBlank()) {
	                    names.add(name);
	                }
	            }
	            dependencies.put(String.valueOf(entry.getKey()), names);
	        }
	        return dependencies;
	    }

	    private static Map<String, Map<String, Object>> dependentSchemasOf(Map<String, Object> schema) {
	        Object raw = schema.get("dependentSchemas");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return Map.of();
	        }
	        Map<String, Map<String, Object>> dependencies = new LinkedHashMap<>();
	        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
	            if (!(entry.getValue() instanceof Map<?, ?> rawSchema)) {
	                continue;
	            }
	            Map<String, Object> copy = new LinkedHashMap<>();
	            rawSchema.forEach((key, item) -> copy.put(String.valueOf(key), item));
	            dependencies.put(String.valueOf(entry.getKey()), effectiveDependentObjectSchema(copy));
	        }
	        return dependencies;
	    }

	    private static Map<String, Object> effectiveDependentObjectSchema(Map<String, Object> schema) {
	        Map<String, Object> effective = new LinkedHashMap<>(schema);
	        if (schemaType(effective).isBlank()
	                && (effective.containsKey("required")
	                || effective.containsKey("dependentRequired")
	                || effective.containsKey("dependentSchemas")
	                || effective.containsKey("minProperties")
	                || effective.containsKey("maxProperties")
	                || effective.containsKey("propertyNames")
	                || effective.containsKey("patternProperties"))) {
	            effective.put("type", "object");
	        }
	        return effective;
	    }

	    private static boolean presentObjectProperty(Map<?, ?> value, String property) {
	        return value.containsKey(property) && value.get(property) != null;
	    }

	    private static List<Map<String, Object>> matchingPatternPropertySchemas(Map<String, Object> schema,
	                                                                            String propertyName) {
	        Map<String, Object> patternProperties = patternPropertiesOf(schema);
	        if (patternProperties == null || patternProperties.isEmpty()) {
	            return List.of();
	        }
	        List<Map<String, Object>> matches = new ArrayList<>();
	        for (Map.Entry<String, Object> entry : patternProperties.entrySet()) {
	            if (patternMatches(entry.getKey(), propertyName) && entry.getValue() instanceof Map<?, ?> nested) {
	                Map<String, Object> copy = new LinkedHashMap<>();
	                nested.forEach((key, item) -> copy.put(String.valueOf(key), item));
	                matches.add(copy);
	            }
	        }
	        return matches;
	    }

	    private static Map<String, Object> patternPropertiesOf(Map<String, Object> schema) {
	        Object raw = schema.get("patternProperties");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> patternProperties = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> patternProperties.put(String.valueOf(key), item));
	        return patternProperties;
	    }

	    private static boolean patternMatches(String pattern, String value) {
	        try {
	            return Pattern.compile(pattern).matcher(value).find();
	        } catch (PatternSyntaxException ex) {
	            return false;
	        }
	    }

	    private static Map<String, Object> propertyNameSchema(Map<String, Object> schema) {
	        Object raw = schema.get("propertyNames");
	        if (!(raw instanceof Map<?, ?> rawMap)) {
	            return null;
	        }
	        Map<String, Object> propertyNameSchema = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> propertyNameSchema.put(String.valueOf(key), item));
	        return propertyNameSchema;
	    }

	    private static Map<String, Object> effectivePropertyNameSchema(Map<String, Object> propertyNameSchema) {
	        Map<String, Object> effective = new LinkedHashMap<>(propertyNameSchema);
	        if (schemaType(effective).isBlank()) {
	            effective.put("type", "string");
	        }
	        return effective;
	    }

    private static void validateEdgeIdentity(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        Set<String> edgeIds = new HashSet<>();
        Set<EdgeSignature> edgeSignatures = new HashSet<>();
        for (int i = 0; i < draft.edges().size(); i++) {
            GraphDraft.DraftEdge edge = draft.edges().get(i);
            String edgePath = "/edges/" + i;
            if (!SUPPORTED_EDGE_KINDS.contains(edge.kind())) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.kindUnsupported",
                        "Edge kind '%s' is unsupported; visual authoring supports %s."
                                .formatted(edge.kind(), SUPPORTED_EDGE_KINDS),
                        edgePath + "/kind"));
            }
            if (!edgeIds.add(edge.id())) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.duplicateId",
                        "Duplicate edge id: " + edge.id(), edgePath + "/id"));
            }
            EdgeSignature signature = EdgeSignature.from(edge);
            if (!edgeSignatures.add(signature)) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.duplicateConnection",
                        "Duplicate edge connection: " + signature.label(), edgePath));
            }
        }
    }

    private record ExpressionReference(boolean matched, Map<String, Object> schema, String label) {
    }

    private record OutputReference(String port, String path) {
    }

    private record EdgeSignature(
            String kind,
            String sourceNodeId,
            String sourcePort,
            String sourcePath,
            String targetNodeId,
            String targetPort,
            String targetPath,
            String routeCondition
    ) {

        private static EdgeSignature from(GraphDraft.DraftEdge edge) {
            return new EdgeSignature(
                    normalizedEdgeValue(edge.kind()),
                    normalizedEdgeValue(edge.source().nodeId()),
                    normalizedEdgeValue(edge.source().port()),
                    normalizePath(edge.source().path()),
                    normalizedEdgeValue(edge.target().nodeId()),
                    normalizedEdgeValue(edge.target().port()),
                    normalizePath(edge.target().path()),
                    "route".equals(edge.kind()) ? normalizedRouteCondition(edge.condition()) : ""
            );
        }

        private String label() {
            return "%s:%s.%s.%s -> %s.%s.%s%s".formatted(
                    kind,
                    sourceNodeId,
                    sourcePort,
                    sourcePath,
                    targetNodeId,
                    targetPort,
                    targetPath,
                    routeCondition.isBlank() ? "" : " when " + routeCondition
            );
        }
    }

    private record CanvasConnection(
            String sourceNodeId,
            String sourcePort,
            String sourcePath,
            String targetNodeId,
            String targetPort,
            String targetPath
    ) {
    }

    private record ValidationOptions(boolean requireEdgeBindingConsistency) {
    }

    private static void validateConfig(GraphDraft.DraftNode node,
                                       OperatorDefinition operator,
                                       String nodePath,
                                       List<VisualDiagnostic> diagnostics) {
        validateConfigValue(node.config(), operator.configSchema().schema(), nodePath + "/config", diagnostics);
    }

    private static void validateConfigValue(Object value,
                                            Map<String, Object> schema,
                                            String path,
                                            List<VisualDiagnostic> diagnostics) {
        if (value instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if ("constant".equals(kind)) {
                validateConfigValue(map.get("value"), schema, path + "/value", diagnostics);
                return;
            }
            if ("expression".equals(kind)) {
                return;
            }
            if ("objectTemplate".equals(kind) && map.get("fields") instanceof Map<?, ?> fields) {
                validateConfigObjectTemplate(fields, schema, path, diagnostics);
                return;
            }
        }
        String type = schemaType(schema);
        if (type.isBlank() || "any".equals(type) || "opaque".equals(type)) {
            return;
        }
        if (value == null && schemaAllowsNull(schema)) {
            validateConfigEnum(value, schema, path, diagnostics);
            return;
        }
        if ("object".equals(type)) {
            validateConfigObject(value, schema, path, diagnostics);
            return;
        }
        if ("array".equals(type)) {
            validateConfigArray(value, schema, path, diagnostics);
            return;
        }
        if ("enum".equals(type)) {
            validateConfigEnum(value, schema, path, diagnostics);
            return;
        }
        if (!configValueMatchesType(value, type)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Config value at '%s' must be %s.".formatted(path, schemaTypeLabel(schema)),
                    path));
            return;
        }
	        if (!numericValueMatchesBounds(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s numeric bounds."
	                            .formatted(path, schemaTypeLabel(schema)),
	                    path));
	            return;
	        }
	        if (!numericValueMatchesMultipleOf(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s numeric multipleOf constraint."
	                            .formatted(path, schemaTypeLabel(schema)),
	                    path));
	            return;
	        }
	        if (!stringValueMatchesLengthBounds(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s string length constraints."
	                            .formatted(path, schemaTypeLabel(schema)),
	                    path));
	            return;
	        }
		        if (!stringValueMatchesPattern(value, schema)) {
		            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
		                    "Config value at '%s' must satisfy %s string pattern constraint."
		                            .formatted(path, schemaTypeLabel(schema)),
		                    path));
		            return;
		        }
	        if (!stringValueMatchesFormat(value, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s string format constraint."
	                            .formatted(path, schemaTypeLabel(schema)),
	                    path));
	            return;
	        }
		        validateConfigEnum(value, schema, path, diagnostics);
		    }

    private static void validateConfigObjectTemplate(Map<?, ?> fields,
                                                     Map<String, Object> schema,
                                                     String path,
                                                     List<VisualDiagnostic> diagnostics) {
        String type = schemaType(schema);
        if (type.isBlank() || "any".equals(type) || "opaque".equals(type)) {
            return;
        }
        if (!"object".equals(type)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Config value at '%s' must be %s.".formatted(path, schemaTypeLabel(schema)),
                    path));
            return;
        }

	        Map<String, Object> object = new LinkedHashMap<>();
	        fields.forEach((key, item) -> object.put(String.valueOf(key), item));
	        if (!objectValueMatchesPropertyBounds(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object property count constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesPropertyNames(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object propertyNames constraint.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesPatternProperties(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object patternProperties constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesDependentRequired(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object dependentRequired constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesDependentSchemas(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object dependentSchemas constraints.".formatted(path),
	                    path));
	            return;
	        }
	        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                diagnostics.add(VisualDiagnostic.error("visual.config.required",
                        "Required config '%s' is missing.".formatted(required),
                        path + "/fields/" + required));
            }
        }
        Object residual = residualPropertiesPolicy(schema);
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/fields/" + entry.getKey(), diagnostics);
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                validateConfigValue(entry.getValue(), patternSchema, path + "/fields/" + entry.getKey(), diagnostics);
            }
            if (property != null || !patternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(residual)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/fields/" + entry.getKey()));
            } else if (residual instanceof Map<?, ?> residualSchema) {
                validateConfigValue(entry.getValue(), objectProperty(residualSchema),
                        path + "/fields/" + entry.getKey(), diagnostics);
            }
        }
    }

    private static void validateConfigObject(Object value,
                                             Map<String, Object> schema,
                                             String path,
                                             List<VisualDiagnostic> diagnostics) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Config value at '%s' must be object.".formatted(path),
                    path));
            return;
        }
	        Map<String, Object> object = new LinkedHashMap<>();
	        rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));
	        if (!objectValueMatchesPropertyBounds(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object property count constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesPropertyNames(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object propertyNames constraint.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesPatternProperties(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object patternProperties constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesDependentRequired(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object dependentRequired constraints.".formatted(path),
	                    path));
	            return;
	        }
	        if (!objectValueMatchesDependentSchemas(object, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy object dependentSchemas constraints.".formatted(path),
	                    path));
	            return;
	        }
	        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                diagnostics.add(VisualDiagnostic.error("visual.config.required",
                        "Required config '%s' is missing.".formatted(required),
                        path + "/" + required));
            }
        }
        Object residual = residualPropertiesPolicy(schema);
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            List<Map<String, Object>> patternSchemas = matchingPatternPropertySchemas(schema, entry.getKey());
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/" + entry.getKey(), diagnostics);
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                validateConfigValue(entry.getValue(), patternSchema, path + "/" + entry.getKey(), diagnostics);
            }
            if (property != null || !patternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(residual)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/" + entry.getKey()));
            } else if (residual instanceof Map<?, ?> residualSchema) {
                validateConfigValue(entry.getValue(), objectProperty(residualSchema),
                        path + "/" + entry.getKey(), diagnostics);
            }
        }
    }

    private static void validateConfigArray(Object value,
                                            Map<String, Object> schema,
                                            String path,
                                            List<VisualDiagnostic> diagnostics) {
	        if (!(value instanceof List<?> list)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
	                    "Config value at '%s' must be array.".formatted(path),
	                    path));
	            return;
	        }
	        if (!arrayValueMatchesItemBounds(list, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy array item count constraints."
	                            .formatted(path),
	                    path));
	            return;
	        }
	        if (!arrayValueMatchesUniqueItems(list, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy array uniqueItems constraint."
	                            .formatted(path),
	                    path));
	            return;
	        }
	        if (!arrayValueMatchesContains(list, schema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy array contains constraints."
	                            .formatted(path),
	                    path));
	            return;
	        }
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> itemSchema = arrayItemSchemaForIndex(schema, i);
            if (itemSchema != null) {
                validateConfigValue(list.get(i), itemSchema, path + "/" + i, diagnostics);
            }
        }
    }

    private static void validateConfigEnum(Object value,
                                           Map<String, Object> schema,
                                           String path,
                                           List<VisualDiagnostic> diagnostics) {
        List<Object> values = enumValues(schema);
        if (values.isEmpty()) {
            return;
        }
        if (!values.contains(value)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.enumMismatch",
                    "Config value at '%s' must be one of %s.".formatted(path, values),
                    path));
        }
    }

    private static boolean configValueMatchesType(Object value, String type) {
        return switch (type) {
            case "string", "duration", "datetime" -> value instanceof String;
            case "integer" -> isIntegerValue(value);
            case "number", "decimal" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static boolean schemaAllowsNull(Map<String, Object> schema) {
        Object raw = schema.containsKey("kind") ? schema.get("kind") : schema.get("type");
        if (raw instanceof List<?> types) {
            return types.stream().anyMatch(type -> "null".equals(type));
        }
        if ("null".equals(raw)) {
            return true;
        }
        return raw == null && schema.containsKey("const") && schema.get("const") == null;
    }

    private static boolean isIntegerValue(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            return Double.isFinite(doubleValue) && Math.rint(doubleValue) == doubleValue;
        }
        return false;
    }

	    private static boolean numericValueMatchesBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Number number)) {
	            return true;
        }
        double numericValue = number.doubleValue();
        NumericBoundary lower = lowerBound(schema);
        if (lower != null && !lower.acceptsLower(numericValue)) {
            return false;
        }
	        NumericBoundary upper = upperBound(schema);
	        return upper == null || upper.acceptsUpper(numericValue);
	    }

	    private static boolean numericValueMatchesMultipleOf(Object value, Map<String, Object> schema) {
	        if (!(value instanceof Number number)) {
	            return true;
	        }
	        Double multipleOf = numericMultipleOf(schema.get("multipleOf"));
	        return multipleOf == null || numericValueIsMultipleOf(number.doubleValue(), multipleOf);
	    }

	    private static boolean stringValueMatchesLengthBounds(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        long length = string.codePoints().count();
	        Long minimum = stringLengthBoundary(schema.get("minLength"));
	        if (minimum != null && length < minimum) {
	            return false;
	        }
	        Long maximum = stringLengthBoundary(schema.get("maxLength"));
	        return maximum == null || length <= maximum;
	    }

		    private static boolean stringValueMatchesPattern(Object value, Map<String, Object> schema) {
		        if (!(value instanceof String string)) {
		            return true;
		        }
		        Object rawPattern = schema.get("pattern");
	        if (!(rawPattern instanceof String pattern)) {
	            return true;
	        }
	        try {
	            return Pattern.compile(pattern).matcher(string).find();
	        } catch (PatternSyntaxException ex) {
	            return true;
		        }
		    }

	    private static boolean stringValueMatchesFormat(Object value, Map<String, Object> schema) {
	        if (!(value instanceof String string)) {
	            return true;
	        }
	        String format = stringFormat(schema);
	        return format == null || stringMatchesFormat(string, format);
	    }

	    private static String stringFormat(Map<String, Object> schema) {
	        Object rawFormat = schema.get("format");
	        return rawFormat instanceof String format && SUPPORTED_STRING_FORMATS.contains(format) ? format : null;
	    }

	    private static boolean stringMatchesFormat(String value, String format) {
	        try {
	            switch (format) {
	                case "date" -> LocalDate.parse(value);
	                case "date-time" -> OffsetDateTime.parse(value);
	                case "duration" -> Duration.parse(value);
	                case "email" -> {
	                    return EMAIL_PATTERN.matcher(value).matches();
	                }
	                case "uri" -> {
	                    URI uri = new URI(value);
	                    return uri.isAbsolute();
	                }
	                case "uuid" -> UUID.fromString(value);
	                default -> {
	                    return true;
	                }
	            }
	            return true;
	        } catch (DateTimeParseException | IllegalArgumentException | URISyntaxException ex) {
	            return false;
	        }
	    }

		    private static boolean arrayValueMatchesItemBounds(List<?> value, Map<String, Object> schema) {
	        long size = value.size();
	        Long minimum = arrayItemBoundary(schema.get("minItems"));
	        if (minimum != null && size < minimum) {
	            return false;
	        }
	        Long maximum = arrayItemBoundary(schema.get("maxItems"));
	        return maximum == null || size <= maximum;
	    }

	    private static boolean arrayValueMatchesUniqueItems(List<?> value, Map<String, Object> schema) {
	        return !Boolean.TRUE.equals(schema.get("uniqueItems")) || new HashSet<>(value).size() == value.size();
	    }

	    private static boolean arrayValueMatchesContains(List<?> value, Map<String, Object> schema) {
	        Map<String, Object> contains = objectProperty(schema.get("contains"));
	        if (contains == null) {
	            return true;
	        }
	        long matches = value.stream()
	                .filter(item -> constantValueMatchesSchema(item, contains))
	                .count();
	        Long minimum = arrayMinContains(schema);
	        if (minimum != null && matches < minimum) {
	            return false;
	        }
	        Long maximum = arrayMaxContains(schema);
	        return maximum == null || matches <= maximum;
	    }

	    private static Map<String, Object> arrayItemSchemaForIndex(Map<String, Object> schema, int index) {
	        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
	        if (index < prefixItems.size()) {
	            return prefixItems.get(index);
	        }
	        return objectProperty(schema.get("items"));
	    }

	    private static List<Map<String, Object>> prefixItemsOf(Map<String, Object> schema) {
	        Object raw = schema.get("prefixItems");
	        if (!(raw instanceof List<?> values)) {
	            return List.of();
	        }
	        List<Map<String, Object>> prefixItems = new ArrayList<>();
	        for (Object value : values) {
	            Map<String, Object> itemSchema = objectProperty(value);
	            if (itemSchema != null) {
	                prefixItems.add(itemSchema);
	            }
	        }
	        return prefixItems;
	    }

	    private static Long arrayMinContains(Map<String, Object> schema) {
	        if (!schema.containsKey("contains")) {
	            return null;
	        }
	        Long explicit = arrayItemBoundary(schema.get("minContains"));
	        return explicit == null ? 1L : explicit;
	    }

	    private static Long arrayMaxContains(Map<String, Object> schema) {
	        return arrayItemBoundary(schema.get("maxContains"));
	    }

	    private static Long stringLengthBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
	    }

		    private static Long arrayItemBoundary(Object value) {
		        if (!(value instanceof Number number)) {
		            return null;
		        }
		        double numericValue = number.doubleValue();
		        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
		            return null;
		        }
		        return (long) numericValue;
		    }

	    private static Long objectPropertyBoundary(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        if (!Double.isFinite(numericValue) || Math.rint(numericValue) != numericValue || numericValue < 0) {
	            return null;
	        }
	        return (long) numericValue;
	    }

	    private static NumericBoundary lowerBound(Map<String, Object> schema) {
        NumericBoundary minimum = numericBoundary(schema.get("minimum"), false);
        NumericBoundary exclusiveMinimum = numericBoundary(schema.get("exclusiveMinimum"), true);
        if (minimum == null) {
            return exclusiveMinimum;
        }
        if (exclusiveMinimum == null) {
            return minimum;
        }
        int comparison = Double.compare(minimum.value(), exclusiveMinimum.value());
        if (comparison > 0) {
            return minimum;
        }
        if (comparison < 0) {
            return exclusiveMinimum;
        }
        return exclusiveMinimum.exclusive() ? exclusiveMinimum : minimum;
    }

    private static NumericBoundary upperBound(Map<String, Object> schema) {
        NumericBoundary maximum = numericBoundary(schema.get("maximum"), false);
        NumericBoundary exclusiveMaximum = numericBoundary(schema.get("exclusiveMaximum"), true);
        if (maximum == null) {
            return exclusiveMaximum;
        }
        if (exclusiveMaximum == null) {
            return maximum;
        }
        int comparison = Double.compare(maximum.value(), exclusiveMaximum.value());
        if (comparison < 0) {
            return maximum;
        }
        if (comparison > 0) {
            return exclusiveMaximum;
        }
        return exclusiveMaximum.exclusive() ? exclusiveMaximum : maximum;
    }

	    private static NumericBoundary numericBoundary(Object value, boolean exclusive) {
	        if (!(value instanceof Number number)) {
	            return null;
        }
        double numericValue = number.doubleValue();
	        return Double.isFinite(numericValue) ? new NumericBoundary(numericValue, exclusive) : null;
	    }

	    private static Double numericMultipleOf(Object value) {
	        if (!(value instanceof Number number)) {
	            return null;
	        }
	        double numericValue = number.doubleValue();
	        return Double.isFinite(numericValue) && numericValue > 0 ? numericValue : null;
	    }

	    private static boolean numericValueIsMultipleOf(double value, double multipleOf) {
	        if (!Double.isFinite(value) || !Double.isFinite(multipleOf) || multipleOf <= 0) {
	            return true;
	        }
	        double quotient = value / multipleOf;
	        double nearest = Math.rint(quotient);
	        double tolerance = 1.0e-9 * Math.max(1.0, Math.abs(quotient));
	        return Math.abs(quotient - nearest) <= tolerance;
	    }

    private record NumericBoundary(double value, boolean exclusive) {

        private boolean acceptsLower(double candidate) {
            return exclusive ? candidate > value : candidate >= value;
        }

        private boolean acceptsUpper(double candidate) {
            return exclusive ? candidate < value : candidate <= value;
        }
    }

    private static void validateDataEdgeBindingConsistency(GraphDraft draft,
                                                           Map<String, GraphDraft.DraftNode> nodesById,
                                                           Map<String, OperatorDefinition> operatorsByNodeId,
                                                           List<VisualDiagnostic> diagnostics) {
        Map<CanvasConnection, String> edgePaths = new LinkedHashMap<>();
        for (int i = 0; i < draft.edges().size(); i++) {
            GraphDraft.DraftEdge edge = draft.edges().get(i);
            if (!"data".equals(edge.kind())) {
                continue;
            }
            String edgePath = "/edges/" + i;
            edgeConnection(edge, nodesById, operatorsByNodeId)
                    .ifPresent(connection -> edgePaths.putIfAbsent(connection, edgePath));
        }

        Set<CanvasConnection> semanticConnections = new HashSet<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            OperatorDefinition operator = operatorsByNodeId.get(node.id());
            if (operator == null) {
                continue;
            }
            int nodeIndex = i;
            node.inputs().forEach((inputKey, binding) -> collectNodePathBindingConnections(
                    node,
                    operator,
                    inputKey,
                    binding,
                    "/nodes/" + nodeIndex + "/inputs/" + inputKey,
                    nodesById,
                    operatorsByNodeId,
                    edgePaths,
                    semanticConnections,
                    diagnostics));
            collectConfigReferenceConnections(node, operator, nodesById, operatorsByNodeId, semanticConnections);
        }

        edgePaths.forEach((connection, edgePath) -> {
            if (!semanticConnections.contains(connection)) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.bindingMissing",
                        "Data edge %s must match a nodePath binding or expression reference on target node '%s'."
                                .formatted(connectionLabel(connection), connection.targetNodeId()),
                        edgePath));
            }
        });
    }

    private static void collectNodePathBindingConnections(GraphDraft.DraftNode targetNode,
                                                          OperatorDefinition targetOperator,
                                                          String inputKey,
                                                          GraphDraft.Binding binding,
                                                          String bindingPath,
                                                          Map<String, GraphDraft.DraftNode> nodesById,
                                                          Map<String, OperatorDefinition> operatorsByNodeId,
                                                          Map<CanvasConnection, String> edgePaths,
                                                          Set<CanvasConnection> semanticConnections,
                                                          List<VisualDiagnostic> diagnostics) {
        String inputName = targetInputName(inputKey, binding);
        if ("objectTemplate".equals(binding.kind())) {
            binding.fields().forEach((key, nested) -> {
                String nestedInputName = inputName.isBlank() ? key : inputName + "." + key;
                collectNodePathBindingConnections(targetNode, targetOperator, nestedInputName, nested,
                        bindingPath + "/" + key, nodesById, operatorsByNodeId, edgePaths, semanticConnections,
                        diagnostics);
            });
            return;
        }
        if (!"nodePath".equals(binding.kind())) {
            return;
        }

        Optional<CanvasConnection> connection = bindingConnection(targetNode, targetOperator, inputName, binding,
                nodesById, operatorsByNodeId);
        if (connection.isEmpty()) {
            return;
        }
        semanticConnections.add(connection.get());
        if (!edgePaths.containsKey(connection.get())) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.edgeMissing",
                    "NodePath binding %s must be represented by a matching data edge."
                            .formatted(connectionLabel(connection.get())),
                    bindingPath));
        }
    }

    private static void collectConfigReferenceConnections(GraphDraft.DraftNode targetNode,
                                                          OperatorDefinition targetOperator,
                                                          Map<String, GraphDraft.DraftNode> nodesById,
                                                          Map<String, OperatorDefinition> operatorsByNodeId,
                                                          Set<CanvasConnection> connections) {
        collectConfigReferenceConnections(targetNode, targetOperator, targetNode.config(), "",
                nodesById, operatorsByNodeId, connections);
    }

    private static void collectConfigReferenceConnections(GraphDraft.DraftNode targetNode,
                                                          OperatorDefinition targetOperator,
                                                          Object value,
                                                          String configPath,
                                                          Map<String, GraphDraft.DraftNode> nodesById,
                                                          Map<String, OperatorDefinition> operatorsByNodeId,
                                                          Set<CanvasConnection> connections) {
        if (value instanceof String expression) {
            if (looksLikeReferenceExpression(expression)) {
                collectExpressionConnections(expression, targetNode, targetOperator, configTargetPath(configPath),
                        nodesById, operatorsByNodeId, connections);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if ("expression".equals(map.get("kind"))) {
                Object expression = map.get("expr");
                collectExpressionConnections(expression == null ? "" : String.valueOf(expression),
                        targetNode, targetOperator, configTargetPath(configPath), nodesById, operatorsByNodeId,
                        connections);
                return;
            }
            if ("objectTemplate".equals(map.get("kind")) && map.get("fields") instanceof Map<?, ?> fields) {
                fields.forEach((key, item) -> collectConfigReferenceConnections(targetNode, targetOperator, item,
                        appendPath(configPath, "fields." + key), nodesById, operatorsByNodeId, connections));
                return;
            }
            map.forEach((key, item) -> collectConfigReferenceConnections(targetNode, targetOperator, item,
                    appendPath(configPath, String.valueOf(key)), nodesById, operatorsByNodeId, connections));
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectConfigReferenceConnections(targetNode, targetOperator, list.get(i),
                        appendPath(configPath, String.valueOf(i)), nodesById, operatorsByNodeId, connections);
            }
        }
    }

    private static void collectExpressionConnections(String expression,
                                                     GraphDraft.DraftNode targetNode,
                                                     OperatorDefinition targetOperator,
                                                     String targetPath,
                                                     Map<String, GraphDraft.DraftNode> nodesById,
                                                     Map<String, OperatorDefinition> operatorsByNodeId,
                                                     Set<CanvasConnection> connections) {
        if (expression == null || expression.isBlank() || targetPath.isBlank()) {
            return;
        }
        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, "", targetPath);
        if (targetPort.isEmpty() || propertyAtPath(targetPort.get().schema(), targetPath) == null) {
            return;
        }

        Matcher matcher = NODE_REFERENCE.matcher(withoutQuotedStrings(expression));
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String outputPath = matcher.group(2) == null ? "" : matcher.group(2);
            OperatorDefinition sourceOperator = operatorsByNodeId.get(nodeId);
            if (!nodesById.containsKey(nodeId) || sourceOperator == null) {
                continue;
            }
            OutputReference outputReference = outputReference(sourceOperator, outputPath);
            Optional<OperatorDefinition.Port> sourcePort = resolveOutputPort(sourceOperator, outputReference.port());
            if (sourcePort.isEmpty()
                    || propertyAtPath(sourcePort.get().schema(), outputReference.path()) == null) {
                continue;
            }
            connections.add(new CanvasConnection(
                    nodeId,
                    sourcePort.get().name(),
                    normalizePath(outputReference.path()),
                    targetNode.id(),
                    targetPort.get().name(),
                    normalizePath(targetPath)
            ));
        }
    }

    private static String appendPath(String prefix, String segment) {
        return prefix.isBlank() ? segment : prefix + "." + segment;
    }

    private static String configTargetPath(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return "";
        }
        String[] segments = configPath.split("\\.");
        return segments.length == 0 ? "" : segments[segments.length - 1];
    }

    private static Optional<CanvasConnection> edgeConnection(GraphDraft.DraftEdge edge,
                                                            Map<String, GraphDraft.DraftNode> nodesById,
                                                            Map<String, OperatorDefinition> operatorsByNodeId) {
        if (!nodesById.containsKey(edge.source().nodeId()) || !nodesById.containsKey(edge.target().nodeId())) {
            return Optional.empty();
        }
        OperatorDefinition sourceOperator = operatorsByNodeId.get(edge.source().nodeId());
        OperatorDefinition targetOperator = operatorsByNodeId.get(edge.target().nodeId());
        if (sourceOperator == null || targetOperator == null) {
            return Optional.empty();
        }
        Optional<OperatorDefinition.Port> sourcePort = findPort(sourceOperator.ports().outputs(),
                edge.source().port());
        Optional<OperatorDefinition.Port> targetPort = findPort(targetOperator.ports().inputs(),
                edge.target().port());
        if (sourcePort.isEmpty() || targetPort.isEmpty()
                || propertyAtPath(sourcePort.get().schema(), edge.source().path()) == null
                || propertyAtPath(targetPort.get().schema(), edge.target().path()) == null) {
            return Optional.empty();
        }
        return Optional.of(new CanvasConnection(
                edge.source().nodeId(),
                sourcePort.get().name(),
                normalizePath(edge.source().path()),
                edge.target().nodeId(),
                targetPort.get().name(),
                normalizePath(edge.target().path())
        ));
    }

    private static Optional<CanvasConnection> bindingConnection(GraphDraft.DraftNode targetNode,
                                                               OperatorDefinition targetOperator,
                                                               String inputName,
                                                               GraphDraft.Binding binding,
                                                               Map<String, GraphDraft.DraftNode> nodesById,
                                                               Map<String, OperatorDefinition> operatorsByNodeId) {
        if (!nodesById.containsKey(binding.nodeId())) {
            return Optional.empty();
        }
        OperatorDefinition sourceOperator = operatorsByNodeId.get(binding.nodeId());
        if (sourceOperator == null) {
            return Optional.empty();
        }
        Optional<OperatorDefinition.Port> sourcePort = resolveOutputPort(sourceOperator, binding.sourcePort());
        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        if (sourcePort.isEmpty() || targetPort.isEmpty()
                || propertyAtPath(sourcePort.get().schema(), binding.path()) == null
                || propertyAtPath(targetPort.get().schema(), inputName) == null) {
            return Optional.empty();
        }
        return Optional.of(new CanvasConnection(
                binding.nodeId(),
                sourcePort.get().name(),
                normalizePath(binding.path()),
                targetNode.id(),
                targetPort.get().name(),
                normalizePath(inputName)
        ));
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.trim();
    }

    private static String normalizedEdgeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, Object> configChildSchema(Map<String, Object> schema, String key) {
        if (schema == null || schema.isEmpty()) {
            return Map.of();
        }
        String type = schemaType(schema);
        if ("object".equals(type) || schema.containsKey("properties")) {
            Map<String, Object> property = objectProperty(propertiesOf(schema).get(key));
            if (property != null) {
                return property;
            }
            Object residual = residualPropertiesPolicy(schema);
            if (Boolean.TRUE.equals(residual)) {
                return Map.of();
            }
            if (residual instanceof Map<?, ?> residualSchema) {
                Map<String, Object> residualProperty = objectProperty(residualSchema);
                return residualProperty == null ? Map.of() : residualProperty;
            }
        }
        if ("array".equals(type) && schema.get("items") instanceof Map<?, ?> items) {
            Map<String, Object> itemSchema = objectProperty(items);
            return itemSchema == null ? Map.of() : itemSchema;
        }
        return Map.of();
    }

    private static String connectionLabel(CanvasConnection connection) {
        return "'%s.%s.%s -> %s.%s.%s'".formatted(
                connection.sourceNodeId(),
                connection.sourcePort(),
                connection.sourcePath(),
                connection.targetNodeId(),
                connection.targetPort(),
                connection.targetPath());
    }

    private static Object residualPropertiesPolicy(Map<String, Object> schema) {
        if (schema.containsKey("additionalProperties")) {
            return schema.get("additionalProperties");
        }
        return schema.get("unevaluatedProperties");
    }

    private static void validateAcyclic(GraphDraft draft,
                                        Map<String, GraphDraft.DraftNode> nodesById,
                                        List<VisualDiagnostic> diagnostics) {
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        draft.nodes().forEach(node -> {
            outgoing.put(node.id(), new HashSet<>());
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
        List<String> ready = new ArrayList<>();
        indegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                ready.add(nodeId);
            }
        });
        int visited = 0;
        for (int index = 0; index < ready.size(); index++) {
            String nodeId = ready.get(index);
            visited++;
            for (String target : outgoing.get(nodeId)) {
                int degree = indegree.compute(target, (ignored, current) -> current == null ? 0 : current - 1);
                if (degree == 0) {
                    ready.add(target);
                }
            }
        }
        if (visited != nodesById.size()) {
            diagnostics.add(VisualDiagnostic.error("visual.edge.cycle",
                    "Visual graph dependencies must form an acyclic dataflow graph.",
                    "/edges"));
        }
    }

    private record InputTarget(String portName, String path) {

        private boolean overlaps(InputTarget other) {
            if (!portName.equals(other.portName)) {
                return false;
            }
            if (path.isBlank() || other.path.isBlank()) {
                return true;
            }
            return path.equals(other.path)
                    || path.startsWith(other.path + ".")
                    || other.path.startsWith(path + ".");
        }

        private String label() {
            if (path.isBlank()) {
                return portName;
            }
            if (portName.isBlank()) {
                return path;
            }
            return portName + "." + path;
        }
    }
}
