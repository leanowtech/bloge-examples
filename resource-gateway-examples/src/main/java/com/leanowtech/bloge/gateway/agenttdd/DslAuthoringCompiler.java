package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.compiler.CompilationMode;
import com.leanowtech.bloge.dsl.compiler.CompilationResult;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import com.leanowtech.bloge.gateway.visual.importer.DslVisualProjection;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.lint.LintRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the bounded server-authoritative DSL authoring pipeline against one frozen context.
 *
 * <p>The implementation invokes the production parser, semantic compiler, linter, visual
 * projector and round-trip checker. Only {@link DslSafeDiagnosticRegistry} output crosses the MCP
 * boundary; source text, regenerated source and lower-layer prose remain inside this service.</p>
 */
final class DslAuthoringCompiler {
    static final int MAX_SOURCE_BYTES = 512 * 1024;
    static final int MAX_DIAGNOSTICS_PER_PHASE = 25;
    static final int MAX_DIAGNOSTICS_TOTAL = 100;
    static final Duration PREVIEW_BUDGET = Duration.ofSeconds(5);
    private static final List<String> PHASES = List.of(
            "CONTEXT", "PARSE", "RESOLVE", "TYPE_CHECK", "SEMANTIC_COMPILE",
            "LINT", "PROJECT", "ROUND_TRIP");

    private final ObjectMapper mapper;
    private final DslSafeDiagnosticRegistry diagnostics;

    DslAuthoringCompiler(ObjectMapper mapper) {
        this.mapper = mapper;
        this.diagnostics = new DslSafeDiagnosticRegistry(mapper);
    }

