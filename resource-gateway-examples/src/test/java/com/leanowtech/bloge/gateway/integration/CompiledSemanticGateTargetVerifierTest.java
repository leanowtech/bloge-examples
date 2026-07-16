package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifest;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorComposabilityManifestProvider;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompiledSemanticGateTargetVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
    private final ResourceRegistry resources = new EmptyResourceRegistry();
    private final GraphDraftDslGenerator generator = new GraphDraftDslGenerator(
            VisualCatalogTestSupport.catalogWithLibrary(
                    VisualCatalogTestSupport.eligibilityLibrary("number")));
    private final CompiledSemanticGateTargetVerifier verifier =
            new CompiledSemanticGateTargetVerifier(generator, operators, resources, mapper);

    @Test
    void bindsGraphSuiteToCompiledExactDraftFingerprintInsteadOfGraphNameAlone() {
        GraphDraft draft = graphDraft();
        DslGenerationResult generated = generator.generate(draft);
        var compilation = new GraphLoader(operators).loadWithDiagnostics(generated.dsl());
        String fingerprint = GraphExecutionTargetSnapshot.capture(
                mapper, compilation.graph(), resources).fingerprint();

        assertThat(verifier.verify(draft,
                new TestSuite.Target("GRAPH", draft.graphName(), fingerprint)).matched()).isTrue();
        assertThat(verifier.verify(draft,
                new TestSuite.Target("GRAPH", draft.graphName(), sha('f'))).reason())
                .isEqualTo("GRAPH_TARGET_FINGERPRINT_STALE");
        assertThat(verifier.verify(draft,
                new TestSuite.Target("GRAPH", "sameLookingName", fingerprint)).reason())
                .isEqualTo("GRAPH_TARGET_ID_MISMATCH");
    }

    @Test
    void bindsOperatorSuiteOnlyWhenCurrentRuntimeTargetOccursInDraft() {
        operators.register("echo", new EchoOperator());
        String fingerprint = OperatorExecutionTargetSnapshot.capture(
                mapper, "echo", operators, resources).fingerprint();
        GraphDraft referenced = operatorDraft("echo");

        SemanticGateTargetVerifier.Verification exact = verifier.verify(referenced,
                new TestSuite.Target("OPERATOR", "echo", fingerprint));
        assertThat(exact.matched()).as(exact.reason()).isTrue();
        assertThat(verifier.verify(referenced,
                new TestSuite.Target("OPERATOR", "echo", sha('e'))).reason())
                .isEqualTo("OPERATOR_TARGET_FINGERPRINT_STALE");
        assertThat(verifier.verify(operatorDraft("another"),
                new TestSuite.Target("OPERATOR", "echo", fingerprint)).reason())
                .isEqualTo("OPERATOR_NOT_IN_DRAFT");
    }

    @Test
    void propagatesRuntimeAuthorityOutageInsteadOfMisclassifyingTargetAsStale() {
        operators.register("echo", new EchoOperator());
        ResourceRegistry unavailable = new ResourceRegistry() {
            @Override public ResourceDescriptor resolve(String resourceId) {
                throw new IllegalStateException("registry offline");
            }
            @Override public boolean contains(String resourceId) {
                throw new IllegalStateException("registry offline");
            }
            @Override public Collection<ResourceDescriptor> all() {
                throw new IllegalStateException("registry offline");
            }
        };
        CompiledSemanticGateTargetVerifier outageVerifier =
                new CompiledSemanticGateTargetVerifier(generator, operators, unavailable, mapper);

        assertThatThrownBy(() -> outageVerifier.verify(operatorDraft("echo"),
                new TestSuite.Target("OPERATOR", "echo", sha('e'))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry offline");
    }

    private static GraphDraft graphDraft() {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode("eligibility", "risk:eligibility",
                "Eligibility", Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")), Map.of(),
                new GraphDraft.Position(100, 100));
        return new GraphDraft("", "draft-risk", 3, "riskGraph", "tenant-a", "knowledge", "test",
                "DRAFT", SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "number"),
                "amount", Map.of("type", "number")), List.of("score", "amount")),
                List.of(node), List.of(), Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""));
    }

    private static GraphDraft operatorDraft(String operatorRef) {
        return new GraphDraft("", "draft-operator", 2, "operatorGraph", "tenant-a", "knowledge",
                "test", "DRAFT", SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode("subject", operatorRef, "Subject", Map.of(), Map.of(),
                        new GraphDraft.Position(100, 100))), List.of(), Map.of(),
                new GraphDraft.OutputSelection("subject", ""));
    }

    private static String sha(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            throw new IllegalArgumentException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return false;
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of();
        }
    }

    private static final class EchoOperator implements Operator<Object, Object>,
            OperatorComposabilityManifestProvider {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }

        @Override
        public OperatorComposabilityManifest operatorComposabilityManifest() {
            return OperatorComposabilityManifest.selfContained(
                    "test:echo", "sha256:" + "c".repeat(64));
        }
    }
}
