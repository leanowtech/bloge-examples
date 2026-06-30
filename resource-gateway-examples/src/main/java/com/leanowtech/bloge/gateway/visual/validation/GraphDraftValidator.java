package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencies;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Set<String> SUPPORTED_EDGE_KINDS = Set.of("data");
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
        if (output.path().isBlank()) {
            return;
        }

        OperatorDefinition operator = operatorsByNodeId.get(output.nodeId());
        if (operator == null) {
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
                    .map(GraphDraftValidator::compatibilityReason)
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
            current = objectProperty(properties.get(segment));
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
        if (type == null && property.containsKey("properties")) {
            return "object";
        }
        if (type == null && property.containsKey("items")) {
            return "array";
        }
        return type == null ? "" : String.valueOf(type);
    }

    private static String schemaTypeLabel(Map<String, Object> schema) {
        List<Object> enumValues = enumValues(schema);
        if (!enumValues.isEmpty()) {
            return "enum<" + String.join("|", enumValues.stream().map(String::valueOf).toList()) + ">";
        }
        String type = schemaType(schema);
        if ("array".equals(type)) {
            Map<String, Object> items = objectProperty(schema.get("items"));
            return items == null ? "array" : "array<" + schemaTypeLabel(items) + ">";
        }
        return type.isBlank() ? "unknown" : type;
    }

    private static boolean schemasCompatible(Map<String, Object> sourceSchema, Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema).isEmpty();
    }

    private static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                             Map<String, Object> targetSchema) {
        return schemaCompatibilityIssue(sourceSchema, targetSchema, "");
    }

    private static Optional<String> schemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                             Map<String, Object> targetSchema,
                                                             String path) {
        String sourceType = schemaType(sourceSchema);
        String targetType = schemaType(targetSchema);
        if (sourceType.isBlank() || targetType.isBlank()
                || "any".equals(sourceType) || "any".equals(targetType)
                || "opaque".equals(sourceType) || "opaque".equals(targetType)) {
            return Optional.empty();
        }
        if ("array".equals(sourceType) && "array".equals(targetType)) {
            Map<String, Object> sourceItems = objectProperty(sourceSchema.get("items"));
            Map<String, Object> targetItems = objectProperty(targetSchema.get("items"));
            return sourceItems == null || targetItems == null
                    ? Optional.empty()
                    : schemaCompatibilityIssue(sourceItems, targetItems, appendCompatibilityPath(path, "items"));
        }
        if ("object".equals(sourceType) && "object".equals(targetType)) {
            return objectSchemaCompatibilityIssue(sourceSchema, targetSchema, path);
        }
        List<Object> targetEnumValues = enumValues(targetSchema);
        if (!targetEnumValues.isEmpty()) {
            List<Object> sourceEnumValues = enumValues(sourceSchema);
            if (sourceEnumValues.isEmpty()) {
                return Optional.of(reasonAt(path,
                        "target enum %s requires a finite source enum domain, but source is %s"
                                .formatted(valueDomainLabel(targetEnumValues), schemaTypeLabel(sourceSchema))));
            }
            List<Object> outside = sourceEnumValues.stream()
                    .filter(value -> !targetEnumValues.contains(value))
                    .toList();
            if (!outside.isEmpty()) {
                return Optional.of(reasonAt(path,
                        "source enum value(s) %s are outside target enum %s"
                                .formatted(valueDomainLabel(outside), valueDomainLabel(targetEnumValues))));
            }
            return Optional.empty();
        }
        if ("enum".equals(sourceType)) {
            List<Object> sourceEnumValues = enumValues(sourceSchema);
            if (sourceEnumValues.isEmpty()) {
                return Optional.empty();
            }
            List<Object> incompatible = sourceEnumValues.stream()
                    .filter(value -> !configValueMatchesType(value, targetType))
                    .toList();
            if (!incompatible.isEmpty()) {
                return Optional.of(reasonAt(path,
                        "source enum value(s) %s do not match target type %s"
                                .formatted(valueDomainLabel(incompatible), targetType)));
            }
            return Optional.empty();
        }
        if (sourceType.equals(targetType)) {
            return Optional.empty();
        }
        return numeric(sourceType) && numeric(targetType)
                ? Optional.empty()
                : Optional.of(reasonAt(path,
                "source type %s cannot feed target type %s"
                        .formatted(schemaTypeLabel(sourceSchema), schemaTypeLabel(targetSchema))));
    }

    private static Optional<String> objectSchemaCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                   Map<String, Object> targetSchema,
                                                                   String path) {
        Map<String, Object> sourceProperties = propertiesOf(sourceSchema);
        Map<String, Object> targetProperties = propertiesOf(targetSchema);
        Set<String> sourceRequired = new HashSet<>(requiredNamesOf(sourceSchema));
        for (String required : requiredNamesOf(targetSchema)) {
            String childPath = appendCompatibilityPath(path, required);
            Map<String, Object> sourceProperty = objectProperty(sourceProperties.get(required));
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(required));
            if (sourceProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "source object does not declare required field '%s'".formatted(required)));
            }
            if (targetProperty == null) {
                return Optional.of(reasonAt(childPath,
                        "target schema requires undeclared field '%s'".formatted(required)));
            }
            if (!sourceRequired.contains(required)) {
                return Optional.of(reasonAt(childPath,
                        "source object does not guarantee required field '%s'".formatted(required)));
            }
            Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
            if (nested.isPresent()) {
                return nested;
            }
        }
        Object targetAdditional = targetSchema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : sourceProperties.entrySet()) {
            String propertyName = entry.getKey();
            String childPath = appendCompatibilityPath(path, propertyName);
            Map<String, Object> sourceProperty = objectProperty(entry.getValue());
            if (sourceProperty == null) {
                continue;
            }
            Map<String, Object> targetProperty = objectProperty(targetProperties.get(propertyName));
            if (targetProperty != null) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, targetProperty, childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            } else if (Boolean.FALSE.equals(targetAdditional)) {
                return Optional.of(reasonAt(childPath,
                        "source object declares additional field '%s' but target additionalProperties=false"
                                .formatted(propertyName)));
            } else if (targetAdditional instanceof Map<?, ?> additionalSchema) {
                Optional<String> nested = schemaCompatibilityIssue(sourceProperty, objectProperty(additionalSchema),
                        childPath);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        Optional<String> additionalPolicyIssue = additionalPropertiesCompatibilityIssue(sourceSchema, targetAdditional,
                path);
        if (additionalPolicyIssue.isPresent()) {
            return additionalPolicyIssue;
        }
        return Optional.empty();
    }

    private static Optional<String> additionalPropertiesCompatibilityIssue(Map<String, Object> sourceSchema,
                                                                           Object targetAdditional,
                                                                           String path) {
        Object sourceAdditional = sourceSchema.get("additionalProperties");
        if (Boolean.FALSE.equals(targetAdditional)) {
            return Boolean.FALSE.equals(sourceAdditional)
                    ? Optional.empty()
                    : Optional.of(reasonAt(path,
                    "source object allows undeclared additional fields but target additionalProperties=false"));
        }
        if (targetAdditional instanceof Map<?, ?> targetAdditionalSchema) {
            if (sourceAdditional == null || Boolean.TRUE.equals(sourceAdditional)) {
                return Optional.of(reasonAt(path,
                        "source object allows unconstrained additional fields but target additionalProperties requires %s"
                                .formatted(schemaTypeLabel(objectProperty(targetAdditionalSchema)))));
            }
            if (sourceAdditional instanceof Map<?, ?> sourceAdditionalSchema) {
                return schemaCompatibilityIssue(objectProperty(sourceAdditionalSchema),
                        objectProperty(targetAdditionalSchema), appendCompatibilityPath(path, "additionalProperties"));
            }
        }
        return Optional.empty();
    }

    private static String appendCompatibilityPath(String path, String segment) {
        if (path == null || path.isBlank()) {
            return segment;
        }
        return path + "." + segment;
    }

    private static String reasonAt(String path, String reason) {
        return path == null || path.isBlank() ? reason : "at '%s': %s".formatted(path, reason);
    }

    private static String compatibilityReason(String reason) {
        return reason == null || reason.isBlank() ? "" : " Reason: " + reason + ".";
    }

    private static String valueDomainLabel(List<Object> values) {
        return values.stream().map(String::valueOf).toList().toString();
    }

    private static List<Object> enumValues(Map<String, Object> schema) {
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        if ("enum".equals(schemaType(schema)) && schema.get("values") instanceof List<?> values) {
            return values.stream().map(Object.class::cast).distinct().toList();
        }
        return List.of();
    }

    private static boolean numeric(String type) {
        return "number".equals(type) || "integer".equals(type) || "decimal".equals(type);
    }

    private static void validateEdges(GraphDraft draft,
                                      Map<String, GraphDraft.DraftNode> nodesById,
                                      Map<String, OperatorDefinition> operatorsByNodeId,
                                      List<VisualDiagnostic> diagnostics) {
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
        Object additional = schema.get("additionalProperties");
        if (Boolean.TRUE.equals(additional)) {
            return Map.of();
        }
        if (additional instanceof Map<?, ?> additionalSchema) {
            return objectProperty(additionalSchema);
        }
        return null;
    }

    private static boolean constantValueMatchesSchema(Object value, Map<String, Object> schema) {
        Object rawEnum = schema.get("enum");
        if (rawEnum instanceof List<?> values && !values.contains(value)) {
            return false;
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
        return configValueMatchesType(value, type);
    }

    private static boolean constantObjectMatchesSchema(Object value, Map<String, Object> schema) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            return false;
        }
        Map<String, Object> object = new LinkedHashMap<>();
        rawMap.forEach((key, item) -> object.put(String.valueOf(key), item));

        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                return false;
            }
        }

        Map<String, Object> properties = propertiesOf(schema);
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            if (property != null) {
                if (!constantValueMatchesSchema(entry.getValue(), property)) {
                    return false;
                }
            } else if (Boolean.FALSE.equals(additional)) {
                return false;
            } else if (additional instanceof Map<?, ?> additionalSchema
                    && !constantValueMatchesSchema(entry.getValue(), objectProperty(additionalSchema))) {
                return false;
            }
        }
        return true;
    }

    private static boolean constantArrayMatchesSchema(Object value, Map<String, Object> schema) {
        if (!(value instanceof List<?> list)) {
            return false;
        }
        Map<String, Object> items = objectProperty(schema.get("items"));
        return items == null || list.stream().allMatch(item -> constantValueMatchesSchema(item, items));
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
            String targetPath
    ) {

        private static EdgeSignature from(GraphDraft.DraftEdge edge) {
            return new EdgeSignature(
                    normalizedEdgeValue(edge.kind()),
                    normalizedEdgeValue(edge.source().nodeId()),
                    normalizedEdgeValue(edge.source().port()),
                    normalizePath(edge.source().path()),
                    normalizedEdgeValue(edge.target().nodeId()),
                    normalizedEdgeValue(edge.target().port()),
                    normalizePath(edge.target().path())
            );
        }

        private String label() {
            return "%s:%s.%s.%s -> %s.%s.%s".formatted(
                    kind,
                    sourceNodeId,
                    sourcePort,
                    sourcePath,
                    targetNodeId,
                    targetPort,
                    targetPath
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
        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                diagnostics.add(VisualDiagnostic.error("visual.config.required",
                        "Required config '%s' is missing.".formatted(required),
                        path + "/fields/" + required));
            }
        }
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/fields/" + entry.getKey(), diagnostics);
            } else if (Boolean.FALSE.equals(additional)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/fields/" + entry.getKey()));
            } else if (additional instanceof Map<?, ?> additionalSchema) {
                validateConfigValue(entry.getValue(), objectProperty(additionalSchema),
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
        Map<String, Object> properties = propertiesOf(schema);
        for (String required : requiredNamesOf(schema)) {
            if (!object.containsKey(required) || object.get(required) == null) {
                diagnostics.add(VisualDiagnostic.error("visual.config.required",
                        "Required config '%s' is missing.".formatted(required),
                        path + "/" + required));
            }
        }
        Object additional = schema.get("additionalProperties");
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            Map<String, Object> property = objectProperty(properties.get(entry.getKey()));
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/" + entry.getKey(), diagnostics);
            } else if (Boolean.FALSE.equals(additional)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/" + entry.getKey()));
            } else if (additional instanceof Map<?, ?> additionalSchema) {
                validateConfigValue(entry.getValue(), objectProperty(additionalSchema),
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
        Map<String, Object> items = objectProperty(schema.get("items"));
        if (items == null) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            validateConfigValue(list.get(i), items, path + "/" + i, diagnostics);
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
            Object additional = schema.get("additionalProperties");
            if (additional instanceof Map<?, ?> additionalSchema) {
                Map<String, Object> additionalProperty = objectProperty(additionalSchema);
                return additionalProperty == null ? Map.of() : additionalProperty;
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
