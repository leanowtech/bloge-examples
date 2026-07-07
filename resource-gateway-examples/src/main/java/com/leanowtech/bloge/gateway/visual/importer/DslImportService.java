package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.ast.Expression;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Projects parsed BLOGE DSL into the generic visual graph draft model.
 */
@Service
public class DslImportService {

    private static final String TRANSFORM_OPERATOR_REF = "bloge:transform";
    private static final String DECISION_TABLE_OPERATOR_REF = "bloge:decisionTable";

    private final VisualOperatorCatalog catalog;
    private final OperatorLibraryValidator libraryValidator;

    public DslImportService(VisualOperatorCatalog catalog, OperatorLibraryValidator libraryValidator) {
        this.catalog = catalog;
        this.libraryValidator = libraryValidator;
    }

    /**
     * Parses and projects DSL into an editable visual draft without persisting the draft.
     *
     * @param request import request
     * @return visual projection
     */
    public DslVisualProjection preview(DslImportPreviewRequest request) {
        DslImportPreviewRequest normalized = request == null
                ? new DslImportPreviewRequest("", "", List.of(), List.of(), "", Map.of())
                : request;
        ProjectionState state = new ProjectionState(normalized);
        EffectiveCatalog effectiveCatalog = effectiveCatalog(normalized, state.diagnostics);
        AstNode root = parseAst(normalized.dsl(), state);
        if (root == null) {
            state.diagnostics.add(new VisualDiagnostic("ERROR", "visual.dslImport.parseFailed",
                    "BLOGE DSL could not be parsed: " + state.parseFailureMessage, "/dsl", -1, -1,
                    Map.of("sourceId", normalized.sourceId())));
            GraphDraft draft = emptyDraft("dslImport", normalized);
            return new DslVisualProjection(DslVisualProjection.SCHEMA_VERSION, normalized.sourceId(), draft,
                    DslSourceMap.empty(), DslImportCoverage.empty(), DslRoundTripSummary.partial(
                    "Parsing failed; no visual draft could be projected."), state.diagnostics);
        }
        if (!(root instanceof AstNode.GraphDef graph)) {
            state.diagnostics.add(VisualDiagnostic.error("visual.dslImport.rootUnsupported",
                    "Only top-level BLOGE graph DSL can be rendered as a visual graph draft.",
                    "/dsl", Map.of("rootKind", root.getClass().getSimpleName())));
            GraphDraft draft = emptyDraft("dslImport", normalized);
            return new DslVisualProjection(DslVisualProjection.SCHEMA_VERSION, normalized.sourceId(), draft,
                    DslSourceMap.empty(), DslImportCoverage.empty(), DslRoundTripSummary.partial(
                    "The DSL root is not a graph definition."), state.diagnostics);
        }

        return projectGraph(normalized, graph, effectiveCatalog, state, true);
    }

    private AstNode parseAst(String source, ProjectionState state) {
        try {
            return new DslCompiler(new DefaultOperatorRegistry())
                    .withDiscoveredExtensionProviders()
                    .parseAst(source);
        } catch (RuntimeException ex) {
            if (state != null) {
                state.parseFailureMessage = ex.getMessage() == null ? ex.getClass().getSimpleName()
                        : ex.getMessage();
            }
            return null;
        }
    }

    private DslVisualProjection projectGraph(DslImportPreviewRequest normalized,
                                             AstNode.GraphDef graph,
                                             EffectiveCatalog effectiveCatalog,
                                             ProjectionState state,
                                             boolean assessRoundTrip) {
        Map<String, AstNode.SchemaDeclaration> namedSchemas = collectNamedSchemas(graph);
        for (AstNode member : graph.members()) {
            projectMember(member, state, effectiveCatalog);
        }
        SchemaEnvelope inputSchema = schemaEnvelope(graph.inputSchema(), namedSchemas, state,
                "/graph/inputSchema");
        SchemaEnvelope outputSchema = schemaEnvelope(graph.outputSchema(), namedSchemas, state,
                "/graph/outputSchema");
        GraphDraft draft = buildDraft(graph, inputSchema, outputSchema, state, effectiveCatalog);
        DslImportCoverage coverage = new DslImportCoverage(
                graph.members().size(),
                state.nodes.size(),
                state.edges.size(),
                state.unsupportedSyntaxTargets.size(),
                state.missingOperatorRefs.size(),
                state.missingFunctionNames.size()
        );
        DslRoundTripSummary roundTrip = assessRoundTrip
                ? assessRoundTrip(normalized, draft, effectiveCatalog, state)
                : DslRoundTripSummary.notAssessed();
        return new DslVisualProjection(DslVisualProjection.SCHEMA_VERSION, normalized.sourceId(), draft,
                new DslSourceMap(state.nodeSpans, state.edgeSpans, state.bindingSpans),
                coverage, roundTrip, state.diagnostics);
    }

    private DslRoundTripSummary assessRoundTrip(DslImportPreviewRequest sourceRequest,
                                                GraphDraft draft,
                                                EffectiveCatalog effectiveCatalog,
                                                ProjectionState state) {
        String sourceFingerprint = semanticFingerprint(draft);
        if (!state.unsupportedSyntaxTargets.isEmpty() || !state.expressionOutputTargets.isEmpty()) {
            return DslRoundTripSummary.partial(
                    "Some DSL syntax or expression-valued decision outputs were projected for visual repair; "
                            + "regeneration is intentionally blocked until the draft is repaired.",
                    "", sourceFingerprint, "", List.of());
        }
        GraphDraftDslGenerator generator = new GraphDraftDslGenerator(effectiveCatalog.asCatalog());
        DslGenerationResult generation = generator.generate(draft);
        if (!generation.generated()) {
            return DslRoundTripSummary.partial(
                    "Generated DSL could not be produced without blocking diagnostics.",
                    generation.dsl(), sourceFingerprint, "", generation.diagnostics());
        }

        DslImportPreviewRequest generatedRequest = new DslImportPreviewRequest(
                sourceRequest.sourceId() + "#round-trip",
                generation.dsl(),
                sourceRequest.operatorLibraryIds(),
                sourceRequest.inlineLibraries(),
                "round-trip",
                sourceRequest.layout()
        );
        ProjectionState generatedState = new ProjectionState(generatedRequest);
        AstNode generatedRoot = parseAst(generation.dsl(), generatedState);
        if (!(generatedRoot instanceof AstNode.GraphDef generatedGraph)) {
            VisualDiagnostic diagnostic = new VisualDiagnostic("ERROR", "visual.dslImport.roundTripParseFailed",
                    "Generated BLOGE DSL could not be parsed: " + generatedState.parseFailureMessage,
                    "/roundTrip/generatedDsl", -1, -1, Map.of("sourceId", sourceRequest.sourceId()));
            return DslRoundTripSummary.partial(
                    "Generated DSL could not be parsed back into a graph definition.",
                    generation.dsl(), sourceFingerprint, "", List.of(diagnostic));
        }

        DslVisualProjection generatedProjection = projectGraph(generatedRequest, generatedGraph, effectiveCatalog,
                generatedState, false);
        String generatedFingerprint = semanticFingerprint(generatedProjection.draft());
        if (generatedState.diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return DslRoundTripSummary.partial(
                    "Generated DSL parsed, but the generated projection has blocking diagnostics.",
                    generation.dsl(), sourceFingerprint, generatedFingerprint, generatedState.diagnostics);
        }
        return sourceFingerprint.equals(generatedFingerprint)
                ? DslRoundTripSummary.supported(generation.dsl(), sourceFingerprint)
                : DslRoundTripSummary.drift(generation.dsl(), sourceFingerprint, generatedFingerprint);
    }

