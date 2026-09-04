package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.importer.DslImportPreviewRequest;
import com.leanowtech.bloge.gateway.visual.importer.DslImportService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Certifies every shipped reference example against the production parser and visual projector. */
class DslReferenceCertificationTest {

    @Test
    void everyPackagedExampleCompilesAndRoundTripsWithItsDeclaredBuiltIns() {
        DslReferenceBundleLoader.Bundle bundle = new DslReferenceBundleLoader(new ObjectMapper()).bundle();
        DslImportService importer = new DslImportService(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource(), new OperatorLibraryValidator());

        assertThat(bundle.examples()).isNotEmpty();
        bundle.examples().forEach(example -> {
            var projection = importer.preview(new DslImportPreviewRequest(
                    example.exampleId() + ".bloge", example.source(), List.of(), List.of(),
                    "agent-tdd-reference-certification", Map.of()));

            assertThat(projection.diagnostics())
                    .as("diagnostics for %s", example.exampleId())
                    .noneMatch(VisualDiagnostic::error);
            assertThat(projection.roundTrip().supported())
                    .as("round-trip for %s: %s", example.exampleId(), projection.roundTrip())
                    .isTrue();
        });
    }
}
