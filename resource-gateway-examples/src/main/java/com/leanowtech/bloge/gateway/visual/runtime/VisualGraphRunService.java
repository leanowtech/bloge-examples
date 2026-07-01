package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.example.DynamicGatewayComposerService;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunRequest;
import com.leanowtech.bloge.gateway.example.DynamicGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
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
    private final DynamicGatewayComposerService dynamicRunner;

    /**
     * @param validator visual draft validator
     * @param generator visual draft DSL generator
     * @param dynamicRunner existing dynamic BLOGE runner
     */
    public VisualGraphRunService(GraphDraftValidator validator,
                                 GraphDraftDslGenerator generator,
                                 DynamicGatewayComposerService dynamicRunner) {
        this.validator = validator;
        this.generator = generator;
        this.dynamicRunner = dynamicRunner;
    }

    /**
     * Validates, lowers, and compiles a visual graph draft without executing it.
     *
     * @param draft graph draft
     * @return generated DSL plus lowering/compiler diagnostics
     */
    public DslGenerationResult compile(GraphDraft draft) {
        List<VisualDiagnostic> fingerprintDiagnostics = requireOperatorFingerprintSnapshot(draft);
        if (!fingerprintDiagnostics.isEmpty()) {
            return new DslGenerationResult(false, "", fingerprintDiagnostics);
        }
        VisualValidationResult validation = validator.validate(draft);
        if (!validation.valid()) {
            return new DslGenerationResult(false, "", validation.diagnostics());
        }
        DslGenerationResult generated = generator.generate(draft);
        if (!generated.generated()) {
            return generated;
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>(generated.diagnostics());
        diagnostics.addAll(dynamicRunner.compileDiagnostics(generated.dsl()).stream()
                .map(VisualGraphRunService::fromCompilerDiagnostic)
                .toList());
        return new DslGenerationResult(true, generated.dsl(), diagnostics);
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
        List<VisualDiagnostic> fingerprintDiagnostics = requireOperatorFingerprintSnapshot(draft);
        if (!fingerprintDiagnostics.isEmpty()) {
            return blocked(draft, false, fingerprintDiagnostics,
                    List.of("Operator fingerprint snapshot is required."), "");
        }
        VisualValidationResult validation = validator.validate(draft);
        List<VisualDiagnostic> diagnostics = new ArrayList<>(validation.diagnostics());
        if (!validation.valid()) {
            return blocked(draft, false, diagnostics, List.of("Visual validation failed."), "");
        }
        List<VisualDiagnostic> contextDiagnostics = validateRuntimeContext(draft, context);
        diagnostics.addAll(contextDiagnostics);
        if (!contextDiagnostics.isEmpty()) {
            return blocked(draft, true, diagnostics, List.of("Runtime context validation failed."), "");
        }

        DslGenerationResult generated = generator.generate(draft);
        diagnostics.addAll(generated.diagnostics());
        if (!generated.generated()) {
            return blocked(draft, true, diagnostics, List.of("Visual DSL generation failed."), generated.dsl());
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
            return blocked(draft, true, diagnostics, List.of("Output node override validation failed."), generated.dsl());
        }

        DynamicGraphRunResponse dynamic = dynamicRunner.run(new DynamicGraphRunRequest(
                generated.dsl(),
                effectiveContext,
                selectedOutputNode
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
                generated.dsl()
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
        if (publication == null) {
            return blocked(null, false, List.of(VisualDiagnostic.error("visual.publication.missing",
                    "Visual graph publication is required.", "/publication")),
                    List.of("Visual graph publication is required."), "");
        }

        List<VisualDiagnostic> diagnostics = new ArrayList<>(publication.validation().diagnostics());
        if (!publication.validation().valid()) {
            return blocked(publication.draft(), false, diagnostics,
                    List.of("Published visual validation report contains errors."), publication.dsl());
        }
        diagnostics.addAll(publication.generation().diagnostics());
        if (!publication.generation().generated() || publication.dsl().isBlank()) {
            return blocked(publication.draft(), true, diagnostics,
                    List.of("Published visual DSL is not executable."), publication.dsl());
        }
        List<VisualDiagnostic> contextDiagnostics = validateRuntimeContext(publication.draft(), context);
        diagnostics.addAll(contextDiagnostics);
        if (!contextDiagnostics.isEmpty()) {
            return blocked(publication.draft(), true, diagnostics,
                    List.of("Runtime context validation failed."), publication.dsl());
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
                    publication.dsl());
        }

        DynamicGraphRunResponse dynamic = dynamicRunner.run(new DynamicGraphRunRequest(
                publication.dsl(),
                effectiveContext,
                selectedOutputNode
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
                publication.dsl()
        );
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
                diagnostics,
                errors,
                null,
                null,
                generatedDsl
        );
    }

    private static VisualDiagnostic fromCompilerDiagnostic(DynamicGraphRunResponse.Diagnostic diagnostic) {
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
