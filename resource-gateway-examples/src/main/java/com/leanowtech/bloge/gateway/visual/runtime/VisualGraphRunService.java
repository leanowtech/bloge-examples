package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates, lowers, compiles, and executes visual graph drafts.
 */
@Service
public class VisualGraphRunService {

    private static final Set<String> SYSTEM_CONTEXT_FIELDS = Set.of("tenantId", "namespace");

    private final GraphDraftValidator validator;
    private final GraphDraftDslGenerator generator;
    private final VisualDslRunner dslRunner;

    /**
     * @param validator visual draft validator
     * @param generator visual draft DSL generator
     * @param dslRunner BLOGE DSL execution adapter
     */
    public VisualGraphRunService(GraphDraftValidator validator,
                                 GraphDraftDslGenerator generator,
                                 VisualDslRunner dslRunner) {
        this.validator = validator;
        this.generator = generator;
        this.dslRunner = dslRunner;
    }

    /**
     * Validates, lowers, and compiles a visual graph draft without executing it.
     *
     * @param draft graph draft
     * @return generated DSL plus lowering/compiler diagnostics
     */
    public DslGenerationResult compile(GraphDraft draft) {
        return compileWith(draft, validator, generator);
    }

    /**
     * Compiles a materialized draft against one immutable operation catalog.
     *
     * <p>Governed publication passes the same frozen catalog used for executable identity and
     * dependency reporting. Validation and DSL generation therefore cannot observe a later live
     * catalog replacement.</p>
     *
     * @param draft materialized graph draft
     * @param operationCatalog immutable operator definitions captured for this publication
     * @return generated DSL plus lowering/compiler diagnostics
     */
    public DslGenerationResult compileAgainst(GraphDraft draft,
                                              VisualOperatorCatalog operationCatalog) {
        if (operationCatalog == null) {
            throw new IllegalArgumentException("operationCatalog is required");
        }
        return compileWith(draft, new GraphDraftValidator(operationCatalog),
                new GraphDraftDslGenerator(operationCatalog));
    }

