package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationBinding;
import com.leanowtech.bloge.gateway.visual.asset.VisualRuntimeBindingImplementationRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivation;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRuntimeAdapterActivationRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestCase;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRepository;
import com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuiteRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:graph-draft-dependency-snapshot-application;DB_CLOSE_DELAY=-1"
        })
class GraphDraftDependencySnapshotApplicationIntegrationTest {

    @Autowired private ToolStudioIntegrationService integration;
    @Autowired private GraphDraftRepository drafts;
    @Autowired private OperatorLibraryRegistry libraries;
    @Autowired private VisualRuntimeBindingImplementationRepository bindings;
    @Autowired private VisualRuntimeAdapterActivationRepository activations;
    @Autowired private VisualOperatorContractTestSuiteRepository suites;

    @Test
    void exportsStructuredRefsFromTheRealSpringAndDatabaseRepositories() {
        OperatorDefinition operator = operator();
        libraries.upsert(new OperatorLibrary("", "risk-spring", "Risk Spring", "3.2.1",
                "risk-runtime-team", OperatorLibrary.STATUS_ACTIVE, List.of(operator)));
        VisualRuntimeBindingImplementationBinding binding = bindings.create(binding(operator));
        VisualRuntimeAdapterActivation activation = activations.create(activation(binding));
        suites.save(suite(operator.operatorRef()));
        GraphDraft stored = drafts.save(draft(operator));

        IntegrationEnvelope<GraphDraftIntegrationBundle> envelope = integration.exportDraft(
                stored.draftId(), stored.revision(), context());

        assertThat(envelope.payload().dependencyProfile().snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.consistencyStatus()).isEqualTo("STABLE");
            assertThat(snapshot.operatorCount()).isEqualTo(1);
            assertThat(snapshot.operatorLibraryCount()).isEqualTo(1);
            assertThat(snapshot.runtimeBindingCount()).isEqualTo(1);
            assertThat(snapshot.contractSuiteCount()).isEqualTo(1);
        });
        assertThat(envelope.payload().dependencyProfile().operatorDependencies()).singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.operatorLibrary()).satisfies(library -> {
                        assertThat(library.libraryId()).isEqualTo("risk-spring");
                        assertThat(library.revision()).isEqualTo(1);
                        assertThat(library.version()).isEqualTo("3.2.1");
                        assertThat(library.owner()).isEqualTo("risk-runtime-team");
                    });
                    assertThat(dependency.runtimeBindings()).singleElement().satisfies(ref -> {
                        assertThat(ref.bindingId()).isEqualTo(binding.bindingId());
                        assertThat(ref.revision()).isEqualTo(binding.revision());
                        assertThat(ref.activationId()).isEqualTo(activation.activationId());
                        assertThat(ref.activationRevision()).isEqualTo(activation.revision());
                        assertThat(ref.ready()).isTrue();
                    });
                    assertThat(dependency.contractSuites()).singleElement().satisfies(ref -> {
                        assertThat(ref.suiteId()).isEqualTo("suite-risk-spring");
                        assertThat(ref.revision()).isEqualTo(1);
                        assertThat(ref.caseCount()).isEqualTo(1);
                    });
                    assertThat(dependency.readiness().state()).isEqualTo("EXTERNAL_RUNTIME_BOUND");
                });
        assertThat(integration.capabilities().payload().features())
                .containsEntry("graphDraftConsistentDependencySnapshot", true)
                .containsEntry("graphDraftStructuredDependencyRefs", true);
    }

    private static OperatorDefinition operator() {
        SchemaEnvelope scalar = new SchemaEnvelope("json-schema", "2020-12", Map.of("type", "string"));
        return new OperatorDefinition("", "risk:spring", "1.0.0",
                new OperatorDefinition.Display("Spring risk", "", List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", false, "risk-spring"),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("input", scalar, true, "")),
                        List.of(new OperatorDefinition.Port("output", scalar, true, ""))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("remote-worker", "", Map.of()), List.of());
    }

    private static VisualRuntimeBindingImplementationBinding binding(OperatorDefinition operator) {
        Instant time = Instant.parse("2026-07-13T00:00:00Z");
        return new VisualRuntimeBindingImplementationBinding("", "binding-risk-spring", 0,
                VisualRuntimeBindingImplementationBinding.STATE_BOUND, "success",
                operator.operatorRef(), operator.fingerprint(), "sha256:handoff", List.of(),
                null, null, null, "", "", List.of(), time, time);
    }

    private static VisualRuntimeAdapterActivation activation(
            VisualRuntimeBindingImplementationBinding binding) {
        return new VisualRuntimeAdapterActivation("", "activation-risk-spring", 0,
                VisualRuntimeAdapterActivation.STATE_ACTIVE, "success", binding.bindingId(), binding.revision(),
                binding.operatorRef(), binding.operatorFingerprint(), "remote-worker", "worker://risk-spring",
                "risk-runtime-team", "prod", VisualRuntimeAdapterActivation.HEALTH_HEALTHY,
                "runtime-platform", "integration-test", "healthy", List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    private static VisualOperatorContractTestSuite suite(String operatorRef) {
        VisualOperatorContractTestCase testCase = new VisualOperatorContractTestCase(
                "valid", Map.of("input", "customer-1"), Map.of(), Map.of("output", "LOW"), Map.of());
        return new VisualOperatorContractTestSuite("suite-risk-spring", "Risk Spring", "",
                List.of("regression"), new VisualOperatorContractTestSuiteRequest(operatorRef, List.of(testCase)));
    }

    private static GraphDraft draft(OperatorDefinition operator) {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "riskNode", operator.operatorRef(), "Risk", Map.of(), Map.of(), new GraphDraft.Position(100, 100));
        return new GraphDraft("", "draft-risk-spring", 0, "riskSpringGraph",
                "tenant-a", "knowledge", "prod", "DRAFT", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(node), List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection(node.id(), "output"),
                Map.of(node.id(), operator.fingerprint()), Map.of(node.id(), operator),
                GraphDraft.RevisionMetadata.patch("integration-test", "test", "snapshot", List.of(), ""));
    }

    private static IntegrationRequestContext context() {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-spring-snapshot");
    }
}
