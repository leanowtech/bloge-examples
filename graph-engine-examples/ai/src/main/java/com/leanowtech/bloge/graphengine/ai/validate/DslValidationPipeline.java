package com.leanowtech.bloge.graphengine.ai.validate;

import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpointStore;
import com.leanowtech.bloge.core.runtime.event.EventMatcherStore;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStore;
import com.leanowtech.bloge.core.runtime.work.WorkItemNotifier;
import com.leanowtech.bloge.core.runtime.work.WorkItemStore;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.core.spi.ScriptOperatorFactory;
import com.leanowtech.bloge.core.spi.SecretProvider;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.CompilationDiagnostic;
import com.leanowtech.bloge.dsl.compiler.CompilationMode;
import com.leanowtech.bloge.dsl.compiler.CompilationResult;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.compiler.RemoteWorkerOperatorFactories;
import com.leanowtech.bloge.ext.compiler.SessionDslCompiler;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.lint.LintDiagnostic;
import com.leanowtech.bloge.lint.LintRunner;
import com.leanowtech.bloge.lint.QualityScore;
import com.leanowtech.bloge.lint.QualityScorer;
import com.leanowtech.bloge.state.compiler.StateMachineDslCompiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared validation pipeline for human-authored or AI-generated BLOGE DSL.
 *
 * <p>The pipeline mirrors the product-layer Java validation path: parse, lint, compile, then
 * summarize the AI quality score.</p>
 */
public final class DslValidationPipeline {

    private final OperatorRegistry operatorRegistry;
    private final EventMatcherStore eventMatcherStore;
    private final ExecutionCheckpointStore executionCheckpointStore;
    private final ExecutionStore executionStore;
    private final ScriptOperatorFactory scriptOperatorFactory;
    private final WorkItemStore workItemStore;
    private final WorkItemNotifier workItemNotifier;
    private final JsonCodec jsonCodec;
    private final LintRunner lintRunner;
    private final QualityScorer qualityScorer;

    private DslValidationPipeline(Builder builder) {
        this.operatorRegistry = builder.operatorRegistry == null
                ? new DefaultOperatorRegistry()
                : builder.operatorRegistry;
        this.eventMatcherStore = builder.eventMatcherStore;
        this.executionCheckpointStore = builder.executionCheckpointStore;
        this.executionStore = builder.executionStore;
        this.scriptOperatorFactory = builder.scriptOperatorFactory;
        this.workItemStore = builder.workItemStore;
        this.workItemNotifier = builder.workItemNotifier == null ? WorkItemNotifier.NOOP : builder.workItemNotifier;
        this.jsonCodec = builder.jsonCodec == null ? JsonCodec.DEFAULT : builder.jsonCodec;
        this.lintRunner = builder.lintRunner == null ? new LintRunner() : builder.lintRunner;
        this.qualityScorer = builder.qualityScorer == null ? new QualityScorer() : builder.qualityScorer;
    }