    private DslGenerationResult compileWith(GraphDraft draft,
                                            GraphDraftValidator operationValidator,
                                            GraphDraftDslGenerator operationGenerator) {
        List<VisualDiagnostic> fingerprintDiagnostics = requireOperatorFingerprintSnapshot(draft);
        if (!fingerprintDiagnostics.isEmpty()) {
            return new DslGenerationResult(false, "", fingerprintDiagnostics,
                    repairValidation(draft, fingerprintDiagnostics));
        }
        VisualValidationResult validation = operationValidator.validate(draft);
        if (!validation.valid()) {
            return new DslGenerationResult(false, "", validation.diagnostics(), validation);
        }
        if (!validation.actionReadiness().compileNow()) {
            return new DslGenerationResult(false, "", List.of(actionReadinessDiagnostic("compile",
                    validation.actionReadiness())), validation);
        }
        DslGenerationResult generated = operationGenerator.generate(draft);
        if (!generated.generated()) {
            return new DslGenerationResult(false, generated.dsl(), generated.diagnostics(), validation);
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>(generated.diagnostics());
        diagnostics.addAll(dslRunner.compileDiagnostics(generated.dsl()).stream()
                .map(VisualGraphRunService::fromCompilerDiagnostic)
                .toList());
        return new DslGenerationResult(true, generated.dsl(), diagnostics, validation);
    }

    /**
     * Runs a visual graph draft.
     *
     * @param draft graph draft
     * @param context initial graph context
     * @param outputNode optional output node override
     * @return run response
     */
    public VisualGraphRunResponse run(GraphDraft draft,
                                      Map<String, Object> context,
                                      String outputNode) {
        return run(draft, context, outputNode, VisualRunIntent.unmanaged());
    }

    /** Runs a visual graph draft with an optional graph deadline and fenced cancellation address. */
    public VisualGraphRunResponse run(GraphDraft draft,
                                      Map<String, Object> context,
                                      String outputNode,
                                      VisualRunIntent runIntent) {
        List<VisualDiagnostic> fingerprintDiagnostics = requireOperatorFingerprintSnapshot(draft);
        if (!fingerprintDiagnostics.isEmpty()) {
            return blocked(draft, false, fingerprintDiagnostics,
                    List.of("Operator fingerprint snapshot is required."), "",
                    repairValidation(draft, fingerprintDiagnostics));
        }
        VisualValidationResult validation = validator.validate(draft);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        if (!validation.valid()) {
            return blocked(draft, false, diagnostics, List.of("Visual validation failed."), "", validation);
        }
        if (!validation.actionReadiness().runNow()) {
            diagnostics.add(actionReadinessDiagnostic("run", validation.actionReadiness()));
            return blocked(draft, true, diagnostics, List.of("Visual graph action readiness blocked runtime run."),
                    "", validation);
        }
        List<VisualDiagnostic> contextDiagnostics = validateRuntimeContext(draft, context);
        diagnostics.addAll(contextDiagnostics);
        if (!contextDiagnostics.isEmpty()) {
            return blocked(draft, true, diagnostics, List.of("Runtime context validation failed."), "",
                    validation);
        }

        DslGenerationResult generated = generator.generate(draft);
        diagnostics.addAll(generated.diagnostics());
        if (!generated.generated()) {
            return blocked(draft, true, diagnostics, List.of("Visual DSL generation failed."), generated.dsl(),
                    validation);
        }

        Map<String, Object> effectiveContext = new LinkedHashMap<>(context == null ? Map.of() : context);
        effectiveContext.putIfAbsent("tenantId", draft.tenantId());
        effectiveContext.putIfAbsent("namespace", draft.namespace());
        String selectedOutputNode = outputNode == null || outputNode.isBlank()
                ? draft.output().nodeId()
                : outputNode;
        List<VisualDiagnostic> outputDiagnostics = validateOutputNodeOverride(draft, outputNode);
        diagnostics.addAll(outputDiagnostics);
        if (!outputDiagnostics.isEmpty()) {
            return blocked(draft, true, diagnostics, List.of("Output node override validation failed."),
                    generated.dsl(), validation);
        }

        VisualDslRunResponse dynamic = dslRunner.run(new VisualDslRunRequest(
                generated.dsl(),
                effectiveContext,
                selectedOutputNode,
                runIntent
        ));
        diagnostics.addAll(dynamic.diagnostics().stream()
                .map(VisualGraphRunService::fromCompilerDiagnostic)
                .toList());
        Object output = dynamic.output();
        if (shouldExtractDraftOutputPath(draft, dynamic.outputNode())) {
            output = extractPath(output, draft.output().path());
        }
        return new VisualGraphRunResponse(
                true,
                dynamic.compiled(),
                dynamic.success(),
                dynamic.graphName(),
                dynamic.outputNode(),
                output,
                dynamic.results(),
                dynamic.statusMap(),
                dynamic.elapsedMs(),
                dynamic.nodeElapsedMs(),
                diagnostics,
                dynamic.errors(),
                dynamic.layout(),
                dynamic.decisionTable(),
                generated.dsl(),
                validation,
                "",
                dynamic.nodeAttempts(),
                dynamic.nodeExecutionFacts(),
                dynamic.runControl()
        );
    }

    /**
     * Runs an immutable published visual graph artifact without consulting the current operator catalog.
     *
     * @param publication published artifact
     * @param context initial graph context
     * @param outputNode optional output node override
     * @return run response
     */
    public VisualGraphRunResponse run(VisualGraphPublication publication,
                                      Map<String, Object> context,
                                      String outputNode) {
        return run(publication, context, outputNode, VisualRunIntent.unmanaged());
    }

    /** Runs an immutable publication with an optional controlled-run intent. */
    public VisualGraphRunResponse run(VisualGraphPublication publication,
                                      Map<String, Object> context,
                                      String outputNode,
                                      VisualRunIntent runIntent) {
        if (publication == null) {
            return blocked(null, false, List.of(VisualDiagnostic.error("visual.publication.missing",
                    "Visual graph publication is required.", "/publication")),
                    List.of("Visual graph publication is required."), "");
        }

        VisualValidationResult validation = publication.validation();
        List<VisualDiagnostic> diagnostics = new ArrayList<>(publication.validation().diagnostics());
        if (!publication.validation().valid()) {
            return blocked(publication.draft(), false, diagnostics,
                    List.of("Published visual validation report contains errors."), publication.dsl(), validation);
        }
        diagnostics.addAll(publication.generation().diagnostics());
        if (publication.designArtifact()) {
            diagnostics.add(VisualDiagnostic.error("visual.publication.designNotExecutable",
                    "Design visual graph publication '%s' is not executable; bind runtime lowerings and publish an EXECUTABLE artifact before running."
                            .formatted(publication.publicationId()),
                    "/artifactKind"));
            return blocked(publication.draft(), true, diagnostics,
                    List.of("Design visual graph publication is not executable."), publication.dsl(), validation);
        }
        if (!publication.generation().generated() || publication.dsl().isBlank()) {
            return blocked(publication.draft(), true, diagnostics,
                    List.of("Published visual DSL is not executable."), publication.dsl(), validation);
        }
        List<VisualDiagnostic> contextDiagnostics = validateRuntimeContext(publication.draft(), context);
        diagnostics.addAll(contextDiagnostics);
        if (!contextDiagnostics.isEmpty()) {
            return blocked(publication.draft(), true, diagnostics,
                    List.of("Runtime context validation failed."), publication.dsl(), validation);
        }

        Map<String, Object> effectiveContext = new LinkedHashMap<>(context == null ? Map.of() : context);
        effectiveContext.putIfAbsent("tenantId", publication.tenantId());
        effectiveContext.putIfAbsent("namespace", publication.namespace());
        GraphDraft draft = publication.draft();
        String selectedOutputNode = outputNode == null || outputNode.isBlank()
                ? (draft == null ? "" : draft.output().nodeId())
                : outputNode;
        List<VisualDiagnostic> outputDiagnostics = validateOutputNodeOverride(draft, outputNode);
        diagnostics.addAll(outputDiagnostics);
        if (!outputDiagnostics.isEmpty()) {
            return blocked(draft, true, diagnostics, List.of("Output node override validation failed."),
                    publication.dsl(), validation);
        }

        VisualDslRunResponse dynamic = dslRunner.run(new VisualDslRunRequest(
                publication.dsl(),
                effectiveContext,
                selectedOutputNode,
                runIntent
        ));
        diagnostics.addAll(dynamic.diagnostics().stream()
                .map(VisualGraphRunService::fromCompilerDiagnostic)
                .toList());
        Object output = dynamic.output();
        if (draft != null && shouldExtractDraftOutputPath(draft, dynamic.outputNode())) {
            output = extractPath(output, draft.output().path());
        }
        return new VisualGraphRunResponse(
                true,
                dynamic.compiled(),
                dynamic.success(),
                dynamic.graphName(),
                dynamic.outputNode(),
                output,
                dynamic.results(),
                dynamic.statusMap(),
                dynamic.elapsedMs(),
                dynamic.nodeElapsedMs(),
                diagnostics,
                dynamic.errors(),
                dynamic.layout(),
                dynamic.decisionTable(),
                publication.dsl(),
                validation,
                "",
                dynamic.nodeAttempts(),
                dynamic.nodeExecutionFacts(),
                dynamic.runControl()
        );
    }

    /** Returns the latest lifecycle view for a controlled run. */
    public VisualRunControlResult runControl(String requestId, String fencingToken) {
        return dslRunner.runControl(requestId, fencingToken);
    }

    /** Requests cooperative cancellation through the hosting DSL adapter. */
    public VisualRunControlResult cancel(VisualRunControlCommand command) {
        return dslRunner.cancel(command);
    }

    private static VisualValidationResult repairValidation(GraphDraft draft, List<VisualDiagnostic> diagnostics) {
        List<VisualDiagnostic> safeDiagnostics = diagnostics == null ? List.of() : diagnostics;
        return new VisualValidationResult(false, safeDiagnostics,
                VisualGraphReadiness.from(draft, Map.of(), safeDiagnostics));
    }

    private static List<VisualDiagnostic> requireOperatorFingerprintSnapshot(GraphDraft draft) {
        if (draft == null || draft.nodes().isEmpty() || !draft.operatorFingerprints().isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < draft.nodes().size(); i++) {
            GraphDraft.DraftNode node = draft.nodes().get(i);
            diagnostics.add(VisualDiagnostic.error("visual.operator.fingerprintMissing",
                    "Node '%s' using operator '%s' is missing an operator fingerprint snapshot."
                            .formatted(node.id(), node.operatorRef()),
                    "/nodes/" + i + "/operatorRef"));
        }
        return diagnostics;
    }

    private static List<VisualDiagnostic> validateOutputNodeOverride(GraphDraft draft, String outputNode) {
        if (draft == null || outputNode == null || outputNode.isBlank()) {
            return List.of();
        }
        boolean known = draft.nodes().stream()
                .anyMatch(node -> node.id().equals(outputNode));
        if (known) {
            return List.of();
        }
        return List.of(VisualDiagnostic.error("visual.run.outputNode.unknown",
                "Output node override does not exist in visual draft: " + outputNode,
                "/outputNode"));
    }

    private static List<VisualDiagnostic> validateRuntimeContext(GraphDraft draft, Map<String, Object> context) {
        if (draft == null) {
            return List.of();
        }
        return VisualSchemaValidator.validateValue(draft.inputSchema(),
                schemaVisibleContext(draft.inputSchema(), context),
                "/context");
    }

    private static Map<String, Object> schemaVisibleContext(SchemaEnvelope inputSchema,
                                                            Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> visible = new LinkedHashMap<>();
        context.forEach((key, value) -> {
            if (key == null || key.startsWith("_bloge")) {
                return;
            }
            if (SYSTEM_CONTEXT_FIELDS.contains(key) && !inputSchema.hasProperty(key)) {
                return;
            }
            visible.put(key, value);
        });
        return visible;
    }

    private static boolean shouldExtractDraftOutputPath(GraphDraft draft, String actualOutputNode) {
        return !draft.output().path().isBlank()
                && !draft.output().nodeId().isBlank()
                && draft.output().nodeId().equals(actualOutputNode);
    }

    private static VisualGraphRunResponse blocked(GraphDraft draft,
                                                  boolean validated,
                                                  List<VisualDiagnostic> diagnostics,
                                                  List<String> errors,
                                                  String generatedDsl) {
        return blocked(draft, validated, diagnostics, errors, generatedDsl, new VisualValidationResult(false,
                diagnostics));
    }

    private static VisualGraphRunResponse blocked(GraphDraft draft,
                                                  boolean validated,
                                                  List<VisualDiagnostic> diagnostics,
                                                  List<String> errors,
                                                  String generatedDsl,
                                                  VisualValidationResult validation) {
        return new VisualGraphRunResponse(
                validated,
                false,
                false,
                draft == null ? "" : draft.graphName(),
                draft == null ? "" : draft.output().nodeId(),
                null,
                Map.of(),
                Map.of(),
                0,
                Map.of(),
                diagnostics,
                errors,
                null,
                null,
                generatedDsl,
                validation
        );
    }

    private static VisualDiagnostic actionReadinessDiagnostic(String action,
                                                              VisualGraphActionReadiness actionReadiness) {
        VisualGraphActionReadiness readiness = actionReadiness == null
                ? VisualGraphActionReadiness.from(false, List.of(), VisualGraphReadiness.notAssessed())
                : actionReadiness;
        String normalizedAction = "run".equals(action) ? "run" : "compile";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", normalizedAction);
        metadata.put("state", readiness.state());
        metadata.put("compileNow", readiness.compileNow());
        metadata.put("runNow", readiness.runNow());
        metadata.put("artifactKinds", readiness.artifactKinds());
        metadata.put("recommendedAction", readiness.recommendedAction());
        return VisualDiagnostic.error(
                "visual.action.%sBlocked".formatted(normalizedAction),
                "Visual graph action '%s' is blocked by readiness state '%s': %s"
                        .formatted(normalizedAction, readiness.state(), readiness.message()),
                "/actionReadiness",
                metadata
        );
    }

    private static VisualDiagnostic fromCompilerDiagnostic(VisualDslRunResponse.Diagnostic diagnostic) {
        String target = diagnostic.nodeId().isBlank()
                ? ""
                : "/nodes/" + diagnostic.nodeId() + (diagnostic.field().isBlank() ? "" : "/" + diagnostic.field());
        return new VisualDiagnostic(
                diagnostic.level(),
                "bloge.dsl",
                diagnostic.message(),
                target,
                diagnostic.line(),
                diagnostic.column()
        );
    }

    private static Object extractPath(Object root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank() || current == null) {
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                continue;
            }
            if (current instanceof List<?> list) {
                Integer index = listIndexSegment(segment);
                current = index == null || index >= list.size() ? null : list.get(index);
                continue;
            }
            current = recordAccessor(current, segment);
        }
        return current;
    }

    private static Integer listIndexSegment(String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index < 0 ? null : index;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Object recordAccessor(Object target, String accessor) {
        try {
            Method method = target.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