    /**
     * Compiles a candidate without executing graph nodes or resolving the live catalog again.
     *
     * @param request normalized candidate and context identity
     * @param context frozen, scope-authorized authoring context
     * @return payload-free receipt and an internal immutable projection
     */
    DslPreviewReceipt preview(DslPreviewRequest request, DslAuthoringContext context) {
        if (request.source().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new AgentTddToolException("DSL_SOURCE_TOO_LARGE",
                    "The DSL source exceeds the authoring size limit.",
                    Map.of("maximumBytes", MAX_SOURCE_BYTES, "nextAction", "NARROW_DSL_SOURCE"));
        }
        long deadline = System.nanoTime() + PREVIEW_BUDGET.toNanos();
        DslAuthoringContextCatalog frozenCatalog = new DslAuthoringContextCatalog(context);
        DslImportService importer = new DslImportService(frozenCatalog, new OperatorLibraryValidator());
        DslVisualProjection projection = importer.preview(new DslImportPreviewRequest(
                request.sourceId(), request.source(), context.libraries().stream()
                .map(DslAuthoringContext.LibrarySnapshot::libraryId).toList(), List.of(),
                "agent-tdd", Map.of()));

        LinkedHashMap<String, List<DslSafeDiagnosticRegistry.MappedDiagnostic>> byPhase = new LinkedHashMap<>();
        PHASES.forEach(phase -> byPhase.put(phase, new ArrayList<>()));
        projection.diagnostics().stream().filter(java.util.Objects::nonNull)
                .map(value -> diagnostics.visual(value, context))
                .forEach(value -> byPhase.get(value.diagnostic().phase()).add(value));

        List<DslPreviewReceipt.Stage> stages = new ArrayList<>();
        List<DslSafeDiagnosticRegistry.MappedDiagnostic> emitted = new ArrayList<>();
        stages.add(new DslPreviewReceipt.Stage("CONTEXT", "PASS"));
        String failedPhase = runCollected("PARSE", byPhase, stages, emitted);
        if (failedPhase == null) failedPhase = runCollected("RESOLVE", byPhase, stages, emitted);

        if (failedPhase == null) {
            if (System.nanoTime() > deadline) {
                byPhase.get("TYPE_CHECK").add(diagnostics.platformDefect("TYPE_CHECK"));
            } else if (projection.draft().nodes().stream()
                    .anyMatch(node -> !node.operatorRef().startsWith("bloge:"))) {
                new GraphDraftValidator(frozenCatalog).validate(projection.draft()).diagnostics().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(value -> diagnostics.visual(value, context))
                        .filter(value -> "TYPE_CHECK".equals(value.diagnostic().phase()))
                        .forEach(byPhase.get("TYPE_CHECK")::add);
            }
            failedPhase = runCollected("TYPE_CHECK", byPhase, stages, emitted);
        }

        if (failedPhase == null) {
            try {
                DslCompiler compiler = semanticCompiler(context);
                AstNode ast = compiler.parseAst(request.source());
                if (ast instanceof AstNode.GraphDef graph) {
                    CompilationResult result = compiler.compileWithDiagnostics(graph);
                    result.diagnostics().stream().map(diagnostics::compiler)
                            .forEach(byPhase.get("SEMANTIC_COMPILE")::add);
                } else {
                    byPhase.get("SEMANTIC_COMPILE").add(diagnostics.platformDefect("SEMANTIC_COMPILE"));
                }
            } catch (RuntimeException failure) {
                byPhase.get("SEMANTIC_COMPILE").add(diagnostics.platformDefect("SEMANTIC_COMPILE"));
            }
            failedPhase = runCollected("SEMANTIC_COMPILE", byPhase, stages, emitted);
        }

        if (failedPhase == null) {
            try {
                new LintRunner().lintSource(request.source()).stream().map(diagnostics::lint)
                        .forEach(byPhase.get("LINT")::add);
            } catch (RuntimeException failure) {
                byPhase.get("LINT").add(diagnostics.platformDefect("LINT"));
            }
            failedPhase = runCollected("LINT", byPhase, stages, emitted);
        }
        if (failedPhase == null) failedPhase = runCollected("PROJECT", byPhase, stages, emitted);
        boolean designOnly = projection.draft().operatorSnapshots().values().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(operator -> "design".equals(operator.lowering().mode()));
        if (failedPhase == null) {
            if (!projection.roundTrip().supported() && designOnly) {
                byPhase.get("ROUND_TRIP").add(diagnostics.designOnlyRoundTripDeferred());
            } else if (!projection.roundTrip().supported()) {
                byPhase.get("ROUND_TRIP").add(diagnostics.roundTripDrift());
            }
            failedPhase = runCollected("ROUND_TRIP", byPhase, stages, emitted);
        }
        if (failedPhase != null) appendNotRun(stages, failedPhase);

        List<DslAuthoringDiagnostic> bounded = bounded(emitted);
        boolean truncated = bounded.size() < deduplicated(emitted).size();
        boolean accepted = failedPhase == null;
        boolean platformDefect = bounded.stream().anyMatch(value ->
                "ERROR".equals(value.level()) && "PLATFORM_MAINTAINER".equals(value.resolutionClass()));
        String acceptance = accepted ? "ACCEPTED" : platformDefect ? "PLATFORM_DEFECT" : "REVISE";
        String nextAction = accepted ? "CONTINUE_TO_REWRITE_GATE"
                : platformDefect ? "REPORT_PLATFORM_DEFECT" : "REVISE_SOURCE";
        String safeSourceSemanticFingerprint = safeSemanticFingerprint(
                projection.roundTrip().sourceFingerprint());
        String safeGeneratedSemanticFingerprint = safeSemanticFingerprint(
                projection.roundTrip().generatedFingerprint());
        Map<String, Object> safeProjection = safeProjection(projection, safeSourceSemanticFingerprint);
        DslPreviewReceipt.RoundTrip roundTrip = new DslPreviewReceipt.RoundTrip(
                designOnly && !projection.roundTrip().supported() ? "DEFERRED_DESIGN_ONLY"
                        : projection.roundTrip().status(), safeSourceSemanticFingerprint,
                safeGeneratedSemanticFingerprint,
                projection.roundTrip().supported() ? List.of()
                        : designOnly ? List.of("DESIGN_ONLY_OPERATOR") : List.of("SEMANTIC_PROJECTION"));
        DslPreviewReceipt.DiagnosticSummary summary = summary(bounded, truncated);
        String receiptFingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper, Map.of(
                "schemaVersion", "rg.dslAuthoringReceipt.v1",
                "contextFingerprint", context.fingerprint(),
                "sourceFingerprint", VisualBundleFingerprint.fromCanonicalValue(
                        mapper, Map.of("source", request.source()), MAX_SOURCE_BYTES + 1024),
                "libraryRefs", request.libraryRefs() == null ? List.of() : request.libraryRefs(),
                "accepted", accepted,
                "diagnostics", bounded.stream().map(DslAuthoringDiagnostic::diagnosticFingerprint).toList(),
                "sourceSemanticFingerprint", safeSourceSemanticFingerprint), 128 * 1024);
        return new DslPreviewReceipt(new DslPreviewReceipt.AuthoringContext(
                context.fingerprint(), "CURRENT", context.languageVersion(), context.compilerProfile()),
                stages, acceptance, safeProjection, roundTrip, bounded, summary, nextAction,
                receiptFingerprint, accepted, projection);
    }

    private DslCompiler semanticCompiler(DslAuthoringContext context) {
        DslCompiler compiler = new DslCompiler(new DefaultOperatorRegistry())
                .withDiscoveredExtensionProviders()
                .withCompilationMode(CompilationMode.LENIENT)
                .withOperatorValidation(SchemaValidationLevel.OFF);
        context.functions().values().stream().sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name))
                .forEach(function -> compiler.registerFunction(functionStub(function)));
        return compiler;
    }

    private static ExpressionFunction functionStub(OperatorLibrary.BuiltInFunction definition) {
        return new ExpressionFunction() {
            @Override public String name() { return definition.name(); }
            @Override public Object apply(Object... arguments) {
                throw new UnsupportedOperationException("Authoring functions are never executed");
            }
            @Override public String returnType(String... argumentTypes) {
                return definition.signatures().isEmpty() ? "any"
                        : definition.signatures().getFirst().returns().type();
            }
        };
    }

    private static String runCollected(
            String phase,
            Map<String, List<DslSafeDiagnosticRegistry.MappedDiagnostic>> byPhase,
            List<DslPreviewReceipt.Stage> stages,
            List<DslSafeDiagnosticRegistry.MappedDiagnostic> emitted) {
        List<DslSafeDiagnosticRegistry.MappedDiagnostic> values = byPhase.getOrDefault(phase, List.of());
        emitted.addAll(values);
        boolean failed = values.stream().anyMatch(DslSafeDiagnosticRegistry.MappedDiagnostic::blocking);
        stages.add(new DslPreviewReceipt.Stage(phase, failed ? "FAIL" : "PASS"));
        return failed ? phase : null;
    }

    private static void appendNotRun(List<DslPreviewReceipt.Stage> stages, String failedPhase) {
        int failed = PHASES.indexOf(failedPhase);
        for (int index = failed + 1; index < PHASES.size(); index++) {
            stages.add(new DslPreviewReceipt.Stage(PHASES.get(index), "NOT_RUN"));
        }
    }

    private static List<DslAuthoringDiagnostic> bounded(
            List<DslSafeDiagnosticRegistry.MappedDiagnostic> source) {
        LinkedHashMap<String, DslAuthoringDiagnostic> distinct = new LinkedHashMap<>();
        Map<String, Integer> phaseCounts = new LinkedHashMap<>();
        for (DslSafeDiagnosticRegistry.MappedDiagnostic mapped : source) {
            DslAuthoringDiagnostic value = mapped.diagnostic();
            String key = value.code() + "|" + value.target() + "|" + value.span();
            if (distinct.containsKey(key)) continue;
            int phaseCount = phaseCounts.getOrDefault(value.phase(), 0);
            if (distinct.size() >= MAX_DIAGNOSTICS_TOTAL || phaseCount >= MAX_DIAGNOSTICS_PER_PHASE) continue;
            distinct.put(key, value);
            phaseCounts.put(value.phase(), phaseCount + 1);
        }
        return List.copyOf(distinct.values());
    }

    private static Set<String> deduplicated(List<DslSafeDiagnosticRegistry.MappedDiagnostic> source) {
        Set<String> result = new LinkedHashSet<>();
        source.forEach(value -> result.add(value.diagnostic().code() + "|"
                + value.diagnostic().target() + "|" + value.diagnostic().span()));
        return result;
    }

    private static DslPreviewReceipt.DiagnosticSummary summary(
            List<DslAuthoringDiagnostic> diagnostics, boolean truncated) {
        Map<String, Long> counts = diagnostics.stream().collect(java.util.stream.Collectors.groupingBy(
                DslAuthoringDiagnostic::phase, LinkedHashMap::new, java.util.stream.Collectors.counting()));
        List<DslPreviewReceipt.PhaseCount> byPhase = PHASES.stream().filter(counts::containsKey)
                .map(phase -> new DslPreviewReceipt.PhaseCount(phase, counts.get(phase).intValue())).toList();
        return new DslPreviewReceipt.DiagnosticSummary(diagnostics.size(), truncated, byPhase);
    }

    private Map<String, Object> safeProjection(DslVisualProjection projection,
                                               String safeSourceSemanticFingerprint) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "rg.dslProjectionSummary.v1");
        result.put("status", projection.diagnostics().stream().anyMatch(VisualDiagnostic::error)
                ? "REPAIR_REQUIRED" : "PROJECTED");
        result.put("nodeCount", projection.draft().nodes().size());
        result.put("edgeCount", projection.draft().edges().size());
        result.put("unsupportedSyntaxCount", projection.coverage().unsupportedSyntaxCount());
        result.put("missingOperatorCount", projection.coverage().missingOperatorCount());
        result.put("missingFunctionCount", projection.coverage().missingFunctionCount());
        result.put("sourceSemanticFingerprint", safeSourceSemanticFingerprint);
        return Map.copyOf(result);
    }

    private String safeSemanticFingerprint(String internalSemanticMaterial) {
        if (internalSemanticMaterial == null || internalSemanticMaterial.isBlank()) return "";
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                Map.of("semanticMaterial", internalSemanticMaterial), MAX_SOURCE_BYTES * 2);
    }
}