    /**
     * Creates a new builder.
     *
     * @return empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates one DSL source string.
     *
     * @param source DSL source
     * @return structured validation result
     */
    public DslValidationResult validate(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        String normalizedSource = source.strip();
        QualityScore qualityScore = qualityScorer.scoreSource(normalizedSource);
        QualityScoreSummary qualitySummary = new QualityScoreSummary(
                qualityScore.total(),
                qualityScore.dimensions(),
                qualityScore.parseSuccess(),
                qualityScore.isProductionQuality()
        );

        AstNode root;
        GraphExecutionMode executionMode;
        String declaredRootName;
        try {
            root = parsingCompiler().parseAst(normalizedSource);
            executionMode = detectExecutionMode(root);
            declaredRootName = declaredRootName(root);
        } catch (RuntimeException exception) {
            List<DslDiagnostic> diagnostics = List.of(serviceDiagnostic(
                    DslDiagnostic.Stage.PARSE,
                    "parse",
                    exception.getMessage()
            ));
            return new DslValidationResult(normalizedSource, GraphExecutionMode.GRAPH, null, diagnostics, qualitySummary, false);
        }

        List<DslDiagnostic> diagnostics = new ArrayList<>(lintRunner.lintSource(normalizedSource).stream()
                .map(this::lintDiagnostic)
                .toList());

        switch (executionMode) {
            case GRAPH -> compileGraph(root, diagnostics);
            case SESSION -> compileSession(normalizedSource, diagnostics);
            case STATE_MACHINE -> compileStateMachine(normalizedSource, diagnostics);
        }

        boolean valid = diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == DslDiagnostic.Severity.ERROR);
        return new DslValidationResult(normalizedSource, executionMode, declaredRootName, diagnostics, qualitySummary, valid);
    }

    private void compileGraph(AstNode root, List<DslDiagnostic> diagnostics) {
        if (!(root instanceof AstNode.GraphDef graphDef)) {
            diagnostics.add(serviceDiagnostic(DslDiagnostic.Stage.COMPILE, "compile",
                    "Expected top-level graph definition"));
            return;
        }
        try {
            CompilationResult compilation = baseDslCompiler()
                    .withGraphVersion("ai-draft")
                    .withCompilationMode(CompilationMode.LENIENT)
                    .compileWithDiagnostics(graphDef);
            diagnostics.addAll(compilation.diagnostics().stream()
                    .map(this::compileDiagnostic)
                    .toList());
        } catch (RuntimeException exception) {
            diagnostics.add(serviceDiagnostic(
                    DslDiagnostic.Stage.COMPILE,
                    "compile",
                    exception.getMessage()
            ));
        }
    }

    private void compileSession(String source, List<DslDiagnostic> diagnostics) {
        SessionDslCompiler compiler = new SessionDslCompiler(operatorRegistry, SecretProvider.NONE, jsonCodec)
                .withSchemaValidation(SchemaValidationLevel.WARN);
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
            compiler.compile(source);
        } catch (RuntimeException exception) {
            diagnostics.add(serviceDiagnostic(
                    DslDiagnostic.Stage.COMPILE,
                    "compile",
                    exception.getMessage()
            ));
        }
    }

    private void compileStateMachine(String source, List<DslDiagnostic> diagnostics) {
        StateMachineDslCompiler compiler = new StateMachineDslCompiler(operatorRegistry, SecretProvider.NONE, jsonCodec)
                .withSchemaValidation(SchemaValidationLevel.WARN);
        if (scriptOperatorFactory != null) {
            compiler.withScriptOperatorFactory(scriptOperatorFactory);
        }
        try {
            compiler.compile(source);
        } catch (RuntimeException exception) {
            diagnostics.add(serviceDiagnostic(
                    DslDiagnostic.Stage.COMPILE,
                    "compile",
                    exception.getMessage()
            ));
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

    private DslDiagnostic lintDiagnostic(LintDiagnostic diagnostic) {
        return new DslDiagnostic(
                DslDiagnostic.Stage.LINT,
                diagnostic.ruleId(),
                switch (diagnostic.severity()) {
                    case ERROR -> DslDiagnostic.Severity.ERROR;
                    case WARNING -> DslDiagnostic.Severity.WARNING;
                    case INFO -> DslDiagnostic.Severity.INFO;
                },
                diagnostic.message(),
                null,
                null,
                Math.max(0, diagnostic.line()),
                Math.max(0, diagnostic.column())
        );
    }

    private DslDiagnostic compileDiagnostic(CompilationDiagnostic diagnostic) {
        return new DslDiagnostic(
                DslDiagnostic.Stage.COMPILE,
                "dsl-compile",
                switch (diagnostic.level()) {
                    case ERROR -> DslDiagnostic.Severity.ERROR;
                    case WARNING -> DslDiagnostic.Severity.WARNING;
                    case INFO -> DslDiagnostic.Severity.INFO;
                },
                diagnostic.message(),
                diagnostic.nodeId(),
                diagnostic.field(),
                Math.max(0, diagnostic.line()),
                Math.max(0, diagnostic.column())
        );
    }

    private static DslDiagnostic serviceDiagnostic(DslDiagnostic.Stage stage, String code, String message) {
        return new DslDiagnostic(
                stage,
                code,
                DslDiagnostic.Severity.ERROR,
                message == null || message.isBlank() ? "Unknown DSL validation failure" : message,
                null,
                null,
                0,
                0
        );
    }

    private static GraphExecutionMode detectExecutionMode(AstNode root) {
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

    private static String declaredRootName(AstNode root) {
        if (root instanceof AstNode.GraphDef graphDef) {
            return graphDef.name();
        }
        if (root instanceof AstNode.ExtensionDef extensionDef) {
            return extensionDef.id();
        }
        return null;
    }

    /**
     * Fluent builder for {@link DslValidationPipeline}.
     */
    public static final class Builder {
        private OperatorRegistry operatorRegistry;
        private EventMatcherStore eventMatcherStore;
        private ExecutionCheckpointStore executionCheckpointStore;
        private ExecutionStore executionStore;
        private ScriptOperatorFactory scriptOperatorFactory;
        private WorkItemStore workItemStore;
        private WorkItemNotifier workItemNotifier;
        private JsonCodec jsonCodec;
        private LintRunner lintRunner;
        private QualityScorer qualityScorer;

        private Builder() {
        }

        public Builder operatorRegistry(OperatorRegistry operatorRegistry) {
            this.operatorRegistry = operatorRegistry;
            return this;
        }

        public Builder eventMatcherStore(EventMatcherStore eventMatcherStore) {
            this.eventMatcherStore = eventMatcherStore;
            return this;
        }

        public Builder executionCheckpointStore(ExecutionCheckpointStore executionCheckpointStore) {
            this.executionCheckpointStore = executionCheckpointStore;
            return this;
        }

        public Builder executionStore(ExecutionStore executionStore) {
            this.executionStore = executionStore;
            return this;
        }

        public Builder scriptOperatorFactory(ScriptOperatorFactory scriptOperatorFactory) {
            this.scriptOperatorFactory = scriptOperatorFactory;
            return this;
        }

        public Builder workItemStore(WorkItemStore workItemStore) {
            this.workItemStore = workItemStore;
            return this;
        }

        public Builder workItemNotifier(WorkItemNotifier workItemNotifier) {
            this.workItemNotifier = workItemNotifier;
            return this;
        }

        public Builder jsonCodec(JsonCodec jsonCodec) {
            this.jsonCodec = jsonCodec;
            return this;
        }

        public Builder lintRunner(LintRunner lintRunner) {
            this.lintRunner = lintRunner;
            return this;
        }

        public Builder qualityScorer(QualityScorer qualityScorer) {
            this.qualityScorer = qualityScorer;
            return this;
        }

        public DslValidationPipeline build() {
            return new DslValidationPipeline(this);
        }
    }
}
