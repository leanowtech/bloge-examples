package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Certifies every shipped example through the complete production authoring pipeline. */
class DslReferenceCertificationTest {

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

        assertThat(bundle.examples()).isNotEmpty();
        bundle.examples().forEach(example -> {
            DslPreviewReceipt receipt = support.preview(new DslPreviewRequest(
                    example.exampleId() + ".bloge", example.source(), List.of(),
                    reference.authoringContextFingerprint()), identity);

            assertThat(receipt.authoringDiagnostics())
                    .as("diagnostics for %s", example.exampleId())
                    .noneMatch(diagnostic -> "ERROR".equals(diagnostic.level()));
            assertThat(receipt.stages())
                    .as("stages for %s", example.exampleId())
                    .extracting(DslPreviewReceipt.Stage::status)
                    .containsOnly("PASS");
            assertThat(receipt.accepted()).as("accepted %s", example.exampleId()).isTrue();
            assertThat(receipt.roundTrip().status())
                    .as("round-trip for %s: %s", example.exampleId(), receipt.roundTrip())
                    .isEqualTo("SUPPORTED");
        });
    }
}
