package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.contract.GraphContractSemantics;
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
import static com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility.valueMatchesSchema;

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
    private static final Set<String> SUPPORTED_LAYOUT_SCHEMA_VERSIONS = Set.of(
            "bloge.visualLayout.v1"
    );
    private static final Set<String> SUPPORTED_LAYOUT_EDGE_KINDS = Set.of(
            "data",
            "dependency",
            "route",
            "config"
    );
    private static final Set<String> SUPPORTED_EDGE_KINDS = Set.of("data", "dependency", "route");
    private static final Set<String> EXECUTION_CONFIG_KEYS = Set.of("timeout", "retryAttempts");
    private static final Set<String> SERVICE_MANAGED_PUBLICATION_CONFIG_KEYS = Set.of("publicationId", "outputNode");
    private static final Set<String> RESERVED_DSL_FIELD_NAMES = Set.of(
            "graph", "node", "branch", "decision_table", "on", "input", "depends_on",
            "timeout", "retry", "fallback", "execution_mode", "worker_topic", "compensate",
            "saga", "true", "false", "schema", "output", "otherwise", "when", "transform",
            "foreach", "sequential", "in", "loop", "parallel", "until", "carry", "wait",
            "after", "await", "event", "where", "mode", "stream", "streaming", "buffer",
            "let", "import", "as", "script", "exit", "exhausted"
    );
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String ARRAY_INDEX_PATTERN = "\\d+";
    private static final String PATH_SEGMENT_PATTERN = IDENTIFIER_PATTERN + "(?:\\[" + ARRAY_INDEX_PATTERN + "\\])*";
    private static final String ROOT_ARRAY_PATH_SEGMENT_PATTERN = "(?:\\[" + ARRAY_INDEX_PATTERN + "])+";
    private static final String TEMPLATE_PATH_SEGMENT_PATTERN = "(?:" + IDENTIFIER_PATTERN + "|"
            + ARRAY_INDEX_PATTERN + ")";
    private static final String PATH_PATTERN = PATH_SEGMENT_PATTERN + "(?:\\." + PATH_SEGMENT_PATTERN + ")*";
    private static final String ROOT_ARRAY_PATH_PATTERN = ROOT_ARRAY_PATH_SEGMENT_PATTERN
            + "(?:\\." + PATH_PATTERN + ")*";
    private static final String TEMPLATE_PATH_PATTERN = TEMPLATE_PATH_SEGMENT_PATTERN
            + "(?:\\." + TEMPLATE_PATH_SEGMENT_PATTERN + ")*";
    private static final Pattern DSL_IDENTIFIER = Pattern.compile(IDENTIFIER_PATTERN);
    private static final Pattern LAYOUT_GROUP_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern EXPRESSION_PATH_SEGMENT = Pattern.compile(
            "(?:" + PATH_SEGMENT_PATTERN + "|" + ROOT_ARRAY_PATH_SEGMENT_PATTERN + ")");
    private static final Pattern BRACKET_INDEX = Pattern.compile("\\[(" + ARRAY_INDEX_PATTERN + ")]");
    private static final Pattern PURE_CONTEXT_REFERENCE = Pattern.compile(
            "^ctx(?:(?:\\.(" + PATH_PATTERN + "))|(" + ROOT_ARRAY_PATH_PATTERN + "))?$");
    private static final Pattern PURE_NODE_REFERENCE = Pattern.compile(
            "^(" + IDENTIFIER_PATTERN + ")\\.output(?:(?:\\.(" + PATH_PATTERN + "))|("
                    + ROOT_ARRAY_PATH_PATTERN + "))?$");
    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])ctx(?:(?:\\.(" + PATH_PATTERN + "))|("
                    + ROOT_ARRAY_PATH_PATTERN + "))?(?![A-Za-z0-9_\\[])");
    private static final Pattern NODE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(" + IDENTIFIER_PATTERN + ")\\.output(?:(?:\\.(" + PATH_PATTERN + "))|("
                    + ROOT_ARRAY_PATH_PATTERN + "))?"
                    + "(?![A-Za-z0-9_\\[])");
    private static final Pattern PATH_LIKE_CONTEXT_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])ctx(?:\\.([^\\s,;(){}+*/<>=!&|?]+)|(\\[[^\\s,;(){}+*/<>=!&|?]+))");
    private static final Pattern PATH_LIKE_NODE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_.])(" + IDENTIFIER_PATTERN + ")\\.output"
                    + "(?:\\.([^\\s,;(){}+*/<>=!&|?]+)|(\\[[^\\s,;(){}+*/<>=!&|?]+))");
    private static final Pattern BRANCH_SELECTOR_TEMPLATE = Pattern.compile("^\\{\\{\\s*((?:input\\.)?"
            + TEMPLATE_PATH_PATTERN + ")\\s*}}$");
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
            return new VisualValidationResult(false, diagnostics, VisualGraphReadiness.from(null, Map.of(),
                    diagnostics));
        }
        if (!SUPPORTED_DRAFT_SCHEMA_VERSIONS.contains(draft.schemaVersion())) {
            String actual = draft.schemaVersion();
            diagnostics.add(VisualDiagnostic.error("visual.draft.schemaVersion.unsupported",
                    "Graph draft schemaVersion '%s' is unsupported; visual authoring supports %s."
                            .formatted(actual, SUPPORTED_DRAFT_SCHEMA_VERSIONS),
                    "/schemaVersion",
                    Map.of("actual", actual, "expected", GraphDraft.SCHEMA_VERSION)));
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
        validateGraphIdentifier(draft.graphName(), diagnostics);
        try {
            GraphContractSemantics.fromVisualLayout(draft.visualLayout());
        } catch (IllegalArgumentException exception) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.contract.semantics.invalid",
                    exception.getMessage(),
                    "/visualLayout/graphContract/contractSemantics"));
        }
        diagnostics.addAll(VisualSecretGuard.detectDraftSecrets(draft));
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(draft.inputSchema(), "/inputSchema"));
        diagnostics.addAll(VisualSchemaValidator.validateEnvelope(draft.outputSchema(), "/outputSchema"));
        validateGraphInputSchemaDslPathFields(draft.inputSchema(), diagnostics);

        Set<String> nodeIds = new HashSet<>();
        Map<String, GraphDraft.DraftNode> nodesById = new LinkedHashMap<>();
        Map<String, OperatorDefinition> operatorsByNodeId = new LinkedHashMap<>();
        Map<String, OperatorDefinition> operatorsByRef = catalog.findAll(draft.nodes().stream()
                .map(GraphDraft.DraftNode::operatorRef)
                .toList());
        Map<String, Map<String, GraphDraft.UnionBranchSelection>> configUnionBranchesByNode =
                configUnionBranchesByNode(draft);
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            String nodePath = "/nodes/" + i;
            validateNodeIdentifier(node, nodePath, diagnostics);
            if (!nodeIds.add(node.id())) {
                diagnostics.add(VisualDiagnostic.error("visual.node.duplicateId",
                        "Duplicate node id: " + node.id(), nodePath + "/id"));
            }
            nodesById.put(node.id(), node);
            OperatorDefinition operator = operatorsByRef.get(node.operatorRef());
            if (operator == null) {
                diagnostics.add(VisualDiagnostic.error("visual.operator.unknown",
                        "Unknown operatorRef: " + node.operatorRef(), nodePath + "/operatorRef"));
                continue;
            }
            operatorsByNodeId.put(node.id(), operator);
            validateOperatorFingerprint(node, operator, draft.operatorFingerprints(), nodePath, diagnostics);
            validateOperatorPolicy(draft, node, operator, nodePath, diagnostics);
            validateOperatorRuntimeCapabilities(node, operator, nodePath, diagnostics);
            validateOperatorGovernanceWarnings(node, operator, nodePath, diagnostics);
            validateOperatorLifecycleWarnings(node, operator, nodePath, diagnostics);
            validateDuplicateInputTargets(node, operator, nodePath, diagnostics);
            validateRequiredInputs(node, operator, nodePath, diagnostics);
            validateUnknownInputs(node, operator, nodePath, diagnostics);
            validateConfig(node, operator, nodePath,
                    configUnionBranchesByNode.getOrDefault(node.id(), Map.of()), diagnostics);
        }

        validateOperatorFingerprintKeys(draft.operatorFingerprints(), nodeIds, diagnostics);
        validateVisualLayout(draft, nodesById, diagnostics);
        validateNodePathBindings(draft, nodesById, operatorsByNodeId, diagnostics);
        validateConfigReferences(draft, nodesById, operatorsByNodeId, configUnionBranchesByNode, diagnostics);
        validateEdgeIdentity(draft, diagnostics);
        validateEdges(draft, nodesById, operatorsByNodeId, diagnostics);
        if (options.requireEdgeBindingConsistency()) {
            validateDataEdgeBindingConsistency(draft, nodesById, operatorsByNodeId, diagnostics);
        }
        validateAcyclic(draft, nodesById, diagnostics);
        validateOutputSelection(draft, nodeIds, nodesById, operatorsByNodeId, diagnostics);
        validateOutputReachability(draft, nodesById, diagnostics);
        return new VisualValidationResult(diagnostics.stream().noneMatch(VisualDiagnostic::error), diagnostics,
                VisualGraphReadiness.from(draft, operatorsByNodeId, diagnostics));
    }

    private static void validateGraphIdentifier(String graphName, List<VisualDiagnostic> diagnostics) {
        if (isDslFieldName(graphName)) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.graph.name.invalid",
                "Graph name '%s' cannot be rendered as a BLOGE DSL identifier.".formatted(graphName),
                "/graphName"));
    }

    private static void validateNodeIdentifier(GraphDraft.DraftNode node,
                                               String nodePath,
                                               List<VisualDiagnostic> diagnostics) {
        if (isDslFieldName(node.id())) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.node.id.invalid",
                "Node id '%s' cannot be rendered as a BLOGE DSL identifier.".formatted(node.id()),
                nodePath + "/id"));
    }

    private static void validateGraphInputSchemaDslPathFields(SchemaEnvelope schema,
                                                              List<VisualDiagnostic> diagnostics) {
        if (schema == null) {
            return;
        }
        validateSchemaDslPathFields(schema.schema(), "/inputSchema/schema", diagnostics);
    }

    private static void validateSchemaDslPathFields(Map<String, Object> schema,
                                                    String path,
                                                    List<VisualDiagnostic> diagnostics) {
        propertiesOf(schema).forEach((fieldName, rawProperty) -> {
            String propertyPath = path + "/properties/" + fieldName;
            if (!isDslFieldName(fieldName)) {
                diagnostics.add(VisualDiagnostic.error("visual.inputSchema.dslField.invalid",
                        "Graph inputSchema property '%s' cannot be rendered as a BLOGE DSL path segment."
                                .formatted(fieldName),
                        propertyPath));
            }
            Map<String, Object> property = objectProperty(rawProperty);
            if (property != null) {
                validateSchemaDslPathFields(property, propertyPath, diagnostics);
            }
        });

        Map<String, Object> items = objectProperty(schema.get("items"));
        if (items != null) {
            validateSchemaDslPathFields(items, path + "/items", diagnostics);
        }
        List<Map<String, Object>> prefixItems = prefixItemsOf(schema);
        for (int i = 0; i < prefixItems.size(); i++) {
            validateSchemaDslPathFields(prefixItems.get(i), path + "/prefixItems/" + i, diagnostics);
        }
        Map<String, Object> patternProperties = patternPropertiesOf(schema);
        if (patternProperties != null) {
            patternProperties.forEach((pattern, rawSchema) -> {
                Map<String, Object> patternSchema = objectProperty(rawSchema);
                if (patternSchema != null) {
                    validateSchemaDslPathFields(patternSchema,
                            path + "/patternProperties/" + pattern, diagnostics);
                }
            });
        }
        Object residual = residualPropertiesPolicy(schema);
        if (residual instanceof Map<?, ?>) {
            Map<String, Object> residualSchema = objectProperty(residual);
            if (residualSchema != null) {
                validateSchemaDslPathFields(residualSchema,
                        path + "/" + residualPropertiesKeyword(schema), diagnostics);
            }
        }
    }

    private static String residualPropertiesKeyword(Map<String, Object> schema) {
        return schema.containsKey("additionalProperties") ? "additionalProperties" : "unevaluatedProperties";
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

    private static void validateOperatorFingerprintKeys(Map<String, String> operatorFingerprints,
                                                        Set<String> nodeIds,
                                                        List<VisualDiagnostic> diagnostics) {
        if (operatorFingerprints.isEmpty()) {
            return;
        }
        operatorFingerprints.keySet().stream()
                .filter(nodeId -> !nodeIds.contains(nodeId))
                .forEach(nodeId -> diagnostics.add(VisualDiagnostic.error(
                        "visual.operator.fingerprintUnknownNode",
                        "Operator fingerprint snapshot references node '%s', but no current draft node has that id."
                                .formatted(nodeId),
                        "/operatorFingerprints/" + jsonPointerSegment(nodeId)
                )));
    }

    private static String jsonPointerSegment(String value) {
        return String.valueOf(value).replace("~", "~0").replace("/", "~1");
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

    private static void validateOperatorRuntimeCapabilities(GraphDraft.DraftNode node,
                                                            OperatorDefinition operator,
                                                            String nodePath,
                                                            List<VisualDiagnostic> diagnostics) {
        OperatorDefinition.Capabilities capabilities = operator.capabilities();
        if (capabilities.streaming()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.runtime.streamingUnsupported",
                    "Operator '%s' on node '%s' produces streaming output; schema authoring is allowed, but this visual runtime cannot execute streaming nodes until a streaming runtime is bound."
                            .formatted(operator.operatorRef(), node.id()),
                    nodePath + "/operatorRef"));
        }
        if (capabilities.durable()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.runtime.durableUnsupported",
                    "Operator '%s' on node '%s' requires a durable/suspendable runtime; schema authoring is allowed, but this visual runtime cannot execute durable nodes until a durable runtime is bound."
                            .formatted(operator.operatorRef(), node.id()),
                    nodePath + "/operatorRef"));
        }
    }

    private static void validateOperatorGovernanceWarnings(GraphDraft.DraftNode node,
                                                           OperatorDefinition operator,
                                                           String nodePath,
                                                           List<VisualDiagnostic> diagnostics) {
        OperatorDefinition.Capabilities capabilities = operator.capabilities();
        if (capabilities.externalWrite() && !capabilities.sideEffectProtocol().managedWrite()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.governance.sideEffectProtocolRequired",
                    "Operator '%s' on node '%s' declares an external write without a managed journal, commit receipt, and reconciliation contract; it remains DESIGN-only."
                            .formatted(operator.operatorRef(), node.id()),
                    nodePath + "/operatorRef",
                    Map.of("requiredSchemaVersion", OperatorDefinition.SideEffectProtocol.SCHEMA_VERSION,
                            "protocolMode", capabilities.sideEffectProtocol().mode())));
        }
        if (capabilities.requiresSecrets()) {
            diagnostics.add(VisualDiagnostic.warning("visual.operator.governance.requiresSecrets",
                    "Operator '%s' on node '%s' requires secret-backed execution; verify secretRef binding and access review before production promotion."
                            .formatted(operator.operatorRef(), node.id()),
                    nodePath + "/operatorRef"));
        }
        if (!"NON_IDEMPOTENT".equals(capabilities.idempotency())
                || "PURE".equals(capabilities.effect())) {
            return;
        }
        diagnostics.add(VisualDiagnostic.warning("visual.operator.governance.nonIdempotent",
                "Operator '%s' on node '%s' declares non-idempotent side effects; add an explicit review or audit control before production promotion."
                        .formatted(operator.operatorRef(), node.id()),
                nodePath + "/operatorRef"));
    }

    private static void validateOperatorLifecycleWarnings(GraphDraft.DraftNode node,
                                                          OperatorDefinition operator,
                                                          String nodePath,
                                                          List<VisualDiagnostic> diagnostics) {
        operator.diagnostics().stream()
                .filter(GraphDraftValidator::isCatalogLifecycleDiagnostic)
                .map(diagnostic -> nodeScopedOperatorDiagnostic(node, operator, nodePath, diagnostic))
                .forEach(diagnostics::add);
    }

    private static boolean isCatalogLifecycleDiagnostic(VisualDiagnostic diagnostic) {
        return diagnostic != null && diagnostic.code() != null
                && diagnostic.code().startsWith("visual.operator.lifecycle.");
    }

    private static VisualDiagnostic nodeScopedOperatorDiagnostic(GraphDraft.DraftNode node,
                                                                 OperatorDefinition operator,
                                                                 String nodePath,
                                                                 VisualDiagnostic diagnostic) {
        Map<String, Object> metadata = new LinkedHashMap<>(diagnostic.metadata());
        metadata.put("nodeId", node.id());
        metadata.put("operatorRef", operator.operatorRef());
        if (!diagnostic.target().isBlank()) {
            metadata.put("catalogDiagnosticTarget", diagnostic.target());
        }
        return new VisualDiagnostic(
                diagnostic.level(),
                diagnostic.code(),
                "Node '%s' uses operator '%s': %s"
                        .formatted(node.id(), operator.operatorRef(), diagnostic.message()),
                nodePath + "/operatorRef",
                -1,
                -1,
                metadata
        );
    }

    private static void validateOutputSelection(GraphDraft draft,
                                                Set<String> nodeIds,
                                                Map<String, GraphDraft.DraftNode> nodesById,
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
        GraphDraft.DraftNode outputNode = nodesById.get(output.nodeId());
        if (output.path().isBlank()) {
            if (requiresExecutableDslSafePaths(operator)) {
                validateWholeOutputPortDslPathSegments(operator, diagnostics);
            }
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
        if (requiresExecutableDslSafePaths(operator)) {
            validateOutputPortDslPathSegment(outputPort.get(), "/output/path",
                    "visual.output.portSegment.invalid", diagnostics);
            validateOutputDslPathSegments(outputPort.get().schema(), outputReference.path(), "/output/path",
                    diagnostics);
        }
        if (outputPropertyAtPath(outputNode, operator, outputPort.get(), outputReference.path()) == null) {
            diagnostics.add(VisualDiagnostic.error("visual.output.unknownPath",
                    "Output node '%s' port '%s' does not expose path '%s'."
                            .formatted(output.nodeId(), outputPort.get().name(), outputReference.path()),
                    "/output/path"));
        }
    }

    private static void validateWholeOutputPortDslPathSegments(OperatorDefinition operator,
                                                               List<VisualDiagnostic> diagnostics) {
        for (OperatorDefinition.Port port : operator.ports().outputs()) {
            validateOutputPortDslPathSegment(port, "/output/path",
                    "visual.output.portSegment.invalid", diagnostics);
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
            if (targetPropertyAtPath(targetPort.get(), inputName, input.getValue(),
                    nodePath + "/inputs/" + input.getKey(), diagnostics) == null) {
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
                                                 Map<String, Map<String, GraphDraft.UnionBranchSelection>> configUnionBranchesByNode,
                                                 List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            OperatorDefinition operator = operatorsByNodeId.get(node.id());
            if (operator == null) {
                continue;
            }
            Map<String, GraphDraft.UnionBranchSelection> branchSelections =
                    configUnionBranchesByNode.getOrDefault(node.id(), Map.of());
            validateConfigReferenceValue(node.config(), operator.configSchema().schema(), draft.inputSchema(),
                    nodesById, operatorsByNodeId, "/nodes/" + i + "/config", "", branchSelections, diagnostics);
        }
    }

    private static void validateConfigReferenceValue(Object value,
                                                     Map<String, Object> configSchema,
                                                     SchemaEnvelope inputSchema,
                                                     Map<String, GraphDraft.DraftNode> nodesById,
                                                     Map<String, OperatorDefinition> operatorsByNodeId,
                                                     String targetPath,
                                                     String schemaPath,
                                                     Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                                     List<VisualDiagnostic> diagnostics) {
        Map<String, Object> effectiveConfigSchema = selectedConfigUnionBranchSchemaAtPath(configSchema,
                schemaPath, branchSelections, targetPath, diagnostics).orElse(configSchema);
        if (value instanceof String expression) {
            if (!looksLikeReferenceExpression(expression)) {
                return;
            }
            validateConfigExpressionValue(expression, effectiveConfigSchema, inputSchema, nodesById, operatorsByNodeId,
                    targetPath, diagnostics);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if ("expression".equals(map.get("kind"))) {
                Object expression = map.get("expr");
                validateConfigExpressionValue(expression == null ? "" : String.valueOf(expression), effectiveConfigSchema,
                        inputSchema, nodesById, operatorsByNodeId, targetPath + "/expr", diagnostics);
                return;
            }
            if ("objectTemplate".equals(map.get("kind")) && map.get("fields") instanceof Map<?, ?> fields) {
                fields.forEach((key, item) -> validateConfigReferenceValue(item,
                        configChildSchema(effectiveConfigSchema, String.valueOf(key)), inputSchema, nodesById,
                        operatorsByNodeId, targetPath + "/fields/" + key,
                        appendPath(schemaPath, String.valueOf(key)), branchSelections, diagnostics));
                return;
            }
            map.forEach((key, item) -> validateConfigReferenceValue(item,
                    configChildSchema(effectiveConfigSchema, String.valueOf(key)), inputSchema, nodesById,
                    operatorsByNodeId, targetPath + "/" + key,
                    appendPath(schemaPath, String.valueOf(key)), branchSelections, diagnostics));
            return;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> items = objectProperty(effectiveConfigSchema.get("items"));
            for (int i = 0; i < list.size(); i++) {
                validateConfigReferenceValue(list.get(i), items == null ? Map.of() : items, inputSchema,
                        nodesById, operatorsByNodeId,
                        targetPath + "/" + i, appendPath(schemaPath, String.valueOf(i)),
                        branchSelections, diagnostics);
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
        validateBindingTargetDslPathSegments(binding, inputName, targetOperator, targetPath, diagnostics);
        if ("objectTemplate".equals(binding.kind())) {
            validateObjectTemplateFieldNames(binding.fields(), targetPath + "/fields",
                    "visual.binding.objectTemplateField.invalid", diagnostics);
            Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                    inputName);
            if (targetPort.isEmpty()) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPort",
                        "Binding target input '%s' must target a declared port on operator '%s'."
                                .formatted(inputName, targetOperator.operatorRef()),
                        targetPath));
                return;
            }
            Map<String, Object> targetProperty = targetPropertyAtPath(targetPort.get(), inputName, binding,
                    targetPath, diagnostics);
            if (targetProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                        "Target port '%s' does not accept path '%s'."
                                .formatted(targetPort.get().name(), inputName),
                        targetPath));
                return;
            }
            Map<String, Object> effectiveTargetProperty = selectedTargetUnionBranchSchema(binding, targetProperty,
                    targetPath, diagnostics).orElse(targetProperty);
            String targetType = schemaType(effectiveTargetProperty);
            if (!targetType.isBlank()
                    && !"object".equals(targetType)
                    && !"any".equals(targetType)
                    && !"opaque".equals(targetType)) {
                diagnostics.add(VisualDiagnostic.error("visual.binding.typeMismatch",
                        "Object template for input '%s.%s' must target object-compatible schema, but target is %s."
                                .formatted(targetPort.get().name(), inputName, schemaTypeLabel(effectiveTargetProperty)),
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

        Map<String, Object> sourceProperty = outputPropertyAtPath(sourceNode, sourceOperator,
                sourcePort.get(), binding.path());
        if (requiresExecutableDslSafePaths(sourceOperator)) {
            validateOutputPortDslPathSegment(sourcePort.get(), targetPath + "/sourcePort",
                    "visual.binding.sourcePortSegment.invalid", diagnostics);
            validateDslPathSegments(sourcePort.get().schema(), binding.path(), targetPath + "/path", diagnostics);
        }
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

        Map<String, Object> targetProperty = targetPropertyAtPath(targetPort.get(), inputName, binding,
                targetPath, diagnostics);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }
        Optional<String> compatibilityIssue = schemaCompatibilityIssueForBindingTarget(binding, sourceProperty,
                targetProperty, targetPath, diagnostics);
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
        validateDslPathSegments(inputSchema, binding.path(), targetPath + "/path", diagnostics);
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

        Map<String, Object> targetProperty = targetPropertyAtPath(targetPort.get(), inputName, binding,
                targetPath, diagnostics);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }
        Optional<String> compatibilityIssue = schemaCompatibilityIssueForBindingTarget(binding, sourceProperty,
                targetProperty, targetPath, diagnostics);
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

        Map<String, Object> targetProperty = targetPropertyAtPath(targetPort.get(), inputName, binding,
                targetPath, diagnostics);
        if (targetProperty == null) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.unknownTargetPath",
                    "Target port '%s' does not accept path '%s'."
                            .formatted(targetPort.get().name(), inputName),
                    targetPath));
            return;
        }

        if (!constantValueMatchesBindingTargetSchema(binding.value(), binding, targetProperty, targetPath,
                diagnostics)) {
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

        Map<String, Object> targetProperty = targetPropertyAtPath(targetPort.get(), inputName, binding,
                targetPath, diagnostics);
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
                    : schemaCompatibilityIssueForBindingTarget(binding, pureReference.schema(), targetProperty,
                    targetPath, diagnostics);
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
            Optional<String> compatibilityIssue = schemaCompatibilityIssueForBindingTarget(binding,
                    literal.get().schema(), targetProperty, targetPath, diagnostics);
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

    private static Optional<String> schemaCompatibilityIssueForBindingTarget(GraphDraft.Binding binding,
                                                                             Map<String, Object> sourceSchema,
                                                                             Map<String, Object> targetSchema,
                                                                             String targetPath,
                                                                             List<VisualDiagnostic> diagnostics) {
        Optional<Map<String, Object>> selectedBranch = selectedTargetUnionBranchSchema(binding, targetSchema,
                targetPath, diagnostics);
        if (selectedBranch.isEmpty()) {
            return schemaCompatibilityIssue(sourceSchema, targetSchema);
        }
        Map<String, Object> baseTarget = schemaWithoutUnions(targetSchema);
        if (!baseTarget.isEmpty()) {
            Optional<String> baseIssue = schemaCompatibilityIssue(sourceSchema, baseTarget);
            if (baseIssue.isPresent()) {
                return Optional.of("target union base constraints are not compatible: " + baseIssue.get());
            }
        }
        return schemaCompatibilityIssue(sourceSchema, selectedBranch.get());
    }

    private static Map<String, Object> targetPropertyAtPath(OperatorDefinition.Port targetPort,
                                                            String inputName,
                                                            GraphDraft.Binding binding,
                                                            String targetPath,
                                                            List<VisualDiagnostic> diagnostics) {
        return propertyAtPath(targetPort.schema(), inputName, binding.targetUnionBranches(), targetPath,
                diagnostics);
    }

    private static boolean constantValueMatchesBindingTargetSchema(Object value,
                                                                   GraphDraft.Binding binding,
                                                                   Map<String, Object> targetSchema,
                                                                   String targetPath,
                                                                   List<VisualDiagnostic> diagnostics) {
        Optional<Map<String, Object>> selectedBranch = selectedTargetUnionBranchSchema(binding, targetSchema,
                targetPath, diagnostics);
        if (selectedBranch.isEmpty()) {
            return valueMatchesSchema(value, targetSchema);
        }
        Map<String, Object> baseTarget = schemaWithoutUnions(targetSchema);
        return valueMatchesSchema(value, baseTarget) && valueMatchesSchema(value, selectedBranch.get());
    }

    private static Optional<Map<String, Object>> selectedTargetUnionBranchSchema(GraphDraft.Binding binding,
                                                                                Map<String, Object> targetSchema,
                                                                                String targetPath,
                                                                                List<VisualDiagnostic> diagnostics) {
        return selectedUnionBranchSchema(targetSchema, binding.targetUnionBranch(),
                targetPath + "/targetUnionBranch", diagnostics);
    }

    private static Optional<Map<String, Object>> selectedUnionBranchSchema(Map<String, Object> targetSchema,
                                                                          GraphDraft.UnionBranchSelection selection,
                                                                          String diagnosticPath,
                                                                          List<VisualDiagnostic> diagnostics) {
        if (selection == null || !selection.selected()) {
            return Optional.empty();
        }
        String keyword = selection.keyword();
        if (!"oneOf".equals(keyword) && !"anyOf".equals(keyword)) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.targetUnionBranch.invalid",
                    "Binding targetUnionBranch keyword must be oneOf or anyOf.",
                    diagnosticPath + "/keyword"));
            return Optional.empty();
        }
        List<Map<String, Object>> branches = unionBranches(targetSchema, keyword);
        if (branches.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.targetUnionBranch.invalid",
                    "Binding target does not declare a %s union branch at this path.".formatted(keyword),
                    diagnosticPath));
            return Optional.empty();
        }
        if (selection.index() < 0 || selection.index() >= branches.size()) {
            diagnostics.add(VisualDiagnostic.error("visual.binding.targetUnionBranch.invalid",
                    "Binding targetUnionBranch index %d is outside %s branch range 0..%d."
                            .formatted(selection.index(), keyword, branches.size() - 1),
                    diagnosticPath + "/index"));
            return Optional.empty();
        }
        return Optional.of(branches.get(selection.index()));
    }

    private static ExpressionReference resolvePureExpressionReference(String expression,
                                                                      SchemaEnvelope inputSchema,
                                                                      Map<String, GraphDraft.DraftNode> nodesById,
                                                                      Map<String, OperatorDefinition> operatorsByNodeId,
                                                                      String targetPath,
                                                                      List<VisualDiagnostic> diagnostics) {
        Matcher context = PURE_CONTEXT_REFERENCE.matcher(expression);
        if (context.matches()) {
            String path = matchedPath(context, 1, 2);
            String schemaPath = expressionPathToSchemaPath(path);
            return new ExpressionReference(true,
                    resolveContextReference(schemaPath, inputSchema, targetPath, diagnostics),
                    expressionReferenceLabel("ctx", path));
        }

        Matcher node = PURE_NODE_REFERENCE.matcher(expression);
        if (node.matches()) {
            String nodeId = node.group(1);
            String outputPath = matchedPath(node, 2, 3);
            String schemaPath = expressionPathToSchemaPath(outputPath);
            return new ExpressionReference(true,
                    resolveNodeReference(nodeId, schemaPath, nodesById, operatorsByNodeId, targetPath, diagnostics),
                    expressionReferenceLabel(nodeId + ".output", outputPath));
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
        validateExpressionPathSegments(searchable, targetPath, diagnostics);

        Matcher context = CONTEXT_REFERENCE.matcher(searchable);
        while (context.find()) {
            String path = matchedPath(context, 1, 2);
            String schemaPath = expressionPathToSchemaPath(path);
            if (seenReferences.add("ctx:" + schemaPath)) {
                resolveContextReference(schemaPath, inputSchema, targetPath, diagnostics);
            }
        }

        Matcher node = NODE_REFERENCE.matcher(searchable);
        while (node.find()) {
            String nodeId = node.group(1);
            String outputPath = matchedPath(node, 2, 3);
            String schemaPath = expressionPathToSchemaPath(outputPath);
            if (seenReferences.add("node:" + nodeId + ":" + schemaPath)) {
                resolveNodeReference(nodeId, schemaPath, nodesById, operatorsByNodeId, targetPath, diagnostics);
            }
        }
    }

    private static void validateExpressionPathSegments(String searchable,
                                                       String targetPath,
                                                       List<VisualDiagnostic> diagnostics) {
        Set<String> seenInvalidReferences = new HashSet<>();
        Matcher context = PATH_LIKE_CONTEXT_REFERENCE.matcher(searchable);
        while (context.find()) {
            String path = matchedPath(context, 1, 2);
            validateExpressionPathSegments(expressionReferenceLabel("ctx", path), path, targetPath,
                    seenInvalidReferences, diagnostics);
        }

        Matcher node = PATH_LIKE_NODE_REFERENCE.matcher(searchable);
        while (node.find()) {
            String path = matchedPath(node, 2, 3);
            validateExpressionPathSegments(expressionReferenceLabel(node.group(1) + ".output", path), path,
                    targetPath,
                    seenInvalidReferences, diagnostics);
        }
    }

    private static String matchedPath(Matcher matcher, int dottedGroup, int rootArrayGroup) {
        String dotted = matcher.group(dottedGroup);
        if (dotted != null) {
            return dotted;
        }
        String rootArray = matcher.group(rootArrayGroup);
        return rootArray == null ? "" : rootArray;
    }

    private static String expressionReferenceLabel(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        return path.startsWith("[") ? base + path : base + "." + path;
    }

    private static void validateExpressionPathSegments(String reference,
                                                       String path,
                                                       String targetPath,
                                                       Set<String> seenInvalidReferences,
                                                       List<VisualDiagnostic> diagnostics) {
        String[] segments = path.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean decimalTail = i + 1 < segments.length && INTEGER_LITERAL.matcher(segments[i + 1]).matches();
            if (looksLikeNumericSubtraction(segment) && (i == segments.length - 1 || decimalTail)) {
                if (decimalTail) {
                    i++;
                }
                continue;
            }
            if (segment.isBlank() || isExpressionPathSegment(segment)) {
                continue;
            }
            String key = reference + ":" + segment;
            if (!seenInvalidReferences.add(key)) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.error("visual.expression.pathSegment.invalid",
                    "Expression reference '%s' contains path segment '%s' that cannot be rendered as a BLOGE DSL path segment."
                            .formatted(reference, segment),
                    targetPath));
        }
    }

    private static boolean looksLikeNumericSubtraction(String segment) {
        int minus = segment.indexOf('-');
        if (minus <= 0 || minus == segment.length() - 1) {
            return false;
        }
        String left = segment.substring(0, minus);
        String right = segment.substring(minus + 1);
        return isDslFieldName(left) && NUMBER_LITERAL.matcher(right).matches();
    }

    private static boolean isExpressionPathSegment(String segment) {
        return EXPRESSION_PATH_SEGMENT.matcher(segment).matches();
    }

    private static String expressionPathToSchemaPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                segments.add(segment);
                continue;
            }
            if (bracket > 0) {
                segments.add(segment.substring(0, bracket));
            }
            Matcher matcher = BRACKET_INDEX.matcher(segment.substring(bracket));
            while (matcher.find()) {
                segments.add(matcher.group(1));
            }
        }
        return String.join(".", segments);
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

        if (requiresExecutableDslSafePaths(sourceOperator)) {
            validateOutputPortDslPathSegment(sourcePort.get(), targetPath,
                    "visual.expression.sourcePortSegment.invalid", diagnostics);
        }
        Map<String, Object> sourceProperty = outputPropertyAtPath(sourceNode, sourceOperator,
                sourcePort.get(), outputReference.path());
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

    private static Map<String, Object> outputPropertyAtPath(GraphDraft.DraftNode sourceNode,
                                                            OperatorDefinition sourceOperator,
                                                            OperatorDefinition.Port sourcePort,
                                                            String path) {
        SchemaEnvelope dynamicSchema = dynamicOutputSchema(sourceNode, sourceOperator, sourcePort)
                .orElse(sourcePort.schema());
        return propertyAtPath(dynamicSchema, path);
    }

    private static Optional<SchemaEnvelope> dynamicOutputSchema(GraphDraft.DraftNode sourceNode,
                                                               OperatorDefinition sourceOperator,
                                                               OperatorDefinition.Port sourcePort) {
        if (sourceNode == null
                || !"bloge:decisionTable".equals(sourceOperator.operatorRef())
                || !"output".equals(sourcePort.name())) {
            return Optional.empty();
        }
        return Optional.of(new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                decisionTableOutputSchema(sourceNode)));
    }

    private static Map<String, Object> decisionTableOutputSchema(GraphDraft.DraftNode node) {
        String outputType = stringValue(node.config().get("outputType")).trim();
        Map<String, Object> parsed = parseBlogeTypeSchema(outputType);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of("type", "string"));
        properties.put("ruleId", Map.of("type", "string"));
        return objectSchemaWithResidualProperties(properties);
    }

    private static Map<String, Object> parseBlogeTypeSchema(String value) {
        String type = value == null ? "" : value.trim();
        if (type.isBlank()) {
            return Map.of();
        }
        if (type.startsWith("{") && type.endsWith("}")) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String field : splitTopLevel(type.substring(1, type.length() - 1), ',')) {
                int colon = indexOfTopLevel(field, ':');
                if (colon <= 0) {
                    continue;
                }
                String name = field.substring(0, colon).trim();
                if (name.isBlank()) {
                    continue;
                }
                properties.put(name, parseBlogeTypeSchema(field.substring(colon + 1)));
            }
            return objectSchemaWithResidualProperties(properties);
        }
        String normalized = type.toLowerCase();
        if ((normalized.startsWith("array<") || normalized.startsWith("list<")) && type.endsWith(">")) {
            int start = type.indexOf('<');
            return Map.of(
                    "type", "array",
                    "items", parseBlogeTypeSchema(type.substring(start + 1, type.length() - 1))
            );
        }
        if (normalized.equals("string")) {
            return Map.of("type", "string");
        }
        if (List.of("int", "integer", "long").contains(normalized)) {
            return Map.of("type", "integer");
        }
        if (List.of("decimal", "number", "double", "float").contains(normalized)) {
            return Map.of("type", "number");
        }
        if (List.of("bool", "boolean").contains(normalized)) {
            return Map.of("type", "boolean");
        }
        if (List.of("object", "map").contains(normalized)) {
            return objectSchemaWithResidualProperties(Map.of());
        }
        return Map.of();
    }

    private static Map<String, Object> objectSchemaWithResidualProperties(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : new LinkedHashMap<>(properties));
        schema.put("additionalProperties", true);
        return schema;
    }

    private static List<String> splitTopLevel(String value, char separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '{' || current == '<' || current == '[' || current == '(') {
                depth++;
            } else if (current == '}' || current == '>' || current == ']' || current == ')') {
                depth = Math.max(0, depth - 1);
            } else if (current == separator && depth == 0) {
                parts.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(value.substring(start).trim());
        return parts;
    }

    private static int indexOfTopLevel(String value, char needle) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '{' || current == '<' || current == '[' || current == '(') {
                depth++;
            } else if (current == '}' || current == '>' || current == ']' || current == ')') {
                depth = Math.max(0, depth - 1);
            } else if (current == needle && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema, String path) {
        return propertyAtPath(schema, path, Map.of(), null, List.of());
    }

    private static Map<String, Object> propertyAtPath(SchemaEnvelope schema,
                                                      String path,
                                                      Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                                      String targetPath,
                                                      List<VisualDiagnostic> diagnostics) {
        if (path == null || path.isBlank()) {
            Map<String, Object> root = new LinkedHashMap<>(schema.schema());
            if (!root.containsKey("type") && !root.containsKey("kind")) {
                String inferredType = schemaType(root);
                root.put("type", inferredType.isBlank() ? "object" : inferredType);
            }
            return selectedUnionBranchSchemaAtPath(root, "", branchSelections, targetPath, diagnostics)
                    .orElse(root);
        }
        Map<String, Object> currentSchema = schema.schema();
        Map<String, Object> properties = propertiesOf(currentSchema);
        Map<String, Object> current = null;
        String currentPath = "";
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            currentSchema = selectedUnionBranchSchemaAtPath(currentSchema, currentPath, branchSelections,
                    targetPath, diagnostics).orElse(currentSchema);
            properties = propertiesOf(currentSchema);
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
                currentPath = appendPath(currentPath, segment);
                continue;
            }
            current = objectProperty(properties.get(segment));
            if (current == null) {
                if (!propertyNameAllowedBySchema(currentSchema, segment)) {
                    return null;
                }
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
            currentPath = appendPath(currentPath, segment);
        }
        return selectedUnionBranchSchemaAtPath(current, currentPath, branchSelections, targetPath, diagnostics)
                .orElse(current);
    }

    private static Optional<Map<String, Object>> selectedUnionBranchSchemaAtPath(
            Map<String, Object> schema,
            String path,
            Map<String, GraphDraft.UnionBranchSelection> branchSelections,
            String targetPath,
            List<VisualDiagnostic> diagnostics) {
        if (schema == null || branchSelections == null || branchSelections.isEmpty()) {
            return Optional.empty();
        }
        GraphDraft.UnionBranchSelection selection = branchSelections.get(path == null ? "" : path);
        String diagnosticPath = targetPath == null
                ? "/targetUnionBranches/" + (path == null ? "" : path)
                : targetPath + "/targetUnionBranches/" + (path == null ? "" : path);
        return selectedUnionBranchSchema(schema, selection, diagnosticPath, diagnostics);
    }

    private static Optional<Map<String, Object>> selectedConfigUnionBranchSchemaAtPath(
            Map<String, Object> schema,
            String path,
            Map<String, GraphDraft.UnionBranchSelection> branchSelections,
            String targetPath,
            List<VisualDiagnostic> diagnostics) {
        if (schema == null || branchSelections == null || branchSelections.isEmpty()) {
            return Optional.empty();
        }
        String normalizedPath = path == null ? "" : path;
        GraphDraft.UnionBranchSelection selection = branchSelections.get(normalizedPath);
        if (selection == null || !selection.selected()) {
            return Optional.empty();
        }
        String diagnosticPath = targetPath + "/configUnionBranches/" + normalizedPath;
        String keyword = selection.keyword();
        if (!"oneOf".equals(keyword) && !"anyOf".equals(keyword)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.unionBranch.invalid",
                    "Config union branch keyword must be oneOf or anyOf.",
                    diagnosticPath + "/keyword"));
            return Optional.empty();
        }
        List<Map<String, Object>> branches = unionBranches(schema, keyword);
        if (branches.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.config.unionBranch.invalid",
                    "Config schema does not declare a %s union branch at this path.".formatted(keyword),
                    diagnosticPath));
            return Optional.empty();
        }
        if (selection.index() < 0 || selection.index() >= branches.size()) {
            diagnostics.add(VisualDiagnostic.error("visual.config.unionBranch.invalid",
                    "Config union branch index %d is outside %s branch range 0..%d."
                            .formatted(selection.index(), keyword, branches.size() - 1),
                    diagnosticPath + "/index"));
            return Optional.empty();
        }
        return Optional.of(branches.get(selection.index()));
    }

    private static void validateVisualLayout(GraphDraft draft,
                                             Map<String, GraphDraft.DraftNode> nodesById,
                                             List<VisualDiagnostic> diagnostics) {
        Map<String, Object> layout = draft.visualLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }
        Object rawSchemaVersion = layout.get("schemaVersion");
        if (rawSchemaVersion != null) {
            String schemaVersion = stringValue(rawSchemaVersion).trim();
            if (!SUPPORTED_LAYOUT_SCHEMA_VERSIONS.contains(schemaVersion)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.schemaVersion.unsupported",
                        "Visual layout schemaVersion '%s' is unsupported; visual authoring supports %s."
                                .formatted(schemaVersion, SUPPORTED_LAYOUT_SCHEMA_VERSIONS),
                        "/visualLayout/schemaVersion"));
            }
        }
        Object rootId = layout.get("rootId");
        if (rootId != null && !stringValue(rootId).trim().isBlank()
                && !draft.graphName().equals(stringValue(rootId).trim())) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.rootId.mismatch",
                    "Visual layout rootId '%s' must match graphName '%s'."
                            .formatted(stringValue(rootId).trim(), draft.graphName()),
                    "/visualLayout/rootId"));
        }
        Set<String> layoutGroupIds = validateLayoutGroups(layout.get("groups"), nodesById.keySet(), diagnostics);
        Optional<Set<String>> layoutNodeIds = validateLayoutNodes(layout.get("nodes"), nodesById, layoutGroupIds,
                diagnostics);
        layoutNodeIds.ifPresent(ids -> validateLayoutCoversGraphNodes(draft.nodes(), ids, diagnostics));
        Optional<Set<LayoutEdgeKey>> layoutEdgeKeys = validateLayoutEdges(layout.get("edges"),
                nodesById.keySet(), diagnostics);
        layoutEdgeKeys.ifPresent(keys -> validateLayoutCoversGraphEdges(draft.edges(), keys, diagnostics));
        validateLayoutViewport(layout.get("viewport"), diagnostics);
    }

    private static Optional<Set<String>> validateLayoutNodes(Object rawNodes,
                                                             Map<String, GraphDraft.DraftNode> nodesById,
                                                             Set<String> layoutGroupIds,
                                                             List<VisualDiagnostic> diagnostics) {
        if (rawNodes == null) {
            return Optional.empty();
        }
        if (!(rawNodes instanceof List<?> layoutNodes)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.nodes.invalid",
                    "Visual layout nodes must be an array.", "/visualLayout/nodes"));
            return Optional.empty();
        }
        Set<String> layoutNodeIds = new LinkedHashSet<>();
        for (int i = 0; i < layoutNodes.size(); i++) {
            String itemPath = "/visualLayout/nodes/" + i;
            if (!(layoutNodes.get(i) instanceof Map<?, ?> rawNode)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.node.invalid",
                        "Visual layout node must be an object.", itemPath));
                continue;
            }
            String nodeId = stringValue(rawNode.get("id")).trim();
            if (nodeId.isBlank()) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.node.idMissing",
                        "Visual layout node id is required.", itemPath + "/id"));
            } else {
                if (!layoutNodeIds.add(nodeId)) {
                    diagnostics.add(VisualDiagnostic.error("visual.layout.node.duplicateId",
                            "Visual layout contains duplicate node id: " + nodeId, itemPath + "/id"));
                }
                GraphDraft.DraftNode draftNode = nodesById.get(nodeId);
                if (draftNode == null) {
                    diagnostics.add(VisualDiagnostic.error("visual.layout.node.unknown",
                            "Visual layout references unknown graph node: " + nodeId, itemPath + "/id"));
                } else {
                    validateLayoutNodeOperatorRef(rawNode, draftNode, itemPath, diagnostics);
                }
            }
            validateLayoutNumberObject(rawNode.get("position"), itemPath + "/position",
                    List.of(new LayoutNumberField("x", false), new LayoutNumberField("y", false)),
                    "visual.layout.node.positionInvalid", diagnostics);
            validateLayoutNumberObject(rawNode.get("size"), itemPath + "/size",
                    List.of(new LayoutNumberField("width", true), new LayoutNumberField("height", true)),
                    "visual.layout.node.sizeInvalid", diagnostics);
            validateLayoutNodeGroup(rawNode.get("group"), layoutGroupIds, itemPath, diagnostics);
            Object annotations = rawNode.get("annotations");
            if (annotations != null && !(annotations instanceof Map<?, ?>)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.node.annotationsInvalid",
                        "Visual layout node annotations must be an object.", itemPath + "/annotations"));
            }
        }
        return Optional.of(layoutNodeIds);
    }

    private static void validateLayoutCoversGraphNodes(List<GraphDraft.DraftNode> nodes,
                                                       Set<String> layoutNodeIds,
                                                       List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < nodes.size(); i++) {
            GraphDraft.DraftNode node = nodes.get(i);
            if (layoutNodeIds.contains(node.id())) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.warning("visual.layout.node.missing",
                    "Visual layout does not include graph node '%s'; the canvas may need to auto-place it."
                            .formatted(node.id()),
                    "/nodes/" + i + "/position"));
        }
    }

    private static void validateLayoutNodeGroup(Object rawGroup,
                                                Set<String> layoutGroupIds,
                                                String itemPath,
                                                List<VisualDiagnostic> diagnostics) {
        if (rawGroup == null) {
            return;
        }
        if (!(rawGroup instanceof String)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.node.groupInvalid",
                    "Visual layout node group must be a string.", itemPath + "/group"));
            return;
        }
        String groupId = ((String) rawGroup).trim();
        if (groupId.isBlank()) {
            return;
        }
        if (!layoutGroupIds.contains(groupId)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.node.unknownGroup",
                    "Visual layout node references unknown group: " + groupId, itemPath + "/group"));
        }
    }

    private static void validateLayoutNodeOperatorRef(Map<?, ?> rawNode,
                                                      GraphDraft.DraftNode draftNode,
                                                      String itemPath,
                                                      List<VisualDiagnostic> diagnostics) {
        Object rawOperatorRef = rawNode.get("operatorRef");
        if (rawOperatorRef == null) {
            return;
        }
        String operatorRef = stringValue(rawOperatorRef).trim();
        if (operatorRef.isBlank()) {
            return;
        }
        if (!draftNode.operatorRef().equals(operatorRef)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.node.operatorRefMismatch",
                    "Visual layout node '%s' operatorRef '%s' must match graph node operatorRef '%s'."
                            .formatted(draftNode.id(), operatorRef, draftNode.operatorRef()),
                    itemPath + "/operatorRef"));
        }
    }

    private static Optional<Set<LayoutEdgeKey>> validateLayoutEdges(Object rawEdges,
                                                                    Set<String> nodeIds,
                                                                    List<VisualDiagnostic> diagnostics) {
        if (rawEdges == null) {
            return Optional.empty();
        }
        if (!(rawEdges instanceof List<?> layoutEdges)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.edges.invalid",
                    "Visual layout edges must be an array.", "/visualLayout/edges"));
            return Optional.empty();
        }
        Set<String> layoutEdgeIds = new LinkedHashSet<>();
        Set<LayoutEdgeKey> layoutEdgeKeys = new LinkedHashSet<>();
        for (int i = 0; i < layoutEdges.size(); i++) {
            String itemPath = "/visualLayout/edges/" + i;
            if (!(layoutEdges.get(i) instanceof Map<?, ?> rawEdge)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.edge.invalid",
                        "Visual layout edge must be an object.", itemPath));
                continue;
            }
            String edgeId = stringValue(rawEdge.get("id")).trim();
            if (!edgeId.isBlank() && !layoutEdgeIds.add(edgeId)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.edge.duplicateId",
                        "Visual layout contains duplicate edge id: " + edgeId, itemPath + "/id"));
            }
            validateLayoutEdgeKind(rawEdge.get("kind"), itemPath + "/kind", diagnostics);
            validateLayoutOptionalStringField(rawEdge, "sourcePort", itemPath, diagnostics);
            validateLayoutOptionalStringField(rawEdge, "sourcePath", itemPath, diagnostics);
            validateLayoutOptionalStringField(rawEdge, "targetPort", itemPath, diagnostics);
            validateLayoutOptionalStringField(rawEdge, "targetPath", itemPath, diagnostics);
            validateLayoutOptionalStringField(rawEdge, "condition", itemPath, diagnostics);
            validateLayoutOptionalStringField(rawEdge, "label", itemPath, diagnostics);
            validateLayoutEndpoint(rawEdge.get("source"), nodeIds, itemPath + "/source",
                    "source", diagnostics);
            validateLayoutEndpoint(rawEdge.get("target"), nodeIds, itemPath + "/target",
                    "target", diagnostics);
            layoutEdgeKey(rawEdge).ifPresent(layoutEdgeKeys::add);
        }
        return Optional.of(layoutEdgeKeys);
    }

    private static void validateLayoutCoversGraphEdges(List<GraphDraft.DraftEdge> edges,
                                                       Set<LayoutEdgeKey> layoutEdgeKeys,
                                                       List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < edges.size(); i++) {
            GraphDraft.DraftEdge edge = edges.get(i);
            LayoutEdgeKey key = new LayoutEdgeKey(
                    edge.kind(),
                    edge.source().nodeId(),
                    edge.source().port(),
                    edge.source().path(),
                    edge.target().nodeId(),
                    edge.target().port(),
                    edge.target().path(),
                    "route".equals(edge.kind()) ? edge.condition() : ""
            );
            if (layoutEdgeKeys.contains(key)) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.warning("visual.layout.edge.missing",
                    "Visual layout does not include graph edge '%s -> %s'; the canvas may need to redraw it."
                            .formatted(edge.source().nodeId(), edge.target().nodeId()),
                    "/edges/" + i));
        }
    }

    private static Optional<LayoutEdgeKey> layoutEdgeKey(Map<?, ?> rawEdge) {
        String source = layoutEndpointNodeId(rawEdge.get("source"));
        String target = layoutEndpointNodeId(rawEdge.get("target"));
        if (source.isBlank() || target.isBlank()) {
            return Optional.empty();
        }
        String kind = layoutEdgeKind(rawEdge.get("kind"));
        return Optional.of(new LayoutEdgeKey(
                kind,
                source,
                stringValue(rawEdge.get("sourcePort")).trim(),
                stringValue(rawEdge.get("sourcePath")).trim(),
                target,
                stringValue(rawEdge.get("targetPort")).trim(),
                stringValue(rawEdge.get("targetPath")).trim(),
                "route".equals(kind) ? stringValue(rawEdge.get("condition")).trim() : ""
        ));
    }

    private static void validateLayoutEdgeKind(Object rawKind,
                                               String targetPath,
                                               List<VisualDiagnostic> diagnostics) {
        if (rawKind == null) {
            return;
        }
        if (!(rawKind instanceof String)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.edge.fieldInvalid",
                    "Visual layout edge kind must be a string.", targetPath));
            return;
        }
        String kind = layoutEdgeKind(rawKind);
        if (!SUPPORTED_LAYOUT_EDGE_KINDS.contains(kind)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.edge.kindUnsupported",
                    "Visual layout edge kind '%s' is unsupported; visual authoring supports %s."
                            .formatted(kind, SUPPORTED_LAYOUT_EDGE_KINDS),
                    targetPath));
        }
    }

    private static String layoutEdgeKind(Object rawKind) {
        String kind = stringValue(rawKind).trim();
        return kind.isBlank() ? "data" : kind;
    }

    private static void validateLayoutOptionalStringField(Map<?, ?> rawEdge,
                                                          String field,
                                                          String itemPath,
                                                          List<VisualDiagnostic> diagnostics) {
        validateLayoutOptionalStringField(rawEdge, field, itemPath, "visual.layout.edge.fieldInvalid", diagnostics);
    }

    private static void validateLayoutOptionalStringField(Map<?, ?> rawObject,
                                                          String field,
                                                          String itemPath,
                                                          String code,
                                                          List<VisualDiagnostic> diagnostics) {
        Object value = rawObject.get(field);
        if (value != null && !(value instanceof String)) {
            diagnostics.add(VisualDiagnostic.error(code,
                    "Visual layout field '%s' must be a string.".formatted(field),
                    itemPath + "/" + field));
        }
    }

    private static Set<String> validateLayoutGroups(Object rawGroups,
                                                    Set<String> nodeIds,
                                                    List<VisualDiagnostic> diagnostics) {
        if (rawGroups == null) {
            return Set.of();
        }
        if (!(rawGroups instanceof List<?> groups)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.groups.invalid",
                    "Visual layout groups must be an array.", "/visualLayout/groups"));
            return Set.of();
        }
        Set<String> groupIds = new LinkedHashSet<>();
        Map<String, String> nodeGroupMembership = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            String itemPath = "/visualLayout/groups/" + i;
            if (!(groups.get(i) instanceof Map<?, ?> group)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.group.invalid",
                        "Visual layout group must be an object.", itemPath));
                continue;
            }
            Optional<String> groupId = validateLayoutGroupId(group.get("id"), groupIds, itemPath, diagnostics);
            validateLayoutOptionalStringField(group, "label", itemPath,
                    "visual.layout.group.fieldInvalid", diagnostics);
            validateLayoutOptionalStringField(group, "kind", itemPath,
                    "visual.layout.group.fieldInvalid", diagnostics);
            Object rawNodeIds = group.get("nodeIds");
            if (rawNodeIds == null) {
                continue;
            }
            if (!(rawNodeIds instanceof List<?> groupNodeIds)) {
                diagnostics.add(VisualDiagnostic.error("visual.layout.group.nodeIdsInvalid",
                        "Visual layout group nodeIds must be an array.", itemPath + "/nodeIds"));
                continue;
            }
            Set<String> seenGroupNodeIds = new LinkedHashSet<>();
            for (int j = 0; j < groupNodeIds.size(); j++) {
                String nodeId = stringValue(groupNodeIds.get(j)).trim();
                if (nodeId.isBlank() || !nodeIds.contains(nodeId)) {
                    diagnostics.add(VisualDiagnostic.error("visual.layout.group.unknownNode",
                            "Visual layout group references unknown graph node: " + nodeId,
                            itemPath + "/nodeIds/" + j));
                    continue;
                }
                if (!seenGroupNodeIds.add(nodeId)) {
                    diagnostics.add(VisualDiagnostic.error("visual.layout.group.duplicateNode",
                            "Visual layout group contains duplicate node id: " + nodeId,
                            itemPath + "/nodeIds/" + j));
                    continue;
                }
                if (groupId.isPresent()) {
                    String previousGroup = nodeGroupMembership.putIfAbsent(nodeId, groupId.get());
                    if (previousGroup != null && !previousGroup.equals(groupId.get())) {
                        diagnostics.add(VisualDiagnostic.error("visual.layout.group.duplicateMembership",
                                "Visual layout node '%s' is assigned to both group '%s' and group '%s'."
                                        .formatted(nodeId, previousGroup, groupId.get()),
                                itemPath + "/nodeIds/" + j));
                    }
                }
            }
        }
        return groupIds;
    }

    private static Optional<String> validateLayoutGroupId(Object rawId,
                                                          Set<String> groupIds,
                                                          String itemPath,
                                                          List<VisualDiagnostic> diagnostics) {
        if (rawId == null || stringValue(rawId).trim().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.group.idMissing",
                    "Visual layout group id is required.", itemPath + "/id"));
            return Optional.empty();
        }
        if (!(rawId instanceof String)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.group.fieldInvalid",
                    "Visual layout group id must be a string.", itemPath + "/id"));
            return Optional.empty();
        }
        String groupId = ((String) rawId).trim();
        if (!LAYOUT_GROUP_ID.matcher(groupId).matches()) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.group.idInvalid",
                    "Visual layout group id '%s' must start with a letter or underscore and contain only letters, numbers, underscores, or hyphens."
                            .formatted(groupId),
                    itemPath + "/id"));
            return Optional.empty();
        }
        if (!groupIds.add(groupId)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.group.duplicateId",
                    "Visual layout contains duplicate group id: " + groupId, itemPath + "/id"));
        }
        return Optional.of(groupId);
    }

    private static void validateLayoutViewport(Object rawViewport,
                                               List<VisualDiagnostic> diagnostics) {
        validateLayoutNumberObject(rawViewport, "/visualLayout/viewport",
                List.of(new LayoutNumberField("x", false),
                        new LayoutNumberField("y", false),
                        new LayoutNumberField("zoom", true)),
                "visual.layout.viewport.invalid", diagnostics);
    }

    private static void validateLayoutEndpoint(Object rawEndpoint,
                                               Set<String> nodeIds,
                                               String targetPath,
                                               String label,
                                               List<VisualDiagnostic> diagnostics) {
        String nodeId = layoutEndpointNodeId(rawEndpoint);
        if (nodeId.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.edge.endpointMissing",
                    "Visual layout edge %s node id is required.".formatted(label), targetPath));
            return;
        }
        if (!nodeIds.contains(nodeId)) {
            diagnostics.add(VisualDiagnostic.error("visual.layout.edge.unknownNode",
                    "Visual layout edge %s references unknown graph node: %s.".formatted(label, nodeId),
                    targetPath));
        }
    }

    private static String layoutEndpointNodeId(Object rawEndpoint) {
        if (rawEndpoint instanceof Map<?, ?> endpoint) {
            return stringValue(endpoint.get("nodeId")).trim();
        }
        return stringValue(rawEndpoint).trim();
    }

    private static void validateLayoutNumberObject(Object rawObject,
                                                   String targetPath,
                                                   List<LayoutNumberField> fields,
                                                   String code,
                                                   List<VisualDiagnostic> diagnostics) {
        if (rawObject == null) {
            return;
        }
        if (!(rawObject instanceof Map<?, ?> object)) {
            diagnostics.add(VisualDiagnostic.error(code,
                    "Visual layout value at '%s' must be an object.".formatted(targetPath), targetPath));
            return;
        }
        for (LayoutNumberField field : fields) {
            Object value = object.get(field.name());
            if (!validLayoutNumber(value, field.positive())) {
                diagnostics.add(VisualDiagnostic.error(code,
                        "Visual layout value at '%s/%s' must be a finite %snumber."
                                .formatted(targetPath, field.name(), field.positive() ? "positive " : ""),
                        targetPath + "/" + field.name()));
            }
        }
    }

    private static boolean validLayoutNumber(Object value, boolean positive) {
        if (!(value instanceof Number number)) {
            return false;
        }
        double numeric = number.doubleValue();
        return Double.isFinite(numeric) && (!positive || numeric > 0);
    }

    private record LayoutEdgeKey(String kind,
                                 String source,
                                 String sourcePort,
                                 String sourcePath,
                                 String target,
                                 String targetPort,
                                 String targetPath,
                                 String condition) {
    }

    private record LayoutNumberField(String name, boolean positive) {
    }

    private static Map<String, Map<String, GraphDraft.UnionBranchSelection>> configUnionBranchesByNode(
            GraphDraft draft) {
        Object rawNodes = draft.visualLayout().get("nodes");
        if (!(rawNodes instanceof List<?> layoutNodes)) {
            return Map.of();
        }
        Map<String, Map<String, GraphDraft.UnionBranchSelection>> byNode = new LinkedHashMap<>();
        for (Object item : layoutNodes) {
            if (!(item instanceof Map<?, ?> rawNode)) {
                continue;
            }
            String nodeId = stringValue(rawNode.get("id")).trim();
            if (nodeId.isBlank()) {
                continue;
            }
            Object rawAnnotations = rawNode.get("annotations");
            if (!(rawAnnotations instanceof Map<?, ?> annotations)) {
                continue;
            }
            Map<String, GraphDraft.UnionBranchSelection> selections =
                    unionBranchSelectionMap(annotations.get("configUnionBranches"));
            if (!selections.isEmpty()) {
                byNode.put(nodeId, selections);
            }
        }
        return byNode;
    }

    private static Map<String, GraphDraft.UnionBranchSelection> unionBranchSelectionMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, GraphDraft.UnionBranchSelection> selections = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            GraphDraft.UnionBranchSelection selection = unionBranchSelection(item);
            if (selection.selected()) {
                selections.put(key == null ? "" : String.valueOf(key).trim(), selection);
            }
        });
        return selections;
    }

    private static GraphDraft.UnionBranchSelection unionBranchSelection(Object value) {
        if (value instanceof GraphDraft.UnionBranchSelection selection) {
            return selection;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return GraphDraft.UnionBranchSelection.empty();
        }
        return new GraphDraft.UnionBranchSelection(
                stringValue(map.get("keyword")),
                integerValue(map.get("index"), -1)
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integerValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Integer arrayIndexSegment(String segment) {
        return VisualSchemaIntrospection.arrayIndexSegment(segment);
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

    private static List<Map<String, Object>> unionBranches(Map<String, Object> schema, String keyword) {
        Object raw = schema.get(keyword);
        if (!(raw instanceof List<?> branches)) {
            return List.of();
        }
        List<Map<String, Object>> schemas = new ArrayList<>();
        for (Object branch : branches) {
            Map<String, Object> branchSchema = objectProperty(branch);
            if (branchSchema != null) {
                schemas.add(branchSchema);
            }
        }
        return schemas;
    }

    private static Map<String, Object> schemaWithoutUnions(Map<String, Object> schema) {
        Map<String, Object> base = new LinkedHashMap<>(schema);
        base.remove("oneOf");
        base.remove("anyOf");
        base.remove("$comment");
        base.remove("title");
        base.remove("description");
        base.remove("examples");
        base.remove("deprecated");
        base.remove("readOnly");
        base.remove("writeOnly");
        base.remove("$defs");
        return base;
    }

    private static String schemaType(Map<String, Object> property) {
        return VisualSchemaIntrospection.schemaType(property);
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
            if (requiresExecutableDslSafePaths(sourceOperator)) {
                validateEdgeDslPathSegments(sourcePort.get().schema(), edge.source().path(),
                        edgePath + "/source/path", diagnostics);
                validateOutputPortDslPathSegment(sourcePort.get(), edgePath + "/source/port",
                        "visual.edge.sourcePortSegment.invalid", diagnostics);
            }
            Map<String, Object> sourceProperty = outputPropertyAtPath(sourceNode, sourceOperator,
                    sourcePort.get(), edge.source().path());
            if (sourceProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownSourcePath",
                        "Source port '%s' does not expose path '%s'."
                                .formatted(edge.source().port(), edge.source().path()),
                        edgePath + "/source/path"));
                continue;
            }
            if (requiresExecutableDslSafePaths(targetOperator)) {
                validateEdgeDslPathSegments(targetPort.get().schema(), edge.target().path(),
                        edgePath + "/target/path", diagnostics);
                validateInputPortDslPathSegment(targetPort.get(), edgePath + "/target/port",
                        "visual.edge.targetPortSegment.invalid", diagnostics);
            }
            Optional<GraphDraft.Binding> edgeBinding = bindingForDataEdge(targetNode, edge);
            Map<String, Object> targetProperty = edgeBinding
                    .map(binding -> targetPropertyAtPath(targetPort.get(), edge.target().path(), binding,
                            edgePath, diagnostics))
                    .orElseGet(() -> propertyAtPath(targetPort.get().schema(), edge.target().path()));
            if (targetProperty == null) {
                diagnostics.add(VisualDiagnostic.error("visual.edge.unknownTargetPath",
                        "Target port '%s' does not accept path '%s'."
                                .formatted(edge.target().port(), edge.target().path()),
                        edgePath + "/target/path"));
                continue;
            }
            Optional<String> compatibilityIssue = edgeBinding.isPresent()
                    ? schemaCompatibilityIssueForBindingTarget(edgeBinding.get(), sourceProperty, targetProperty,
                    edgePath, diagnostics)
                    : schemaCompatibilityIssue(sourceProperty, targetProperty);
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

    private static Optional<GraphDraft.Binding> bindingForDataEdge(GraphDraft.DraftNode targetNode,
                                                                   GraphDraft.DraftEdge edge) {
        return targetNode.inputs().entrySet().stream()
                .filter(entry -> dataEdgeMatchesBinding(edge, entry.getKey(), entry.getValue()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static boolean dataEdgeMatchesBinding(GraphDraft.DraftEdge edge,
                                                  String inputKey,
                                                  GraphDraft.Binding binding) {
        if (!"nodePath".equals(binding.kind())) {
            return false;
        }
        if (!binding.nodeId().equals(edge.source().nodeId())) {
            return false;
        }
        if (!binding.sourcePort().isBlank() && !binding.sourcePort().equals(edge.source().port())) {
            return false;
        }
        if (!normalizePath(binding.path()).equals(normalizePath(edge.source().path()))) {
            return false;
        }
        if (!binding.targetPort().isBlank() && !binding.targetPort().equals(edge.target().port())) {
            return false;
        }
        return normalizePath(targetInputName(inputKey, binding)).equals(normalizePath(edge.target().path()));
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

    private static boolean propertyNameAllowedBySchema(Map<String, Object> schema, String propertyName) {
        Map<String, Object> propertyNameSchema = propertyNameSchema(schema);
        if (propertyNameSchema == null) {
            return true;
        }
        return constantValueMatchesSchema(propertyName, effectivePropertyNameSchema(propertyNameSchema));
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
                                       Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                       List<VisualDiagnostic> diagnostics) {
        if ("visual-publication".equals(operator.source().kind())) {
            rejectServiceManagedPublicationConfig(node, nodePath, diagnostics);
        }
        if ("bloge:transform".equals(operator.operatorRef())) {
            validateTransformAssignmentKeys(node, nodePath, diagnostics);
        }
        if ("bloge:decisionTable".equals(operator.operatorRef())) {
            validateDecisionTableFieldKeys(node, nodePath, diagnostics);
        }
        validateNativeConfigInputConflict(node, operator, nodePath, diagnostics);
        validateConfigValue(node.config(), operator.configSchema().schema(), nodePath + "/config",
                branchSelections, "", diagnostics);
    }

    private static void validateNativeConfigInputConflict(GraphDraft.DraftNode node,
                                                          OperatorDefinition operator,
                                                          String nodePath,
                                                          List<VisualDiagnostic> diagnostics) {
        if (!usesNativeNodeBlock(operator) || !nativeOperatorLowersConfigInput(node, operator)) {
            return;
        }
        for (Map.Entry<String, GraphDraft.Binding> input : node.inputs().entrySet()) {
            String inputPath = nativeInputPath(input.getKey(), input.getValue());
            if (!usesNativeConfigInputField(inputPath)) {
                continue;
            }
            diagnostics.add(VisualDiagnostic.error("visual.input.configConflict",
                    "Native operator node '%s' cannot lower input path '%s' and configSchema values because both render to BLOGE input field 'config'."
                            .formatted(node.id(), inputPath),
                    nodePath + "/inputs/" + input.getKey()));
        }
    }

    private static boolean usesNativeNodeBlock(OperatorDefinition operator) {
        return "native".equals(operator.lowering().mode())
                && !"resource-descriptor".equals(operator.source().kind())
                && !List.of("httpResource", "bloge:decisionTable", "bloge:transform")
                .contains(operator.operatorRef());
    }

    private static boolean nativeOperatorLowersConfigInput(GraphDraft.DraftNode node, OperatorDefinition operator) {
        if ("visual-publication".equals(operator.source().kind())) {
            return true;
        }
        return node.config().keySet().stream()
                .anyMatch(key -> !EXECUTION_CONFIG_KEYS.contains(key));
    }

    private static String nativeInputPath(String inputKey, GraphDraft.Binding binding) {
        String inputName = targetInputName(inputKey, binding);
        if (binding.targetPort().isBlank() || "inputs".equals(binding.targetPort())
                || inputName.equals(binding.targetPort()) || inputName.startsWith(binding.targetPort() + ".")) {
            return inputName;
        }
        return inputName.isBlank() ? binding.targetPort() : binding.targetPort() + "." + inputName;
    }

    private static boolean usesNativeConfigInputField(String inputPath) {
        return "config".equals(inputPath) || inputPath.startsWith("config.");
    }

    private static void validateDecisionTableFieldKeys(GraphDraft.DraftNode node,
                                                       String nodePath,
                                                       List<VisualDiagnostic> diagnostics) {
        objectMap(node.config().get("inputs")).keySet().forEach(key -> {
            if (!isDslFieldName(key)) {
                diagnostics.add(VisualDiagnostic.error("visual.decisionTable.inputKey.invalid",
                        "Decision table input key '%s' cannot be rendered as a BLOGE DSL field."
                                .formatted(key),
                        nodePath + "/config/inputs/" + key));
            }
        });

        Object rawRules = node.config().get("rules");
        if (!(rawRules instanceof List<?> rules)) {
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            Map<String, Object> rule = objectMap(rules.get(i));
            Map<String, Object> output = objectMap(rule.get("output"));
            String outputPath = nodePath + "/config/rules/" + i + "/output";
            if (output.isEmpty()) {
                output = decisionTableImplicitOutput(rule);
                outputPath = nodePath + "/config/rules/" + i;
            }
            validateDecisionTableOutputFieldKeys(output, outputPath, diagnostics);
        }
    }

    private static void validateDecisionTableOutputFieldKeys(Map<?, ?> output,
                                                             String path,
                                                             List<VisualDiagnostic> diagnostics) {
        output.forEach((key, value) -> {
            String fieldName = String.valueOf(key);
            if (!isDslFieldName(fieldName)) {
                diagnostics.add(VisualDiagnostic.error("visual.decisionTable.outputKey.invalid",
                        "Decision table output key '%s' cannot be rendered as a BLOGE DSL object field."
                                .formatted(fieldName),
                        path + "/" + fieldName));
            }
            if (value instanceof Map<?, ?> nested) {
                validateDecisionTableOutputFieldKeys(nested, path + "/" + fieldName, diagnostics);
            }
        });
    }

    private static Map<String, Object> decisionTableImplicitOutput(Map<String, Object> rule) {
        Map<String, Object> output = new LinkedHashMap<>(rule);
        output.remove("conditions");
        output.remove("condition");
        output.remove("otherwise");
        output.remove("id");
        return output;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static void validateTransformAssignmentKeys(GraphDraft.DraftNode node,
                                                        String nodePath,
                                                        List<VisualDiagnostic> diagnostics) {
        Object rawAssignments = node.config().get("assignments");
        if (!(rawAssignments instanceof Map<?, ?> assignments)) {
            return;
        }
        assignments.keySet().forEach(key -> {
            String fieldName = String.valueOf(key);
            if (!isDslFieldName(fieldName)) {
                diagnostics.add(VisualDiagnostic.error("visual.transform.assignmentKey.invalid",
                        "Transform assignment key '%s' cannot be rendered as a BLOGE DSL object field."
                                .formatted(fieldName),
                        nodePath + "/config/assignments/" + fieldName));
            }
        });
    }

    private static void rejectServiceManagedPublicationConfig(GraphDraft.DraftNode node,
                                                              String nodePath,
                                                              List<VisualDiagnostic> diagnostics) {
        for (String key : SERVICE_MANAGED_PUBLICATION_CONFIG_KEYS) {
            if (node.config().containsKey(key)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.serviceManaged",
                        "Publication-backed operator config '%s' is service-managed and cannot be authored in draft config."
                                .formatted(key),
                        nodePath + "/config/" + key));
            }
        }
    }

    private static void validateConfigValue(Object value,
                                            Map<String, Object> schema,
                                            String path,
                                            Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                            String schemaPath,
                                            List<VisualDiagnostic> diagnostics) {
        Map<String, Object> effectiveSchema = selectedConfigUnionBranchSchemaAtPath(schema, schemaPath,
                branchSelections, path, diagnostics).orElse(schema);
        if (value instanceof Map<?, ?> map) {
            Object kind = map.get("kind");
            if ("constant".equals(kind)) {
                validateConfigValue(map.get("value"), effectiveSchema, path + "/value",
                        branchSelections, schemaPath, diagnostics);
                return;
            }
            if ("expression".equals(kind)) {
                return;
            }
            if ("objectTemplate".equals(kind) && map.get("fields") instanceof Map<?, ?> fields) {
                validateObjectTemplateFieldNames(fields, path + "/fields",
                        "visual.config.objectTemplateField.invalid", diagnostics);
                validateConfigObjectTemplate(fields, effectiveSchema, path, branchSelections, schemaPath,
                        diagnostics);
                return;
            }
        }
        boolean hasSchemaUnion = !unionBranches(effectiveSchema, "oneOf").isEmpty()
                || !unionBranches(effectiveSchema, "anyOf").isEmpty();
        if (hasSchemaUnion && !valueMatchesSchema(value, effectiveSchema)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Config value at '%s' must match the selected schema union.".formatted(path),
                    path));
            return;
        }
        String type = schemaType(effectiveSchema);
        if (type.isBlank() || "any".equals(type) || "opaque".equals(type)) {
            return;
        }
        if (value == null && schemaAllowsNull(effectiveSchema)) {
            validateConfigEnum(value, effectiveSchema, path, diagnostics);
            return;
        }
        if ("object".equals(type)) {
            validateConfigObject(value, effectiveSchema, path, branchSelections, schemaPath, diagnostics);
            return;
        }
        if ("array".equals(type)) {
            validateConfigArray(value, effectiveSchema, path, branchSelections, schemaPath, diagnostics);
            return;
        }
        if ("enum".equals(type)) {
            validateConfigEnum(value, effectiveSchema, path, diagnostics);
            return;
        }
        if (!configValueMatchesType(value, type)) {
            diagnostics.add(VisualDiagnostic.error("visual.config.typeMismatch",
                    "Config value at '%s' must be %s.".formatted(path, schemaTypeLabel(effectiveSchema)),
                    path));
            return;
        }
	        if (!numericValueMatchesBounds(value, effectiveSchema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s numeric bounds."
	                            .formatted(path, schemaTypeLabel(effectiveSchema)),
	                    path));
	            return;
	        }
	        if (!numericValueMatchesMultipleOf(value, effectiveSchema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s numeric multipleOf constraint."
	                            .formatted(path, schemaTypeLabel(effectiveSchema)),
	                    path));
	            return;
	        }
	        if (!stringValueMatchesLengthBounds(value, effectiveSchema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s string length constraints."
	                            .formatted(path, schemaTypeLabel(effectiveSchema)),
	                    path));
	            return;
	        }
		        if (!stringValueMatchesPattern(value, effectiveSchema)) {
		            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
		                    "Config value at '%s' must satisfy %s string pattern constraint."
		                            .formatted(path, schemaTypeLabel(effectiveSchema)),
		                    path));
		            return;
		        }
	        if (!stringValueMatchesFormat(value, effectiveSchema)) {
	            diagnostics.add(VisualDiagnostic.error("visual.config.constraintMismatch",
	                    "Config value at '%s' must satisfy %s string format constraint."
	                            .formatted(path, schemaTypeLabel(effectiveSchema)),
	                    path));
	            return;
	        }
		        validateConfigEnum(value, effectiveSchema, path, diagnostics);
		    }

    private static void validateConfigObjectTemplate(Map<?, ?> fields,
                                                     Map<String, Object> schema,
                                                     String path,
                                                     Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                                     String schemaPath,
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
            String childSchemaPath = appendPath(schemaPath, entry.getKey());
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/fields/" + entry.getKey(),
                        branchSelections, childSchemaPath, diagnostics);
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                validateConfigValue(entry.getValue(), patternSchema, path + "/fields/" + entry.getKey(),
                        branchSelections, childSchemaPath, diagnostics);
            }
            if (property != null || !patternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(residual)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/fields/" + entry.getKey()));
            } else if (residual instanceof Map<?, ?> residualSchema) {
                validateConfigValue(entry.getValue(), objectProperty(residualSchema),
                        path + "/fields/" + entry.getKey(), branchSelections, childSchemaPath, diagnostics);
            }
        }
    }

    private static void validateObjectTemplateFieldNames(Map<?, ?> fields,
                                                         String path,
                                                         String code,
                                                         List<VisualDiagnostic> diagnostics) {
        fields.keySet().forEach(key -> {
            String fieldName = String.valueOf(key);
            if (!isDslFieldName(fieldName)) {
                diagnostics.add(VisualDiagnostic.error(code,
                        "Object template field '%s' cannot be rendered as a BLOGE DSL object field."
                                .formatted(fieldName),
                        path + "/" + fieldName));
            }
        });
    }

    private static void validateDslPathSegments(SchemaEnvelope schema,
                                                String path,
                                                String diagnosticPath,
                                                List<VisualDiagnostic> diagnostics) {
        validateDslPathSegments(schema, path, diagnosticPath, "visual.binding.pathSegment.invalid",
                "Binding path segment", diagnostics);
    }

    private static void validateBindingTargetDslPathSegments(GraphDraft.Binding binding,
                                                             String inputName,
                                                             OperatorDefinition targetOperator,
                                                             String diagnosticPath,
                                                             List<VisualDiagnostic> diagnostics) {
        if (!requiresExecutableDslSafePaths(targetOperator)) {
            return;
        }
        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, binding.targetPort(),
                inputName);
        targetPort.ifPresent(port -> {
            if (!binding.targetPort().isBlank()) {
                validateInputPortDslPathSegment(port, diagnosticPath + "/targetPort",
                        "visual.binding.targetPortSegment.invalid", diagnostics);
            }
            validateDslPathSegments(port.schema(), inputName, diagnosticPath,
                    "visual.binding.targetPathSegment.invalid", "Binding target path segment", diagnostics);
        });
    }

    private static void validateEdgeDslPathSegments(SchemaEnvelope schema,
                                                    String path,
                                                    String diagnosticPath,
                                                    List<VisualDiagnostic> diagnostics) {
        validateDslPathSegments(schema, path, diagnosticPath, "visual.edge.pathSegment.invalid",
                "Edge path segment", diagnostics);
    }

    private static void validateOutputDslPathSegments(SchemaEnvelope schema,
                                                      String path,
                                                      String diagnosticPath,
                                                      List<VisualDiagnostic> diagnostics) {
        validateDslPathSegments(schema, path, diagnosticPath, "visual.output.pathSegment.invalid",
                "Output path segment", diagnostics);
    }

    private static void validateOutputPortDslPathSegment(OperatorDefinition.Port port,
                                                         String diagnosticPath,
                                                         String code,
                                                         List<VisualDiagnostic> diagnostics) {
        if (outputPortDslPathSafe(port)) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error(code,
                "Output port '%s' cannot be rendered as a BLOGE DSL output path segment."
                        .formatted(port.name()),
                diagnosticPath));
    }

    private static void validateInputPortDslPathSegment(OperatorDefinition.Port port,
                                                        String diagnosticPath,
                                                        String code,
                                                        List<VisualDiagnostic> diagnostics) {
        if (inputPortDslPathSafe(port)) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error(code,
                "Input port '%s' cannot be rendered as a BLOGE DSL input path segment."
                        .formatted(port.name()),
                diagnosticPath));
    }

    private static boolean outputPortDslPathSafe(OperatorDefinition.Port port) {
        return port == null || "output".equals(port.name()) || isDslFieldName(port.name());
    }

    private static boolean inputPortDslPathSafe(OperatorDefinition.Port port) {
        return port == null || isDslFieldName(port.name());
    }

    private static boolean requiresExecutableDslSafePaths(OperatorDefinition operator) {
        return operator != null
                && operator.runtimeReadiness() != null
                && operator.runtimeReadiness().executable();
    }

    private static void validateDslPathSegments(SchemaEnvelope schema,
                                                String path,
                                                String diagnosticPath,
                                                String code,
                                                String label,
                                                List<VisualDiagnostic> diagnostics) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalized = path.startsWith(".") ? path.substring(1) : path;
        Map<String, Object> currentSchema = schema == null ? Map.of() : schema.schema();
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if ("array".equals(schemaType(currentSchema))) {
                Integer index = arrayIndexSegment(segment);
                if (index != null) {
                    Map<String, Object> itemSchema = arrayItemSchemaForIndex(currentSchema, index);
                    currentSchema = itemSchema == null ? Map.of() : itemSchema;
                    continue;
                }
            }
            if (isDslFieldName(segment)) {
                currentSchema = childSchemaForSegment(currentSchema, segment);
                continue;
            }
            diagnostics.add(VisualDiagnostic.error(code,
                    "%s '%s' in '%s' cannot be rendered as a BLOGE DSL path segment."
                            .formatted(label, segment, path),
                    diagnosticPath));
        }
    }

    private static Map<String, Object> childSchemaForSegment(Map<String, Object> schema, String segment) {
        Map<String, Object> properties = propertiesOf(schema);
        Map<String, Object> child = objectProperty(properties.get(segment));
        if (child != null) {
            return child;
        }
        Map<String, Object> pattern = patternPropertySchema(schema, segment);
        if (pattern != null) {
            return pattern;
        }
        Map<String, Object> additional = additionalPropertySchema(schema);
        return additional == null ? Map.of() : additional;
    }

    private static void validateConfigObject(Object value,
                                             Map<String, Object> schema,
                                             String path,
                                             Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                             String schemaPath,
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
            String childSchemaPath = appendPath(schemaPath, entry.getKey());
            if (property != null) {
                validateConfigValue(entry.getValue(), property, path + "/" + entry.getKey(),
                        branchSelections, childSchemaPath, diagnostics);
            }
            for (Map<String, Object> patternSchema : patternSchemas) {
                validateConfigValue(entry.getValue(), patternSchema, path + "/" + entry.getKey(),
                        branchSelections, childSchemaPath, diagnostics);
            }
            if (property != null || !patternSchemas.isEmpty()) {
                continue;
            } else if (Boolean.FALSE.equals(residual)) {
                diagnostics.add(VisualDiagnostic.error("visual.config.unknown",
                        "Config '%s' is not declared by configSchema.".formatted(entry.getKey()),
                        path + "/" + entry.getKey()));
            } else if (residual instanceof Map<?, ?> residualSchema) {
                validateConfigValue(entry.getValue(), objectProperty(residualSchema),
                        path + "/" + entry.getKey(), branchSelections, childSchemaPath, diagnostics);
            }
        }
    }

    private static void validateConfigArray(Object value,
                                            Map<String, Object> schema,
                                            String path,
                                            Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                            String schemaPath,
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
                validateConfigValue(list.get(i), itemSchema, path + "/" + i,
                        branchSelections, appendPath(schemaPath, String.valueOf(i)), diagnostics);
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
        if ("expression".equals(binding.kind())) {
            collectExpressionConnections(binding.expr(), targetNode, targetOperator, binding.targetPort(), inputName,
                    binding.targetUnionBranches(), nodesById, operatorsByNodeId, semanticConnections);
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
                collectExpressionConnections(expression, targetNode, targetOperator, "", configTargetPath(configPath),
                        Map.of(), nodesById, operatorsByNodeId, connections);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if ("expression".equals(map.get("kind"))) {
                Object expression = map.get("expr");
                collectExpressionConnections(expression == null ? "" : String.valueOf(expression),
                        targetNode, targetOperator, "", configTargetPath(configPath), Map.of(), nodesById,
                        operatorsByNodeId, connections);
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
                                                     String targetPortName,
                                                     String targetPath,
                                                     Map<String, GraphDraft.UnionBranchSelection> branchSelections,
                                                     Map<String, GraphDraft.DraftNode> nodesById,
                                                     Map<String, OperatorDefinition> operatorsByNodeId,
                                                     Set<CanvasConnection> connections) {
        if (expression == null || expression.isBlank() || targetPath.isBlank()) {
            return;
        }
        Optional<OperatorDefinition.Port> targetPort = resolveInputPort(targetOperator, targetPortName, targetPath);
        if (targetPort.isEmpty()
                || propertyAtPath(targetPort.get().schema(), targetPath, branchSelections, null,
                new ArrayList<>()) == null) {
            return;
        }

        Matcher matcher = NODE_REFERENCE.matcher(withoutQuotedStrings(expression));
        while (matcher.find()) {
            String nodeId = matcher.group(1);
            String outputPath = matchedPath(matcher, 2, 3);
            String schemaPath = expressionPathToSchemaPath(outputPath);
            OperatorDefinition sourceOperator = operatorsByNodeId.get(nodeId);
            GraphDraft.DraftNode sourceNode = nodesById.get(nodeId);
            if (sourceNode == null || sourceOperator == null) {
                continue;
            }
            OutputReference outputReference = outputReference(sourceOperator, schemaPath);
            Optional<OperatorDefinition.Port> sourcePort = resolveOutputPort(sourceOperator, outputReference.port());
            if (sourcePort.isEmpty()
                    || outputPropertyAtPath(sourceNode, sourceOperator, sourcePort.get(),
                    outputReference.path()) == null) {
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
        Optional<GraphDraft.Binding> edgeBinding = bindingForDataEdge(nodesById.get(edge.target().nodeId()), edge);
        Map<String, GraphDraft.UnionBranchSelection> targetBranchSelections = edgeBinding
                .map(GraphDraft.Binding::targetUnionBranches)
                .orElse(Map.of());
        GraphDraft.DraftNode sourceNode = nodesById.get(edge.source().nodeId());
        if (sourcePort.isEmpty() || targetPort.isEmpty()
                || outputPropertyAtPath(sourceNode, sourceOperator, sourcePort.get(), edge.source().path()) == null
                || propertyAtPath(targetPort.get().schema(), edge.target().path(), targetBranchSelections, null,
                new ArrayList<>()) == null) {
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
        GraphDraft.DraftNode sourceNode = nodesById.get(binding.nodeId());
        if (sourcePort.isEmpty() || targetPort.isEmpty()
                || outputPropertyAtPath(sourceNode, sourceOperator, sourcePort.get(), binding.path()) == null
                || propertyAtPath(targetPort.get().schema(), inputName, binding.targetUnionBranches(), null,
                new ArrayList<>()) == null) {
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

    private static boolean isDslFieldName(String value) {
        return DSL_IDENTIFIER.matcher(value).matches() && !RESERVED_DSL_FIELD_NAMES.contains(value);
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
