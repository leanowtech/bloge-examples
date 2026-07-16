package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.CompilationResult;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Compiler-backed semantic gate target verifier for the isolated test runtime.
 *
 * <p>A graph suite is accepted only when lowering and compiling the exact draft produces the same
 * composite graph/resource fingerprint that the immutable suite captured. An operator suite is
 * accepted only when its operator occurs in the draft and its current implementation, binding,
 * schema, composability, and resource closure fingerprint still matches. Names alone never bind a
 * governance decision to executable code.</p>
 */
@Service
@Profile("!production & (test | staging)")
public final class CompiledSemanticGateTargetVerifier implements SemanticGateTargetVerifier {
    private final GraphDraftDslGenerator generator;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final ObjectMapper objectMapper;

    /** Creates the verifier from the same runtime dependencies used by the test control plane. */
    public CompiledSemanticGateTargetVerifier(GraphDraftDslGenerator generator,
                                              OperatorRegistry operatorRegistry,
                                              ResourceRegistry resourceRegistry,
                                              ObjectMapper objectMapper) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.resourceRegistry = Objects.requireNonNull(resourceRegistry, "resourceRegistry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Verification verify(GraphDraft draft, TestSuite.Target target) {
        if (draft == null || target == null || target.id().isBlank()
                || target.fingerprint().isBlank()) {
            return Verification.rejected("SEMANTIC_TARGET_INVALID");
        }
        return switch (target.kind()) {
            case "GRAPH" -> verifyGraph(draft, target);
            case "OPERATOR" -> verifyOperator(draft, target);
            default -> Verification.rejected("SEMANTIC_TARGET_KIND_UNSUPPORTED");
        };
    }

    private Verification verifyGraph(GraphDraft draft, TestSuite.Target target) {
        if (!draft.graphName().equals(target.id())) {
            return Verification.rejected("GRAPH_TARGET_ID_MISMATCH");
        }
        DslGenerationResult generated = generator.generate(draft);
        if (!generated.generated()) {
            return Verification.rejected("GRAPH_DRAFT_LOWERING_FAILED");
        }
        CompilationResult compilation = new GraphLoader(operatorRegistry)
                .loadWithDiagnostics(generated.dsl());
        if (compilation == null || compilation.hasErrors() || compilation.graph() == null) {
            return Verification.rejected("GRAPH_DRAFT_COMPILATION_FAILED");
        }
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                objectMapper, compilation.graph(), resourceRegistry);
        if (!snapshot.certificationEligible()) {
            return Verification.rejected("GRAPH_TARGET_NOT_CERTIFIABLE");
        }
        return target.fingerprint().equals(snapshot.fingerprint())
                ? Verification.accepted()
                : Verification.rejected("GRAPH_TARGET_FINGERPRINT_STALE");
    }

    private Verification verifyOperator(GraphDraft draft, TestSuite.Target target) {
        boolean referenced = draft.nodes().stream()
                .anyMatch(node -> target.id().equals(node.operatorRef()));
        if (!referenced) {
            return Verification.rejected("OPERATOR_NOT_IN_DRAFT");
        }
        OperatorExecutionTargetSnapshot snapshot;
        try {
            snapshot = OperatorExecutionTargetSnapshot.capture(
                    objectMapper, target.id(), operatorRegistry, resourceRegistry);
        } catch (IllegalArgumentException notFound) {
            return Verification.rejected("OPERATOR_TARGET_NOT_FOUND");
        }
        if (!snapshot.certificationEligible()) {
            return Verification.rejected("OPERATOR_TARGET_NOT_CERTIFIABLE");
        }
        return target.fingerprint().equals(snapshot.fingerprint())
                ? Verification.accepted()
                : Verification.rejected("OPERATOR_TARGET_FINGERPRINT_STALE");
    }
}
