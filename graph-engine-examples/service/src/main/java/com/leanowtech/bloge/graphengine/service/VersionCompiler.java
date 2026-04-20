package com.leanowtech.bloge.graphengine.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeMetadata;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionCodec;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionHasher;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaDescriptor;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.ScriptOperatorFactory;
import com.leanowtech.bloge.core.spi.SecretProvider;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.CompilationMode;
import com.leanowtech.bloge.dsl.compiler.CompilationDiagnostic;
import com.leanowtech.bloge.dsl.compiler.CompilationResult;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.compiler.DslGraphDefinitionCodec;
import com.leanowtech.bloge.dsl.compiler.RemoteWorkerOperatorFactories;
import com.leanowtech.bloge.ext.compiler.SessionDslCompiler;
import com.leanowtech.bloge.ext.model.PhaseDef;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.TaskDefinition;
import com.leanowtech.bloge.lint.LintDiagnostic;
import com.leanowtech.bloge.lint.LintRunner;
import com.leanowtech.bloge.state.compiler.StateMachineDslCompiler;
import com.leanowtech.bloge.state.model.StateDef;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles one immutable product-layer graph version into the runtime artifact
 * needed by execution while preserving authoring diagnostics and derived
 * metadata for the control plane.
 */
public final class VersionCompiler {

    private static final Set<String> HUMAN_TASK_OPERATOR_REFS = Set.of(
            "user",
            "user-task",
            "HumanTaskOperator",
            "human-task"
    );

    private final OperatorRegistry operatorRegistry;
    private final EventMatcherStore eventMatcherStore;
    private final ExecutionCheckpointStore executionCheckpointStore;
    private final ExecutionStore executionStore;
    private final ScriptOperatorFactory scriptOperatorFactory;
    private final WorkItemStore workItemStore;
    private final WorkItemNotifier workItemNotifier;
    private final JsonCodec jsonCodec;
    private final LintRunner lintRunner;
    private final DslGraphDefinitionCodec graphDefinitionCodec;
    private final Cache<VersionCompileCacheKey, VersionCompileResult> compileCache;

    /**
     * Creates a compiler backed by the supplied runtime collaborators.
     *
     * @param runtimeSupport runtime services used for compilation and encoding
     */
    public VersionCompiler(GraphEngineRuntimeSupport runtimeSupport) {
        Objects.requireNonNull(runtimeSupport, "runtimeSupport");
        this.operatorRegistry = runtimeSupport.operatorRegistry() == null
                ? new DefaultOperatorRegistry()
                : runtimeSupport.operatorRegistry();
        this.eventMatcherStore = runtimeSupport.eventMatcherStore();
        this.executionCheckpointStore = runtimeSupport.executionCheckpointStore();
        this.executionStore = runtimeSupport.executionStore();
        this.scriptOperatorFactory = runtimeSupport.scriptOperatorFactory();
        this.workItemStore = runtimeSupport.workItemStore();
        this.workItemNotifier = runtimeSupport.workItemNotifier();
        this.jsonCodec = runtimeSupport.jsonCodec();
        this.lintRunner = new LintRunner();
        this.graphDefinitionCodec = new DslGraphDefinitionCodec(jsonCodec);
        VersionCompilerCacheSettings cacheSettings = runtimeSupport.versionCompilerCacheSettings();
        this.compileCache = cacheSettings.enabled()
                ? Caffeine.newBuilder()
                .maximumSize(cacheSettings.maximumSize())
                .expireAfterAccess(cacheSettings.expireAfterAccess())
                .build()
                : null;
    }