    private EffectiveCatalog effectiveCatalog(DslImportPreviewRequest request,
                                              List<VisualDiagnostic> diagnostics) {
        Map<String, OperatorDefinition> operators = new LinkedHashMap<>();
        Set<String> includedLibraryIds = new LinkedHashSet<>(request.operatorLibraryIds());
        for (OperatorDefinition operator : catalog.list(new OperatorCatalogQuery("", List.of(), false, true))) {
            if (operator == null || operator.operatorRef().isBlank()) {
                continue;
            }
            String owner = operator.source().libraryId();
            if (includedLibraryIds.isEmpty() || owner.isBlank() || includedLibraryIds.contains(owner)) {
                operators.put(operator.operatorRef(), operator);
            }
        }

        OperatorCatalogQuery functionQuery = includedLibraryIds.isEmpty()
                ? new OperatorCatalogQuery("", List.of(), false, true)
                : new OperatorCatalogQuery("", List.of(), false, true, "", "", "",
                List.of(), request.operatorLibraryIds(), List.of(), List.of(), List.of());
        Map<String, OperatorLibrary.BuiltInFunction> functions = new LinkedHashMap<>();
        for (OperatorLibrary.BuiltInFunction function : catalog.builtInFunctions(functionQuery)) {
            if (function != null && !function.name().isBlank()) {
                functions.put(function.name(), function);
            }
        }

        for (int i = 0; i < request.inlineLibraries().size(); i++) {
            String targetPrefix = "/inlineLibraries/" + i;
            OperatorLibrary library = request.inlineLibraries().get(i);
            VisualValidationResult validation = libraryValidator.validate(library);
            diagnostics.addAll(validation.diagnostics().stream()
                    .map(diagnostic -> withTargetPrefix(diagnostic, targetPrefix))
                    .toList());
            if (!validation.valid()) {
                continue;
            }
            for (OperatorDefinition operator : library.operators()) {
                if (operator != null && !operator.operatorRef().isBlank()) {
                    operators.put(operator.operatorRef(), withLibrarySource(operator, library.libraryId()));
                }
            }
            for (OperatorLibrary.BuiltInFunction function : library.builtInFunctions()) {
                if (function != null && !function.name().isBlank()) {
                    functions.put(function.name(), function);
                }
            }
        }
        return new EffectiveCatalog(operators, functions.keySet());
    }

    private static VisualDiagnostic withTargetPrefix(VisualDiagnostic diagnostic, String prefix) {
        String target = diagnostic.target().isBlank() ? prefix : prefix + diagnostic.target();
        return new VisualDiagnostic(diagnostic.level(), diagnostic.code(), diagnostic.message(), target,
                diagnostic.line(), diagnostic.column(), diagnostic.metadata());
    }

