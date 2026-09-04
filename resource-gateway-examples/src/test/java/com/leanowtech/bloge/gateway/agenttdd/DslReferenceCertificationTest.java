package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Certifies every shipped example through the complete production authoring pipeline. */
class DslReferenceCertificationTest {

    @Test
    void humanReferenceNamesEveryStableRuntimeTopic() throws IOException {
        Path repositoryRoot = Path.of(System.getProperty("user.dir"));
        if (!Files.isDirectory(repositoryRoot.resolve("docs"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        String humanReference = Files.readString(
                repositoryRoot.resolve("docs/ai/bloge-dsl-syntax-reference.md"));

        new DslReferenceBundleLoader(new ObjectMapper()).bundle().defaultTopics()
                .forEach(topicId -> assertThat(humanReference).contains("`" + topicId + "`"));
    }

    @Test
    void everyPackagedExampleCompilesAndRoundTripsWithItsDeclaredBuiltIns() {
        ObjectMapper mapper = new ObjectMapper();
        DslReferenceBundleLoader.Bundle bundle = new DslReferenceBundleLoader(mapper).bundle();
        OperatorLibraryRegistry libraries = mock(OperatorLibraryRegistry.class);
        when(libraries.all()).thenReturn(List.of());
        when(libraries.find(anyString())).thenReturn(Optional.empty());
        AgentDslAuthoringSupport support = new AgentDslAuthoringSupport(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource(), libraries, mapper);
        IntegrationRequestContext identity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "certifier",
                "", "AGENT_TDD_READ", "reference-certification");
        DslReferenceSnapshot reference = support.reference(new DslReferenceRequest(
                List.of(), List.of(), List.of(), true), identity);
        OperatorLibrary designLibrary = VisualCatalogTestSupport.designOnlyEligibilityLibrary("number");
        InMemoryOperatorLibraryRegistry designLibraries = new InMemoryOperatorLibraryRegistry();
        designLibraries.upsert(designLibrary);
        AgentDslAuthoringSupport designSupport = new AgentDslAuthoringSupport(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(designLibrary),
                designLibraries, mapper);
        DslReferenceSnapshot designReference = designSupport.reference(new DslReferenceRequest(
                List.of("risk-policy-design"), List.of(), List.of(), true), identity);

        assertThat(bundle.topics()).extracting(DslReferenceSnapshot.Topic::topicId)
                .contains("graph-root", "node-declaration", "node-bindings", "types-and-nullability",
                        "transform", "decision-table", "execution-controls", "functions",
                        "round-trip", "common-errors");
        assertThat(bundle.examples()).extracting(DslReferenceBundleLoader.BundleExample::exampleId)
                .contains("graph-named-ports", "graph-nullable-collection-schema",
                        "graph-decision-boundaries", "graph-design-only-operator");
        bundle.examples().forEach(example -> {
            boolean designOnly = example.exampleId().equals("graph-design-only-operator");
            AgentDslAuthoringSupport selectedSupport = designOnly ? designSupport : support;
            DslReferenceSnapshot selectedReference = designOnly ? designReference : reference;
            List<String> libraryRefs = designOnly ? List.of("risk-policy-design") : List.of();
            DslPreviewReceipt receipt = selectedSupport.preview(new DslPreviewRequest(
                    example.exampleId() + ".bloge", example.source(), libraryRefs,
                    selectedReference.authoringContextFingerprint()), identity);

            assertThat(receipt.authoringDiagnostics())
                    .as("diagnostics for %s", example.exampleId())
                    .noneMatch(diagnostic -> "ERROR".equals(diagnostic.level()));
            assertThat(receipt.stages())
                    .as("stages for %s", example.exampleId())
                    .extracting(DslPreviewReceipt.Stage::status)
                    .containsOnly("PASS");
            assertThat(receipt.accepted()).as("accepted %s", example.exampleId()).isTrue();
            if (designOnly) {
                assertThat(receipt.roundTrip().status())
                        .as("round-trip for %s: %s", example.exampleId(), receipt.roundTrip())
                        .isEqualTo("DEFERRED_DESIGN_ONLY");
                assertThat(receipt.roundTrip().driftKinds()).containsExactly("DESIGN_ONLY_OPERATOR");
            } else {
                assertThat(receipt.roundTrip().status())
                        .as("round-trip for %s: %s", example.exampleId(), receipt.roundTrip())
                        .isEqualTo("SUPPORTED");
            }
        });
    }
}