    /**
     * Compiles the supplied stored version and returns the runtime artifact plus
     * control-plane diagnostics and derived metadata.
     *
     * @param definition owning product definition
     * @param version immutable stored version snapshot
     * @return compile result
     */
    public VersionCompileResult compile(GraphDefinition definition, GraphVersion version) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(version, "version");
        if (compileCache == null) {
            return doCompile(definition, version);
        }
        VersionCompileCacheKey cacheKey = new VersionCompileCacheKey(version.versionId(), sourceHash(version));
        return compileCache.get(cacheKey, ignored -> doCompile(definition, version));
    }

    /**
     * Invalidates the cached compilation for one immutable stored version.
     *
     * @param version version whose cached compilation should be discarded
     */
    public void invalidate(GraphVersion version) {
        if (compileCache != null && version != null) {
            compileCache.invalidate(new VersionCompileCacheKey(version.versionId(), sourceHash(version)));
        }
    }

    /**
     * Clears every cached compile result.
     */
    public void invalidateAll() {
        if (compileCache != null) {
            compileCache.invalidateAll();
        }
    }

    long cacheEntryCount() {
        return compileCache == null ? 0L : compileCache.estimatedSize();
    }

    private VersionCompileResult doCompile(GraphDefinition definition, GraphVersion version) {
        List<GraphEngineDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(lint(version.dslSource()));

        AstNode root = null;
        String declaredRootName = null;
        GraphExecutionMode executionMode = GraphExecutionMode.GRAPH;
        try {
            root = parsingCompiler().parseAst(version.dslSource());
            declaredRootName = declaredRootName(root);
            executionMode = detectExecutionMode(root);
        } catch (RuntimeException exception) {
            diagnostics.add(serviceError(
                    "Failed to parse version '" + version.version() + "': " + exception.getMessage(),
                    "parse"
            ));
            String fallbackRuntimeName = RuntimeArtifactNames.runtimeName(definition, version.versionId(), executionMode);
            GraphVersionMetadata metadata = new GraphVersionMetadata(
                    executionMode,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    Map.of(),
                    RuntimeArtifactNames.namingHints(fallbackRuntimeName, null)
            );
            return new VersionCompileResult(
                    executionMode,
                    fallbackRuntimeName,
                    null,
                    GraphDefinitionHasher.sha256Hex(version.dslSource()),
                    null,
                    metadata,
                    diagnostics,
                    null,
                    null,
                    null
            );
        }

        String runtimeName = RuntimeArtifactNames.runtimeName(definition, version.versionId(), executionMode);
        return switch (executionMode) {
            case GRAPH -> {
                if (!(root instanceof AstNode.GraphDef graphDef)) {
                    diagnostics.add(serviceError("Expected top-level graph definition", "parse"));
                    GraphVersionMetadata metadata = new GraphVersionMetadata(
                            GraphExecutionMode.GRAPH,
                            List.of(),
                            Map.of(),
                            null,
                            null,
                            Map.of(),
                            RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
                    );
                    yield new VersionCompileResult(
                            GraphExecutionMode.GRAPH,
                            runtimeName,
                            declaredRootName,
                            GraphDefinitionHasher.sha256Hex(version.dslSource()),
                            null,
                            metadata,
                            diagnostics,
                            null,
                            null,
                            null
                    );
                }
                yield compileGraph(version, runtimeName, declaredRootName, graphDef, diagnostics);
            }
            case SESSION -> compileSession(version, runtimeName, declaredRootName, diagnostics);
            case STATE_MACHINE -> compileStateMachine(version, runtimeName, declaredRootName, diagnostics);
        };
    }

    private static String sourceHash(GraphVersion version) {
        if (version.contentHash() != null && !version.contentHash().isBlank()) {
            return version.contentHash();
        }
        return GraphDefinitionHasher.sha256Hex(version.dslSource());
    }

    private VersionCompileResult compileGraph(GraphVersion version,
                                              String runtimeName,
                                              String declaredRootName,
                                              AstNode.GraphDef root,
                                              List<GraphEngineDiagnostic> diagnostics) {
        DslCompiler compiler = baseDslCompiler()
                .withGraphVersion(version.version())
                .withCompilationMode(CompilationMode.LENIENT);

        try {
            CompilationResult compilation = compiler.compileWithDiagnostics(root);
            diagnostics.addAll(compilationDiagnostics(compilation.diagnostics()));
            Graph compiledGraph = compilation.graph();
            if (compiledGraph == null) {
                diagnostics.add(serviceError(
                        "Compilation did not produce a graph artifact for version '" + version.version() + "'",
                        "compile"
                ));
                GraphVersionMetadata metadata = new GraphVersionMetadata(
                        GraphExecutionMode.GRAPH,
                        List.of(),
                        Map.of(),
                        null,
                        null,
                        Map.of(),
                        RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
                );
                return new VersionCompileResult(
                        GraphExecutionMode.GRAPH,
                        runtimeName,
                        declaredRootName,
                        GraphDefinitionHasher.sha256Hex(version.dslSource()),
                        null,
                        metadata,
                        diagnostics,
                        null,
                        null,
                        null
                );
            }

            Graph rewrittenGraph = RuntimeArtifactNames.renameGraph(
                    compiledGraph,
                    runtimeName,
                    version.version(),
                    root,
                    jsonCodec
            );
            String compiledArtifactRef = encodedGraphHash(rewrittenGraph);
            GraphVersionMetadata metadata = graphMetadata(
                    GraphExecutionMode.GRAPH,
                    List.of(rewrittenGraph),
                    runtimeName,
                    declaredRootName,
                    rewrittenGraph.declaredInputSchema(),
                    rewrittenGraph.declaredOutputSchema()
            );
            return new VersionCompileResult(
                    GraphExecutionMode.GRAPH,
                    runtimeName,
                    declaredRootName,
                    GraphDefinitionHasher.sha256Hex(version.dslSource()),
                    compiledArtifactRef,
                    metadata,
                    diagnostics,
                    rewrittenGraph,
                    null,
                    null
            );
        } catch (RuntimeException exception) {
            diagnostics.add(serviceError(
                    "Failed to compile graph version '" + version.version() + "': " + exception.getMessage(),
                    "compile"
            ));
            GraphVersionMetadata metadata = new GraphVersionMetadata(
                    GraphExecutionMode.GRAPH,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    Map.of(),
                    RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
            );
            return new VersionCompileResult(
                    GraphExecutionMode.GRAPH,
                    runtimeName,
                    declaredRootName,
                    GraphDefinitionHasher.sha256Hex(version.dslSource()),
                    null,
                    metadata,
                    diagnostics,
                    null,
                    null,
                    null
            );
        }
    }

    private VersionCompileResult compileSession(GraphVersion version,
                                                String runtimeName,
                                                String declaredRootName,
                                                List<GraphEngineDiagnostic> diagnostics) {
        SessionDslCompiler compiler = new SessionDslCompiler(operatorRegistry, SecretProvider.NONE, jsonCodec)
                .withSchemaValidation(SchemaValidationLevel.WARN)
                .withOperatorValidation(SchemaValidationLevel.WARN);
        if (eventMatcherStore != null && executionCheckpointStore != null) {
            if (executionStore != null) {
                compiler.withEventMatcherStore(eventMatcherStore, executionCheckpointStore, executionStore);
            } else {
                compiler.withEventMatcherStore(eventMatcherStore, executionCheckpointStore);
            }
        }
        if (scriptOperatorFactory != null) {
            compiler.withScriptOperatorFactory(scriptOperatorFactory);
        }

        try {
            SessionGraph compiled = compiler.compile(version.dslSource());
            SessionGraph renamed = RuntimeArtifactNames.renameSessionGraph(compiled, runtimeName);
            GraphVersionMetadata metadata = graphMetadata(
                    GraphExecutionMode.SESSION,
                    renamed.phases().stream().map(PhaseDef::graph).toList(),
                    runtimeName,
                    declaredRootName,
                    null,
                    null
            );
            return new VersionCompileResult(
                    GraphExecutionMode.SESSION,
                    runtimeName,
                    declaredRootName,
                    renamed.contentHash(),
                    renamed.contentHash(),
                    metadata,
                    diagnostics,
                    null,
                    renamed,
                    null
            );
        } catch (RuntimeException exception) {
            diagnostics.add(serviceError(
                    "Failed to compile session version '" + version.version() + "': " + exception.getMessage(),
                    "compile"
            ));
            GraphVersionMetadata metadata = new GraphVersionMetadata(
                    GraphExecutionMode.SESSION,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    Map.of(),
                    RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
            );
            return new VersionCompileResult(
                    GraphExecutionMode.SESSION,
                    runtimeName,
                    declaredRootName,
                    GraphDefinitionHasher.sha256Hex(version.dslSource()),
                    null,
                    metadata,
                    diagnostics,
                    null,
                    null,
                    null
            );
        }
    }

    private VersionCompileResult compileStateMachine(GraphVersion version,
                                                     String runtimeName,
                                                     String declaredRootName,
                                                     List<GraphEngineDiagnostic> diagnostics) {
        StateMachineDslCompiler compiler = new StateMachineDslCompiler(operatorRegistry, SecretProvider.NONE, jsonCodec)
                .withSchemaValidation(SchemaValidationLevel.WARN)
                .withOperatorValidation(SchemaValidationLevel.WARN);
        if (scriptOperatorFactory != null) {
            compiler.withScriptOperatorFactory(scriptOperatorFactory);
        }

        try {
            StateMachineDef compiled = compiler.compile(version.dslSource());
            StateMachineDef renamed = RuntimeArtifactNames.renameStateMachine(compiled, runtimeName);
            List<Graph> graphs = renamed.states().values().stream()
                    .map(StateDef::graph)
                    .filter(Objects::nonNull)
                    .toList();
            GraphVersionMetadata metadata = graphMetadata(
                    GraphExecutionMode.STATE_MACHINE,
                    graphs,
                    runtimeName,
                    declaredRootName,
                    null,
                    null
            );
            String contentHash = GraphDefinitionHasher.sha256Hex(version.dslSource());
            return new VersionCompileResult(
                    GraphExecutionMode.STATE_MACHINE,
                    runtimeName,
                    declaredRootName,
                    contentHash,
                    contentHash,
                    metadata,
                    diagnostics,
                    null,
                    null,
                    renamed
            );
        } catch (RuntimeException exception) {
            diagnostics.add(serviceError(
                    "Failed to compile state-machine version '" + version.version() + "': " + exception.getMessage(),
                    "compile"
            ));
            GraphVersionMetadata metadata = new GraphVersionMetadata(
                    GraphExecutionMode.STATE_MACHINE,
                    List.of(),
                    Map.of(),
                    null,
                    null,
                    Map.of(),
                    RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
            );
            return new VersionCompileResult(
                    GraphExecutionMode.STATE_MACHINE,
                    runtimeName,
                    declaredRootName,
                    GraphDefinitionHasher.sha256Hex(version.dslSource()),
                    null,
                    metadata,
                    diagnostics,
                    null,
                    null,
                    null
            );
        }
    }

    private DslCompiler parsingCompiler() {
        return new DslCompiler(operatorRegistry, com.leanowtech.bloge.core.spi.SecretProvider.NONE, jsonCodec)
                .withDiscoveredExtensionProviders();
    }

    private DslCompiler baseDslCompiler() {
        DslCompiler compiler = parsingCompiler()
                .withSchemaValidation(SchemaValidationLevel.WARN)
                .withOperatorValidation(SchemaValidationLevel.WARN);
        if (eventMatcherStore != null && executionCheckpointStore != null) {
            if (executionStore != null) {
                compiler.withEventMatcherStore(eventMatcherStore, executionCheckpointStore, executionStore);
            } else {
                compiler.withEventMatcherStore(eventMatcherStore, executionCheckpointStore);
            }
        }
        if (scriptOperatorFactory != null) {
            compiler.withScriptOperatorFactory(scriptOperatorFactory);
        }
        if (workItemStore != null) {
            compiler.withRemoteWorkerOperatorFactory(RemoteWorkerOperatorFactories.durable(
                    workItemStore,
                    workItemNotifier,
                    jsonCodec
            ));
        }
        return compiler;
    }

    private List<GraphEngineDiagnostic> lint(String source) {
        return lintRunner.lintSource(source).stream()
                .map(this::lintDiagnostic)
                .toList();
    }

    private GraphEngineDiagnostic lintDiagnostic(LintDiagnostic diagnostic) {
        return new GraphEngineDiagnostic(
                "lint",
                diagnostic.ruleId(),
                switch (diagnostic.severity()) {
                    case ERROR -> GraphEngineDiagnostic.Severity.ERROR;
                    case WARNING -> GraphEngineDiagnostic.Severity.WARNING;
                    case INFO -> GraphEngineDiagnostic.Severity.INFO;
                },
                diagnostic.message(),
                null,
                null,
                Math.max(0, diagnostic.line()),
                Math.max(0, diagnostic.column())
        );
    }

    private List<GraphEngineDiagnostic> compilationDiagnostics(List<CompilationDiagnostic> diagnostics) {
        return diagnostics.stream()
                .map(diagnostic -> new GraphEngineDiagnostic(
                        "compile",
                        "dsl-compile",
                        switch (diagnostic.level()) {
                            case ERROR -> GraphEngineDiagnostic.Severity.ERROR;
                            case WARNING -> GraphEngineDiagnostic.Severity.WARNING;
                            case INFO -> GraphEngineDiagnostic.Severity.INFO;
                        },
                        diagnostic.message(),
                        diagnostic.nodeId(),
                        diagnostic.field(),
                        diagnostic.line(),
                        diagnostic.column()
                ))
                .toList();
    }

    private GraphEngineDiagnostic serviceError(String message, String code) {
        return new GraphEngineDiagnostic(
                "service",
                code,
                GraphEngineDiagnostic.Severity.ERROR,
                message,
                null,
                null,
                0,
                0
        );
    }

    private GraphExecutionMode detectExecutionMode(AstNode root) {
        if (root instanceof AstNode.ExtensionDef extensionDef) {
            if ("session".equals(extensionDef.kind())) {
                return GraphExecutionMode.SESSION;
            }
            if ("state_machine".equals(extensionDef.kind())) {
                return GraphExecutionMode.STATE_MACHINE;
            }
        }
        return GraphExecutionMode.GRAPH;
    }

    private String declaredRootName(AstNode root) {
        if (root instanceof AstNode.GraphDef graphDef) {
            return graphDef.name();
        }
        if (root instanceof AstNode.ExtensionDef extensionDef) {
            return extensionDef.id();
        }
        return null;
    }

    private String encodedGraphHash(Graph graph) {
        Optional<GraphDefinitionCodec.EncodedDefinition> encoded = graphDefinitionCodec.encode(graph);
        return encoded.map(value -> GraphDefinitionHasher.sha256Hex(value.definitionJson()))
                .orElseGet(() -> GraphDefinitionHasher.sha256Hex(graph.name() + ':' + graph.nodes().keySet()));
    }

    private GraphVersionMetadata graphMetadata(GraphExecutionMode executionMode,
                                               List<Graph> graphs,
                                               String runtimeName,
                                               String declaredRootName,
                                               SchemaDescriptor inputSchema,
                                               SchemaDescriptor outputSchema) {
        LinkedHashSet<String> operatorRefs = new LinkedHashSet<>();
        LinkedHashMap<String, String> operatorFingerprints = new LinkedHashMap<>();
        LinkedHashMap<String, TaskDefinition> taskDefinitions = new LinkedHashMap<>();
        for (Graph graph : graphs) {
            if (graph == null) {
                continue;
            }
            for (NodeSpec node : graph.nodes().values()) {
                operatorRefs.add(node.operatorRef());
                if (node.operatorFingerprint() != null && !node.operatorFingerprint().isBlank()) {
                    operatorFingerprints.put(node.id(), node.operatorFingerprint());
                }
                taskDefinition(node).ifPresent(task -> taskDefinitions.putIfAbsent(node.id(), task));
            }
        }
        return new GraphVersionMetadata(
                executionMode,
                List.copyOf(operatorRefs),
                Map.copyOf(operatorFingerprints),
                normalizeSchema(inputSchema),
                normalizeSchema(outputSchema),
                Map.copyOf(taskDefinitions),
                RuntimeArtifactNames.namingHints(runtimeName, declaredRootName)
        );
    }

    private Optional<TaskDefinition> taskDefinition(NodeSpec node) {
        if (!HUMAN_TASK_OPERATOR_REFS.contains(node.operatorRef())) {
            return Optional.empty();
        }
        NodeMetadata metadata = node.metadata();
        String formRef = firstNonBlank(metadata.get("formRef"), metadata.get("formKey"));
        String assignee = metadata.get("assignee");
        List<String> candidateGroups = splitCsv(metadata.get("candidateGroups"));
        List<String> candidateRoles = splitCsv(metadata.get("candidateRoles"));
        SchemaDescriptor payloadSchema = normalizeSchema(node.outputSchema());
        if (payloadSchema == null) {
            payloadSchema = normalizeSchema(node.inputSchema());
        }
        return Optional.of(new TaskDefinition(
                node.id(),
                canonicalTaskType(node.operatorRef()),
                formRef,
                assignee,
                candidateGroups,
                candidateRoles,
                payloadSchema
        ));
    }

    private String canonicalTaskType(String operatorRef) {
        return switch (operatorRef) {
            case "user", "user-task", "HumanTaskOperator", "human-task" -> "user-task";
            default -> operatorRef;
        };
    }

    private SchemaDescriptor normalizeSchema(SchemaDescriptor schemaDescriptor) {
        return schemaDescriptor == null || schemaDescriptor == OpaqueSchema.INSTANCE ? null : schemaDescriptor;
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private record VersionCompileCacheKey(String versionId, String contentHash) {
    }
}