    private static OperatorDefinition withLibrarySource(OperatorDefinition operator, String libraryId) {
        OperatorDefinition.Source source = operator.source();
        OperatorDefinition.Source importedSource = new OperatorDefinition.Source(
                source.kind(),
                source.resourceId(),
                source.method(),
                source.urlTemplate(),
                source.virtual(),
                libraryId
        );
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.display(),
                importedSource,
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                operator.diagnostics()
        );
    }

    private void projectMember(AstNode member, ProjectionState state, EffectiveCatalog effectiveCatalog) {
        switch (member) {
            case AstNode.NodeDef node -> projectNode(node, state, effectiveCatalog);
            case AstNode.TransformDef transform -> projectTransform(transform, state, effectiveCatalog);
            case AstNode.DecisionTableDef decisionTable -> projectDecisionTable(decisionTable, state,
                    effectiveCatalog);
            case AstNode.BranchDef branch -> projectBranch(branch, state, effectiveCatalog);
            case AstNode.SchemaDef ignored -> {
            }
            case AstNode.CommentNode ignored -> {
            }
            case AstNode.ImportDef importDef -> unsupported(state, "visual.dslImport.importUnsupported",
                    "Graph imports are preserved as diagnostics in preview import; inline the imported graph or "
                            + "project it separately before visual editing.",
                    importDef, importMetadata(importDef));
            default -> unsupported(state, "visual.dslImport.syntaxUnsupported",
                    "This BLOGE DSL syntax is not yet projected into visual canvas primitives.",
                    member, Map.of("kind", member.getClass().getSimpleName()));
        }
    }

    private void projectNode(AstNode.NodeDef node, ProjectionState state, EffectiveCatalog effectiveCatalog) {
        Map<String, GraphDraft.Binding> inputs = bindingsFromInputBlock(node.id(), node.input(), state,
                effectiveCatalog);
        Map<String, Object> config = new LinkedHashMap<>();
        if (node.timeout() != null) {
            config.put("timeout", duration(node.timeout()));
        }
        if (node.retry() != null) {
            config.put("retryAttempts", node.retry().attempts());
            if (node.retry().backoff() != null) {
                config.put("retryBackoff", duration(node.retry().backoff()));
            }
            config.put("retryStrategy", node.retry().strategy());
        }
        if (node.fallback() != null && node.fallback().value() != null) {
            config.put("fallback", renderExpression(node.fallback().value(), Set.of()));
        }
        if (node.inputSchema() != null || node.outputSchema() != null || node.streaming()
                || node.executionMode() != null || node.workerTopic() != null) {
            config.put("dslAttributes", nodeAttributes(node));
        }
        addNode(state, node.id(), node.operatorRef(), node.description(), inputs, config,
                DslSourceSpan.point(state.request.sourceId(), node.line(), node.column(), "NodeDef"));
        if (!effectiveCatalog.operators.containsKey(node.operatorRef())) {
            state.missingOperatorRefs.add(node.operatorRef());
            state.diagnostics.add(new VisualDiagnostic("ERROR", "visual.dslImport.operatorMissing",
                    "Operator schema '%s' is not present in the effective visual catalog."
                            .formatted(node.operatorRef()),
                    "/nodes/" + node.id(), node.line(), node.column(),
                    Map.of("operatorRef", node.operatorRef())));
        }
        for (String dependency : node.dependsOn()) {
            addEdge(state, "dependency", dependency, "", "", node.id(), "", "",
                    "", DslSourceSpan.point(state.request.sourceId(), node.line(), node.column(),
                            "DependsOn"));
        }
    }

    private void projectTransform(AstNode.TransformDef transform,
                                  ProjectionState state,
                                  EffectiveCatalog effectiveCatalog) {
        Map<String, Object> assignments = new LinkedHashMap<>();
        for (AstNode.TransformField field : transform.fields()) {
            collectFunctionReferences(field.value(), state, effectiveCatalog.functions);
            assignments.put(field.name(), renderExpression(field.value(), Set.of()));
            addDataEdgesFromExpression(state, field.value(), transform.id(), field.name());
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("assignments", assignments);
        if (!transform.letBindings().isEmpty()) {
            Map<String, Object> lets = new LinkedHashMap<>();
            for (AstNode.LetBinding let : transform.letBindings()) {
                lets.put(let.name(), renderExpression(let.value(), Set.of()));
                collectFunctionReferences(let.value(), state, effectiveCatalog.functions);
                addDataEdgesFromExpression(state, let.value(), transform.id(), "let." + let.name());
            }
            config.put("letBindings", lets);
            state.expressionOutputTargets.add("/nodes/" + transform.id() + "/config/letBindings");
        }
        addNode(state, transform.id(), TRANSFORM_OPERATOR_REF, transform.description(), Map.of(), config,
                DslSourceSpan.point(state.request.sourceId(), transform.line(), transform.column(), "TransformDef"));
    }

    private void projectDecisionTable(AstNode.DecisionTableDef table,
                                      ProjectionState state,
                                      EffectiveCatalog effectiveCatalog) {
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        Map<String, Object> inputConfig = new LinkedHashMap<>();
        Set<String> paramNames = new LinkedHashSet<>();
        for (AstNode.DecisionParam param : table.params()) {
            paramNames.add(param.name());
        }
        for (AstNode.DecisionParam param : table.params()) {
            collectFunctionReferences(param.binding(), state, effectiveCatalog.functions);
            inputs.put(param.name(), bindingFromExpression(param.binding(), param.name()));
            inputConfig.put(param.name(), renderExpression(param.binding(), Set.of()));
            state.bindingSpans.put("/nodes/" + table.id() + "/inputs/" + param.name(),
                    DslSourceSpan.point(state.request.sourceId(), param.line(), param.column(), "DecisionParam"));
            addDataEdgesFromExpression(state, param.binding(), table.id(), param.name());
        }

        List<Object> rules = new ArrayList<>();
        for (AstNode.DecisionRule rule : table.rules()) {
            Map<String, Object> ruleMap = new LinkedHashMap<>();
            if (rule.isOtherwise()) {
                ruleMap.put("otherwise", true);
            }
            Map<String, Object> conditions = new LinkedHashMap<>();
            for (AstNode.DecisionCondition condition : rule.conditions()) {
                collectFunctionReferences(condition.predicate(), state, effectiveCatalog.functions);
                conditions.put(condition.paramName(), renderExpression(condition.predicate(), paramNames));
            }
            if (!conditions.isEmpty()) {
                ruleMap.put("conditions", conditions);
            }
            ruleMap.put("output", decisionOutput(rule, state, table.id(), effectiveCatalog));
            rules.add(ruleMap);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inputs", inputConfig);
        config.put("hitPolicy", table.hitPolicy().name().toLowerCase(Locale.ROOT));
        config.put("outputType", table.outputTypeAnnotation() == null || table.outputTypeAnnotation().isBlank()
                ? "{ decision: String }"
                : table.outputTypeAnnotation());
        config.put("rules", rules);
        addNode(state, table.id(), DECISION_TABLE_OPERATOR_REF, table.description(), inputs, config,
                DslSourceSpan.point(state.request.sourceId(), table.line(), table.column(), "DecisionTableDef"));
    }

    private Map<String, Object> decisionOutput(AstNode.DecisionRule rule,
                                               ProjectionState state,
                                               String tableId,
                                               EffectiveCatalog effectiveCatalog) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (!rule.namedOutputs().isEmpty()) {
            for (Map.Entry<String, Expression> entry : rule.namedOutputs().entrySet()) {
                collectFunctionReferences(entry.getValue(), state, effectiveCatalog.functions);
                output.put(entry.getKey(), literalValue(entry.getValue()).orElseGet(() -> {
                    state.expressionOutputTargets.add("/nodes/" + tableId + "/config/rules/" + rule.line()
                            + "/output/" + entry.getKey());
                    return renderExpression(entry.getValue(), Set.of());
                }));
            }
            return output;
        }
        Expression expression = rule.output();
        collectFunctionReferences(expression, state, effectiveCatalog.functions);
        output.put("value", literalValue(expression).orElseGet(() -> {
            state.expressionOutputTargets.add("/nodes/" + tableId + "/config/rules/" + rule.line() + "/output/value");
            return renderExpression(expression, Set.of());
        }));
        return output;
    }

    private void projectBranch(AstNode.BranchDef branch,
                               ProjectionState state,
                               EffectiveCatalog effectiveCatalog) {
        collectFunctionReferences(branch.condition(), state, effectiveCatalog.functions);
        List<String> sources = collectNodeReferences(branch.condition()).stream().toList();
        if (sources.isEmpty()) {
            unsupported(state, "visual.dslImport.branchConditionUnsupported",
                    "Branch conditions must reference an upstream node to be drawn as route edges.",
                    branch, Map.of("condition", renderExpression(branch.condition(), Set.of())));
            return;
        }
        String source = sources.get(0);
        String condition = renderExpression(branch.condition(), Set.of());
        for (AstNode.BranchCase branchCase : branch.cases()) {
            addEdge(state, "route", source, "", "", branchCase.target(), "", "",
                    condition + " == " + renderExpression(branchCase.value(), Set.of()),
                    DslSourceSpan.point(state.request.sourceId(), branch.line(), branch.column(), "BranchCase"));
        }
        if (branch.otherwise() != null && !branch.otherwise().isBlank()) {
            addEdge(state, "route", source, "", "", branch.otherwise(), "", "",
                    "otherwise", DslSourceSpan.point(state.request.sourceId(), branch.line(), branch.column(),
                            "BranchOtherwise"));
        }
        if (branch.inclusive()) {
            state.diagnostics.add(new VisualDiagnostic("WARNING", "visual.dslImport.inclusiveBranchPartial",
                    "Inclusive branch was rendered as route edges; verify OR-split semantics before publishing.",
                    "/edges", branch.line(), branch.column()));
        }
    }

    private Map<String, GraphDraft.Binding> bindingsFromInputBlock(String nodeId,
                                                                   AstNode.InputBlock input,
                                                                   ProjectionState state,
                                                                   EffectiveCatalog effectiveCatalog) {
        if (input == null || input.bindings().isEmpty()) {
            return Map.of();
        }
        Map<String, GraphDraft.Binding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, Expression> entry : input.bindings().entrySet()) {
            collectFunctionReferences(entry.getValue(), state, effectiveCatalog.functions);
            bindings.put(entry.getKey(), bindingFromExpression(entry.getValue(), entry.getKey()));
            state.bindingSpans.put("/nodes/" + nodeId + "/inputs/" + entry.getKey(),
                    DslSourceSpan.point(state.request.sourceId(), entry.getValue().line(),
                            entry.getValue().column(), "InputBinding"));
            addDataEdgesFromExpression(state, entry.getValue(), nodeId, entry.getKey());
        }
        return bindings;
    }

    private GraphDraft.Binding bindingFromExpression(Expression expression, String targetPath) {
        if (expression instanceof Expression.ContextPath contextPath) {
            return GraphDraft.Binding.contextPath(dottedPath(contextPath.segments()), "inputs", targetPath);
        }
        if (expression instanceof Expression.NodeOutputPath nodeOutputPath) {
            return GraphDraft.Binding.nodePath(nodeOutputPath.nodeId(), "output",
                    dottedPath(nodeOutputPath.segments()), "inputs", targetPath);
        }
        Optional<Object> literal = literalValue(expression);
        if (literal.isPresent()) {
            return new GraphDraft.Binding("constant", literal.get(), "", "", "", "inputs",
                    targetPath, "", Map.of());
        }
        if (expression instanceof Expression.ObjectLiteral objectLiteral) {
            Map<String, GraphDraft.Binding> fields = new LinkedHashMap<>();
            objectLiteral.fields().forEach((key, value) -> fields.put(key, bindingFromExpression(value, key)));
            return new GraphDraft.Binding("objectTemplate", null, "", "", "", "inputs",
                    targetPath, "", fields);
        }
        return new GraphDraft.Binding("expression", null, "", "", "", "inputs",
                targetPath, renderExpression(expression, Set.of()), Map.of());
    }

    private void addDataEdgesFromExpression(ProjectionState state,
                                            Expression expression,
                                            String targetNodeId,
                                            String targetPath) {
        for (String sourceNodeId : collectNodeReferences(expression)) {
            String sourcePath = sourcePath(expression, sourceNodeId).orElse("");
            addEdge(state, "data", sourceNodeId, "output", sourcePath, targetNodeId, "inputs",
                    targetPath, "", DslSourceSpan.point(state.request.sourceId(), expression.line(),
                            expression.column(), expression.getClass().getSimpleName()));
        }
    }

    private void addNode(ProjectionState state,
                         String id,
                         String operatorRef,
                         String description,
                         Map<String, GraphDraft.Binding> inputs,
                         Map<String, Object> config,
                         DslSourceSpan sourceSpan) {
        int index = state.nodes.size();
        GraphDraft.Position position = new GraphDraft.Position(120 + (index % 4) * 360.0,
                120 + (index / 4) * 220.0);
        Map<String, Object> normalizedConfig = new LinkedHashMap<>(config == null ? Map.of() : config);
        if (description != null && !description.isBlank()) {
            normalizedConfig.put("description", description);
        }
        state.nodes.add(new GraphDraft.DraftNode(id, operatorRef, id, inputs, normalizedConfig, position));
        state.nodeSpans.put(id, sourceSpan);
    }

    private void addEdge(ProjectionState state,
                         String kind,
                         String sourceNodeId,
                         String sourcePort,
                         String sourcePath,
                         String targetNodeId,
                         String targetPort,
                         String targetPath,
                         String condition,
                         DslSourceSpan sourceSpan) {
        if (sourceNodeId == null || sourceNodeId.isBlank()
                || targetNodeId == null || targetNodeId.isBlank()
                || sourceNodeId.equals(targetNodeId)) {
            return;
        }
        String baseId = sanitizeEdgeId(kind + "_" + sourceNodeId + "_" + targetNodeId + "_" + targetPath);
        String id = baseId;
        int suffix = 2;
        while (!state.edgeIds.add(id)) {
            id = baseId + "_" + suffix++;
        }
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge(id, kind,
                new GraphDraft.Endpoint(sourceNodeId, sourcePort, sourcePath),
                new GraphDraft.Endpoint(targetNodeId, targetPort, targetPath),
                condition);
        state.edges.add(edge);
        state.edgeSpans.put(id, sourceSpan);
    }

    private GraphDraft buildDraft(AstNode.GraphDef graph,
                                  SchemaEnvelope inputSchema,
                                  SchemaEnvelope outputSchema,
                                  ProjectionState state,
                                  EffectiveCatalog effectiveCatalog) {
        Map<String, Object> graphContract = new LinkedHashMap<>();
        graphContract.put("inputSchema", inputSchema);
        graphContract.put("outputSchema", outputSchema);
        graphContract.put("schemaSource", "dsl");
        if (graph.streamingOutputNodeId() != null) {
            graphContract.put("streamingOutputNodeId", graph.streamingOutputNodeId());
        }
        if (!graph.streamingInputs().isEmpty()) {
            graphContract.put("streamingInputs", graph.streamingInputs());
        }

        Map<String, Object> visualLayout = new LinkedHashMap<>(state.request.layout());
        visualLayout.put("import", Map.of(
                "mode", state.request.mode(),
                "sourceId", state.request.sourceId(),
                "schemaNeutral", true
        ));
        visualLayout.put("graphContract", graphContract);

        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, OperatorDefinition> snapshots = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : state.nodes) {
            OperatorDefinition operator = effectiveCatalog.operators.get(node.operatorRef());
            if (operator != null) {
                fingerprints.put(node.id(), operator.fingerprint());
                snapshots.put(node.id(), operator);
            }
        }

        GraphDraft.OutputSelection output = state.nodes.isEmpty()
                ? GraphDraft.OutputSelection.empty()
                : new GraphDraft.OutputSelection(state.nodes.get(state.nodes.size() - 1).id(), "");
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "dsl-preview:" + state.request.sourceId(),
                0,
                graph.name(),
                "demo-tenant",
                "local",
                "local",
                GraphDraft.STATUS_DRAFT,
                inputSchema,
                outputSchema,
                state.nodes,
                state.edges,
                visualLayout,
                Map.of(),
                output,
                fingerprints,
                snapshots,
                GraphDraft.RevisionMetadata.empty()
        );
    }

    private static GraphDraft emptyDraft(String graphName, DslImportPreviewRequest request) {
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "dsl-preview:" + request.sourceId(),
                0,
                graphName,
                "demo-tenant",
                "local",
                "local",
                GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(),
                List.of(),
                List.of(),
                Map.of("import", Map.of(
                        "mode", request.mode(),
                        "sourceId", request.sourceId(),
                        "schemaNeutral", true
                )),
                Map.of(),
                GraphDraft.OutputSelection.empty(),
                Map.of(),
                Map.of(),
                GraphDraft.RevisionMetadata.empty()
        );
    }

    private Map<String, AstNode.SchemaDeclaration> collectNamedSchemas(AstNode.GraphDef graph) {
        Map<String, AstNode.SchemaDeclaration> schemas = new LinkedHashMap<>();
        for (AstNode member : graph.members()) {
            if (member instanceof AstNode.SchemaDef schemaDef) {
                schemas.put(schemaDef.name(), schemaDef.body());
            }
        }
        return schemas;
    }

    private SchemaEnvelope schemaEnvelope(AstNode.SchemaDeclaration declaration,
                                          Map<String, AstNode.SchemaDeclaration> namedSchemas,
                                          ProjectionState state,
                                          String target) {
        if (declaration == null) {
            return SchemaEnvelope.opaque();
        }
        if (declaration instanceof AstNode.SchemaDeclaration.SchemaRef ref) {
            AstNode.SchemaDeclaration resolved = namedSchemas.get(ref.name());
            if (resolved == null) {
                state.diagnostics.add(new VisualDiagnostic("WARNING", "visual.dslImport.schemaRefUnresolved",
                        "Schema reference '%s' is not declared in this DSL source; using opaque schema."
                                .formatted(ref.name()),
                        target, ref.line(), ref.column(), Map.of("schemaRef", ref.name())));
                return SchemaEnvelope.opaque();
            }
            return schemaEnvelope(resolved, namedSchemas, state, target);
        }
        if (declaration instanceof AstNode.SchemaDeclaration.InlineSchema inlineSchema) {
            return SchemaEnvelope.object(schemaProperties(inlineSchema, namedSchemas, state, target),
                    inlineSchema.fields().stream()
                            .filter(AstNode.FieldDeclaration::required)
                            .map(AstNode.FieldDeclaration::name)
                            .toList());
        }
        return SchemaEnvelope.opaque();
    }

    private Map<String, Object> schemaProperties(AstNode.SchemaDeclaration.InlineSchema inlineSchema,
                                                 Map<String, AstNode.SchemaDeclaration> namedSchemas,
                                                 ProjectionState state,
                                                 String target) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (AstNode.FieldDeclaration field : inlineSchema.fields()) {
            Map<String, Object> schema = new LinkedHashMap<>(schemaForField(field, namedSchemas, state,
                    target + "/properties/" + field.name()));
            literalValue(field.defaultValue()).ifPresent(value -> schema.put("default", value));
            if (!field.allowedValues().isEmpty()) {
                schema.put("enum", field.allowedValues().stream()
                        .map(this::literalValue)
                        .flatMap(Optional::stream)
                        .toList());
            }
            properties.put(field.name(), schema);
        }
        return properties;
    }

    private Map<String, Object> schemaForField(AstNode.FieldDeclaration field,
                                               Map<String, AstNode.SchemaDeclaration> namedSchemas,
                                               ProjectionState state,
                                               String target) {
        if (field.nested() instanceof AstNode.SchemaDeclaration.InlineSchema nested) {
            return SchemaEnvelope.object(schemaProperties(nested, namedSchemas, state, target),
                    nested.fields().stream()
                            .filter(AstNode.FieldDeclaration::required)
                            .map(AstNode.FieldDeclaration::name)
                            .toList()).schema();
        }
        if (field.nested() instanceof AstNode.SchemaDeclaration.SchemaRef ref) {
            AstNode.SchemaDeclaration resolved = namedSchemas.get(ref.name());
            if (resolved instanceof AstNode.SchemaDeclaration.InlineSchema inlineSchema) {
                return SchemaEnvelope.object(schemaProperties(inlineSchema, namedSchemas, state, target),
                        inlineSchema.fields().stream()
                                .filter(AstNode.FieldDeclaration::required)
                                .map(AstNode.FieldDeclaration::name)
                                .toList()).schema();
            }
            state.diagnostics.add(new VisualDiagnostic("WARNING", "visual.dslImport.schemaRefUnresolved",
                    "Schema reference '%s' is not declared in this DSL source; using opaque object field."
                            .formatted(ref.name()),
                    target, ref.line(), ref.column(), Map.of("schemaRef", ref.name())));
        }
        String type = field.typeName() == null ? "" : field.typeName().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "string", "date", "datetime" -> Map.of("type", "string");
            case "int", "integer", "long" -> Map.of("type", "integer");
            case "float", "double", "decimal", "number" -> Map.of("type", "number");
            case "bool", "boolean" -> Map.of("type", "boolean");
            case "array", "list" -> Map.of("type", "array", "items", Map.of("type", "object"));
            case "object", "map", "json" -> Map.of("type", "object", "additionalProperties", true);
            default -> Map.of();
        };
    }

    private static Map<String, Object> nodeAttributes(AstNode.NodeDef node) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("streaming", node.streaming());
        if (node.bufferSize() != null) {
            attributes.put("bufferSize", node.bufferSize());
        }
        if (node.executionMode() != null && !node.executionMode().isBlank()) {
            attributes.put("executionMode", node.executionMode());
        }
        if (node.workerTopic() != null && !node.workerTopic().isBlank()) {
            attributes.put("workerTopic", node.workerTopic());
        }
        if (node.scope() != null) {
            attributes.put("scope", node.scope().name());
        }
        if (node.inputSchema() != null) {
            attributes.put("inputSchemaDeclared", true);
        }
        if (node.outputSchema() != null) {
            attributes.put("outputSchemaDeclared", true);
        }
        return attributes;
    }

    private static Map<String, Object> importMetadata(AstNode.ImportDef importDef) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (importDef.path() != null && !importDef.path().isBlank()) {
            metadata.put("path", importDef.path());
        }
        if (importDef.alias() != null && !importDef.alias().isBlank()) {
            metadata.put("alias", importDef.alias());
        }
        return metadata;
    }

    private void unsupported(ProjectionState state,
                             String code,
                             String message,
                             AstNode node,
                             Map<String, Object> metadata) {
        String target = "/dsl/" + node.line() + ":" + node.column();
        state.unsupportedSyntaxTargets.add(target);
        state.diagnostics.add(new VisualDiagnostic("WARNING", code, message, target,
                node.line(), node.column(), metadata));
    }

    private void collectFunctionReferences(Expression expression,
                                           ProjectionState state,
                                           Set<String> availableFunctions) {
        for (String functionName : collectFunctionNames(expression)) {
            if (!availableFunctions.contains(functionName) && state.missingFunctionNames.add(functionName)) {
                state.diagnostics.add(new VisualDiagnostic("ERROR", "visual.dslImport.functionMissing",
                        "Built-in function '%s' is not present in the effective function catalog."
                                .formatted(functionName),
                        "/expressions", expression.line(), expression.column(),
                        Map.of("function", functionName)));
            }
        }
    }

    private Set<String> collectFunctionNames(Expression expression) {
        Set<String> names = new LinkedHashSet<>();
        walkExpressions(expression, expr -> {
            if (expr instanceof Expression.FunctionCall functionCall) {
                names.add(functionCall.name());
            }
        });
        return names;
    }

    private Set<String> collectNodeReferences(Expression expression) {
        Set<String> refs = new LinkedHashSet<>();
        walkExpressions(expression, expr -> {
            if (expr instanceof Expression.NodeOutputPath nodeOutputPath) {
                refs.add(nodeOutputPath.nodeId());
            } else if (expr instanceof Expression.NodeStreamPath streamPath) {
                refs.add(streamPath.nodeId());
            } else if (expr instanceof Expression.TransformFieldPath transformFieldPath) {
                refs.add(transformFieldPath.transformId());
            } else if (expr instanceof Expression.LoopPrevPath loopPrevPath) {
                refs.add(loopPrevPath.nodeId());
            }
        });
        return refs;
    }

    private Optional<String> sourcePath(Expression expression, String sourceNodeId) {
        if (expression instanceof Expression.NodeOutputPath nodeOutputPath
                && nodeOutputPath.nodeId().equals(sourceNodeId)) {
            return Optional.of(dottedPath(nodeOutputPath.segments()));
        }
        if (expression instanceof Expression.NodeStreamPath streamPath
                && streamPath.nodeId().equals(sourceNodeId)) {
            return Optional.of(dottedPath(streamPath.segments()));
        }
        if (expression instanceof Expression.TransformFieldPath transformFieldPath
                && transformFieldPath.transformId().equals(sourceNodeId)) {
            return Optional.of(transformFieldPath.fieldName());
        }
        return Optional.empty();
    }

    private void walkExpressions(Expression expression, java.util.function.Consumer<Expression> consumer) {
        if (expression == null) {
            return;
        }
        consumer.accept(expression);
        switch (expression) {
            case Expression.ObjectLiteral objectLiteral ->
                    objectLiteral.fields().values().forEach(value -> walkExpressions(value, consumer));
            case Expression.ArrayLiteral arrayLiteral ->
                    arrayLiteral.elements().forEach(value -> walkExpressions(value, consumer));
            case Expression.BinaryOp binaryOp -> {
                walkExpressions(binaryOp.left(), consumer);
                walkExpressions(binaryOp.right(), consumer);
            }
            case Expression.ChainedComparisonExpr chain -> {
                walkExpressions(chain.lower(), consumer);
                walkExpressions(chain.value(), consumer);
                walkExpressions(chain.upper(), consumer);
            }
            case Expression.InExpr inExpr -> {
                walkExpressions(inExpr.left(), consumer);
                walkExpressions(inExpr.right(), consumer);
            }
            case Expression.UnaryOp unaryOp -> walkExpressions(unaryOp.operand(), consumer);
            case Expression.ConditionalExpr conditional -> {
                walkExpressions(conditional.condition(), consumer);
                walkExpressions(conditional.thenBranch(), consumer);
                walkExpressions(conditional.elseBranch(), consumer);
            }
            case Expression.NullCoalesce nullCoalesce -> {
                walkExpressions(nullCoalesce.primary(), consumer);
                walkExpressions(nullCoalesce.fallback(), consumer);
            }
            case Expression.FunctionCall functionCall ->
                    functionCall.args().forEach(value -> walkExpressions(value, consumer));
            case Expression.WhenExpr whenExpr -> {
                walkExpressions(whenExpr.subject(), consumer);
                for (Expression.WhenClause clause : whenExpr.clauses()) {
                    walkExpressions(clause.condition(), consumer);
                    walkExpressions(clause.result(), consumer);
                }
                walkExpressions(whenExpr.otherwise(), consumer);
            }
            case Expression.GroupExpr groupExpr -> walkExpressions(groupExpr.inner(), consumer);
            case Expression.LambdaExpr lambdaExpr -> walkExpressions(lambdaExpr.body(), consumer);
            case Expression.MethodCallExpr methodCall -> {
                walkExpressions(methodCall.receiver(), consumer);
                methodCall.args().forEach(value -> walkExpressions(value, consumer));
            }
            case Expression.IndexExpr indexExpr -> {
                walkExpressions(indexExpr.receiver(), consumer);
                walkExpressions(indexExpr.index(), consumer);
            }
            case Expression.MemberAccessExpr memberAccess -> walkExpressions(memberAccess.receiver(), consumer);
            case Expression.StringInterpolation interpolation -> interpolation.parts().forEach(part -> {
                if (part instanceof Expression.InterpolationPart.ExpressionPart expressionPart) {
                    walkExpressions(expressionPart.expression(), consumer);
                }
            });
            default -> {
            }
        }
    }

    private String renderExpression(Expression expression, Set<String> localNames) {
        if (expression == null) {
            return "null";
        }
        return switch (expression) {
            case Expression.ContextPath contextPath -> renderContextPath(contextPath, localNames);
            case Expression.NodeOutputPath nodeOutputPath -> nodeOutputPath.nodeId()
                    + (nodeOutputPath.safeOutputNavigation() ? "?.output" : ".output")
                    + emitSegments(nodeOutputPath.segments());
            case Expression.StringLiteral stringLiteral -> quote(stringLiteral.value());
            case Expression.StringInterpolation interpolation -> renderInterpolation(interpolation, localNames);
            case Expression.NumberLiteral numberLiteral -> renderNumber(numberLiteral.value());
            case Expression.BooleanLiteral booleanLiteral -> String.valueOf(booleanLiteral.value());
            case Expression.DurationLiteral durationLiteral -> duration(durationLiteral.duration());
            case Expression.ObjectLiteral objectLiteral -> renderObjectLiteral(objectLiteral.fields(), localNames);
            case Expression.ArrayLiteral arrayLiteral -> renderArrayLiteral(arrayLiteral.elements(), localNames);
            case Expression.BinaryOp binaryOp -> renderExpression(binaryOp.left(), localNames)
                    + " " + binaryOperator(binaryOp.op()) + " "
                    + renderExpression(binaryOp.right(), localNames);
            case Expression.ChainedComparisonExpr chain -> renderExpression(chain.lower(), localNames)
                    + " " + binaryOperator(chain.lowerOperator()) + " "
                    + renderExpression(chain.value(), localNames)
                    + " " + binaryOperator(chain.upperOperator()) + " "
                    + renderExpression(chain.upper(), localNames);
            case Expression.InExpr inExpr -> renderExpression(inExpr.left(), localNames)
                    + " in " + renderExpression(inExpr.right(), localNames);
            case Expression.UnaryOp unaryOp -> unaryOperator(unaryOp.op())
                    + renderExpression(unaryOp.operand(), localNames);
            case Expression.ConditionalExpr conditional -> renderExpression(conditional.condition(), localNames)
                    + " ? " + renderExpression(conditional.thenBranch(), localNames)
                    + " : " + renderExpression(conditional.elseBranch(), localNames);
            case Expression.NullCoalesce nullCoalesce -> renderExpression(nullCoalesce.primary(), localNames)
                    + " ?? " + renderExpression(nullCoalesce.fallback(), localNames);
            case Expression.FunctionCall functionCall -> functionCall.name() + "("
                    + joinRendered(functionCall.args(), localNames) + ")";
            case Expression.WhenExpr whenExpr -> renderWhen(whenExpr, localNames);
            case Expression.TransformFieldPath transformFieldPath -> transformFieldPath.transformId()
                    + (transformFieldPath.safeNavigation() ? "?." : ".")
                    + transformFieldPath.fieldName();
            case Expression.GroupExpr groupExpr -> "(" + renderExpression(groupExpr.inner(), localNames) + ")";
            case Expression.ItemPath itemPath -> itemPath.segments().isEmpty()
                    ? "item"
                    : "item" + emitSegments(itemPath.segments());
            case Expression.ItemIndex ignored -> "itemIndex";
            case Expression.LoopPrevPath loopPrevPath -> "prev"
                    + (loopPrevPath.safeNodeNavigation() ? "?." : ".")
                    + loopPrevPath.nodeId()
                    + emitSegments(loopPrevPath.segments());
            case Expression.LoopCarryPath loopCarryPath -> "carry" + emitSegments(loopCarryPath.segments());
            case Expression.LoopIterationRef ignored -> "loopIteration";
            case Expression.NodeStreamPath streamPath -> streamPath.nodeId()
                    + (streamPath.safeStreamNavigation() ? "?.stream" : ".stream")
                    + emitSegments(streamPath.segments());
            case Expression.LambdaExpr lambda -> (lambda.params().size() > 1
                    ? "(" + String.join(", ", lambda.params()) + ")"
                    : lambda.params().get(0)) + " -> " + renderExpression(lambda.body(), localNames);
            case Expression.MethodCallExpr methodCall -> renderExpression(methodCall.receiver(), localNames)
                    + (methodCall.safeNavigation() ? "?." : ".")
                    + methodCall.method()
                    + "(" + joinRendered(methodCall.args(), localNames) + ")";
            case Expression.IndexExpr indexExpr -> renderExpression(indexExpr.receiver(), localNames)
                    + (indexExpr.safeNavigation() ? "?[" : "[")
                    + renderExpression(indexExpr.index(), localNames) + "]";
            case Expression.MemberAccessExpr memberAccess -> renderExpression(memberAccess.receiver(), localNames)
                    + (memberAccess.safeNavigation() ? "?." : ".")
                    + memberAccess.name();
            case Expression.LambdaParamPath lambdaParamPath -> lambdaParamPath.paramName()
                    + emitSegments(lambdaParamPath.segments());
        };
    }

    private String renderContextPath(Expression.ContextPath contextPath, Set<String> localNames) {
        List<Expression.PathSegment> segments = contextPath.segments();
        if (!segments.isEmpty() && localNames.contains(segments.get(0).name())) {
            return segments.get(0).name() + emitSegments(segments.subList(1, segments.size()));
        }
        return "ctx" + emitSegments(segments);
    }

    private String renderInterpolation(Expression.StringInterpolation interpolation, Set<String> localNames) {
        StringBuilder builder = new StringBuilder("\"");
        for (Expression.InterpolationPart part : interpolation.parts()) {
            if (part instanceof Expression.InterpolationPart.LiteralPart literalPart) {
                builder.append(escapeString(literalPart.text()));
            } else if (part instanceof Expression.InterpolationPart.ExpressionPart expressionPart) {
                builder.append("${").append(renderExpression(expressionPart.expression(), localNames)).append("}");
            }
        }
        return builder.append("\"").toString();
    }

    private String renderObjectLiteral(Map<String, Expression> fields, Set<String> localNames) {
        StringJoiner joiner = new StringJoiner(", ", "{ ", " }");
        fields.forEach((key, value) -> joiner.add(key + ": " + renderExpression(value, localNames)));
        return joiner.toString();
    }

    private String renderArrayLiteral(Collection<Expression> elements, Set<String> localNames) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        elements.forEach(value -> joiner.add(renderExpression(value, localNames)));
        return joiner.toString();
    }

    private String renderWhen(Expression.WhenExpr whenExpr, Set<String> localNames) {
        StringBuilder builder = new StringBuilder("when");
        if (whenExpr.subject() != null) {
            builder.append(" ").append(renderExpression(whenExpr.subject(), localNames));
        }
        builder.append(" { ");
        for (Expression.WhenClause clause : whenExpr.clauses()) {
            builder.append(renderExpression(clause.condition(), localNames))
                    .append(" -> ")
                    .append(renderExpression(clause.result(), localNames))
                    .append(" ");
        }
        if (whenExpr.otherwise() != null) {
            builder.append("otherwise -> ")
                    .append(renderExpression(whenExpr.otherwise(), localNames))
                    .append(" ");
        }
        return builder.append("}").toString();
    }

    private String joinRendered(List<Expression> expressions, Set<String> localNames) {
        StringJoiner joiner = new StringJoiner(", ");
        expressions.forEach(expression -> joiner.add(renderExpression(expression, localNames)));
        return joiner.toString();
    }

    private Optional<Object> literalValue(Expression expression) {
        if (expression == null) {
            return Optional.empty();
        }
        if (expression instanceof Expression.StringLiteral stringLiteral) {
            return Optional.of(stringLiteral.value());
        }
        if (expression instanceof Expression.NumberLiteral numberLiteral) {
            double value = numberLiteral.value();
            if (value == Math.floor(value) && !Double.isInfinite(value) && !Double.isNaN(value)) {
                return Optional.of((long) value);
            }
            return Optional.of(value);
        }
        if (expression instanceof Expression.BooleanLiteral booleanLiteral) {
            return Optional.of(booleanLiteral.value());
        }
        if (expression instanceof Expression.DurationLiteral durationLiteral) {
            return Optional.of(duration(durationLiteral.duration()));
        }
        if (expression instanceof Expression.ObjectLiteral objectLiteral) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Expression> entry : objectLiteral.fields().entrySet()) {
                Optional<Object> value = literalValue(entry.getValue());
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                values.put(entry.getKey(), value.get());
            }
            return Optional.of(values);
        }
        if (expression instanceof Expression.ArrayLiteral arrayLiteral) {
            List<Object> values = new ArrayList<>();
            for (Expression element : arrayLiteral.elements()) {
                Optional<Object> value = literalValue(element);
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                values.add(value.get());
            }
            return Optional.of(values);
        }
        return Optional.empty();
    }

    private static String duration(AstNode.DurationValue value) {
        return value.amount() + value.unit();
    }

    private static String dottedPath(List<Expression.PathSegment> segments) {
        return String.join(".", segments.stream().map(Expression.PathSegment::name).toList());
    }

    private static String emitSegments(List<Expression.PathSegment> segments) {
        StringBuilder builder = new StringBuilder();
        for (Expression.PathSegment segment : segments) {
            builder.append(segment.safeNavigation() ? "?." : ".").append(segment.name());
        }
        return builder.toString();
    }

    private static String renderNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value) && !Double.isNaN(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private static String quote(String value) {
        return "\"" + escapeString(value) + "\"";
    }

    private static String escapeString(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("${", "\\${");
    }

    private static String binaryOperator(Expression.BinaryOperator operator) {
        return switch (operator) {
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case SLASH -> "/";
            case PERCENT -> "%";
            case EQ_EQ -> "==";
            case BANG_EQ -> "!=";
            case GT -> ">";
            case LT -> "<";
            case GT_EQ -> ">=";
            case LT_EQ -> "<=";
            case AMP_AMP -> "&&";
            case PIPE_PIPE -> "||";
        };
    }

    private static String unaryOperator(Expression.UnaryOperator operator) {
        return switch (operator) {
            case NEGATE -> "-";
            case NOT -> "!";
        };
    }

    private static String sanitizeEdgeId(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
        return sanitized.isBlank() ? "edge" : sanitized;
    }

    private static String semanticFingerprint(GraphDraft draft) {
        if (draft == null) {
            return "null";
        }
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("graphName", draft.graphName());
        semantic.put("inputSchema", draft.inputSchema());
        semantic.put("outputSchema", graphContractValue(draft, "outputSchema"));
        semantic.put("nodes", draft.nodes().stream()
                .map(DslImportService::nodeSemantic)
                .toList());
        semantic.put("edges", draft.edges().stream()
                .map(DslImportService::edgeSemantic)
                .sorted(Comparator.comparing(DslImportService::canonicalValue))
                .toList());
        semantic.put("output", Map.of(
                "nodeId", draft.output().nodeId(),
                "path", draft.output().path()
        ));
        return canonicalValue(semantic);
    }

    private static Map<String, Object> nodeSemantic(GraphDraft.DraftNode node) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("id", node.id());
        semantic.put("operatorRef", node.operatorRef());
        semantic.put("inputs", sortedMap(node.inputs()));
        semantic.put("config", sortedMap(semanticConfig(node.config())));
        return semantic;
    }

    private static Map<String, Object> edgeSemantic(GraphDraft.DraftEdge edge) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("kind", edge.kind());
        semantic.put("source", endpointSemantic(edge.source()));
        semantic.put("target", endpointSemantic(edge.target()));
        semantic.put("condition", edge.condition());
        return semantic;
    }

    private static Map<String, Object> endpointSemantic(GraphDraft.Endpoint endpoint) {
        return Map.of(
                "nodeId", endpoint.nodeId(),
                "port", endpoint.port(),
                "path", endpoint.path()
        );
    }

    private static Map<String, Object> semanticConfig(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if ("description".equals(entry.getKey())) {
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Object graphContractValue(GraphDraft draft, String key) {
        Object rawContract = draft.visualLayout().get("graphContract");
        if (!(rawContract instanceof Map<?, ?> contract)) {
            return "";
        }
        return contract.get(key);
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof SchemaEnvelope envelope) {
            return canonicalValue(Map.of(
                    "format", envelope.format(),
                    "version", envelope.version(),
                    "schema", envelope.schema()
            ));
        }
        if (value instanceof GraphDraft.Binding binding) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kind", binding.kind());
            fields.put("value", binding.value());
            fields.put("path", binding.path());
            fields.put("nodeId", binding.nodeId());
            fields.put("sourcePort", binding.sourcePort());
            fields.put("targetPort", binding.targetPort());
            fields.put("targetPath", binding.targetPath());
            fields.put("expr", binding.expr());
            fields.put("fields", sortedMap(binding.fields()));
            return canonicalValue(fields);
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner joiner = new StringJoiner(",", "{", "}");
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> joiner.add(String.valueOf(entry.getKey()) + ":"
                            + canonicalValue(entry.getValue())));
            return joiner.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(",", "[", "]");
            collection.forEach(item -> joiner.add(canonicalValue(item)));
            return joiner.toString();
        }
        return String.valueOf(value);
    }

    private static <T> Map<String, T> sortedMap(Map<String, T> source) {
        Map<String, T> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private record EffectiveCatalog(Map<String, OperatorDefinition> operators, Set<String> functions) {
        private VisualOperatorCatalog asCatalog() {
            return new VisualOperatorCatalog() {
                @Override
                public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                    return operators.values().stream()
                            .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                            .toList();
                }

                @Override
                public Optional<OperatorDefinition> find(String operatorRef) {
                    return Optional.ofNullable(operators.get(operatorRef));
                }
            };
        }
    }

    private static final class ProjectionState {
        private final DslImportPreviewRequest request;
        private final List<VisualDiagnostic> diagnostics = new ArrayList<>();
        private final List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        private final List<GraphDraft.DraftEdge> edges = new ArrayList<>();
        private final Map<String, DslSourceSpan> nodeSpans = new LinkedHashMap<>();
        private final Map<String, DslSourceSpan> edgeSpans = new LinkedHashMap<>();
        private final Map<String, DslSourceSpan> bindingSpans = new LinkedHashMap<>();
        private final Set<String> edgeIds = new LinkedHashSet<>();
        private final Set<String> unsupportedSyntaxTargets = new LinkedHashSet<>();
        private final Set<String> missingOperatorRefs = new LinkedHashSet<>();
        private final Set<String> missingFunctionNames = new LinkedHashSet<>();
        private final Set<String> expressionOutputTargets = new LinkedHashSet<>();
        private String parseFailureMessage = "";

        private ProjectionState(DslImportPreviewRequest request) {
            this.request = request;
        }
    }
}
