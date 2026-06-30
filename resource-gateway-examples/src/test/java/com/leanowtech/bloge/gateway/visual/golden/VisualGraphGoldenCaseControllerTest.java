package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph golden case API.
 */
class VisualGraphGoldenCaseControllerTest {

    @Test
    void saveListAndGetGoldenCase() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));

        ResponseEntity<VisualGraphGoldenCase> response = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenCase stored = response.getBody();
        assertThat(stored).isNotNull();
        assertThat(stored.caseId()).isNotBlank();
        assertThat(controller.list(publication.publicationId())).containsExactly(stored);
        assertThat(controller.get(stored.caseId()).getBody()).isEqualTo(stored);
    }

    @Test
    void runGoldenCaseRecordsRunAndPassesWhenOutputMatches() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        VisualGraphGoldenCase stored = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true))).getBody();

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenCaseRunResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.passed()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.run().runId()).isNotBlank();
        VisualGraphRunRecord record = runs.find(result.run().runId()).orElseThrow();
        assertThat(record.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_PUBLICATION);
        assertThat(record.publicationId()).isEqualTo(publication.publicationId());
    }

    @Test
    void runGoldenCaseReturnsMismatchDiagnostic() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        VisualGraphGoldenCase stored = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true))).getBody();

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .containsExactly("visual.golden.outputMismatch");
    }

    @Test
    void runPublicationGoldenCasesRecordsEachRunAndSummarizesFailures() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        VisualGraphGoldenCase passing = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true))).getBody();
        VisualGraphGoldenCase failing = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", false))).getBody();

        ResponseEntity<VisualGraphGoldenSuiteRunResult> response = controller.runPublication(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenSuiteRunResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.passed()).isFalse();
        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.passedCases()).isEqualTo(1);
        assertThat(result.failedCases()).isEqualTo(1);
        assertThat(result.results())
                .extracting(caseResult -> caseResult.goldenCase().caseId())
                .containsExactlyInAnyOrder(passing.caseId(), failing.caseId());
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.suiteFailed");
        assertThat(runs.all()).hasSize(2);
    }

    @Test
    void runPublicationGoldenCasesReturnsDiagnosticWhenNoCasesExist() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of()), runs);

        ResponseEntity<VisualGraphGoldenSuiteRunResult> response = controller.runPublication(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().totalCases()).isZero();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.noCases");
        assertThat(runs.all()).isEmpty();
    }

    @Test
    void runPublicationGoldenCasesReturnsNotFoundForUnknownPublication() {
        VisualGraphGoldenCaseController controller = controller(new InMemoryVisualGraphPublicationRepository(),
                new CapturingRunService(Map.of()));

        assertThat(controller.runPublication("missing-publication").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void certifyPublicationRunsSuiteStoresCertificationAndExposesLatestResult() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        controller.save(goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertification> response = controller.certify(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenCertification certification = response.getBody();
        assertThat(certification).isNotNull();
        assertThat(certification.certified()).isTrue();
        assertThat(certification.totalCases()).isEqualTo(1);
        assertThat(certification.passedCases()).isEqualTo(1);
        assertThat(certification.failedCases()).isZero();
        assertThat(certification.runIds()).hasSize(1);
        assertThat(runs.find(certification.runIds().get(0))).isPresent();
        assertThat(controller.certification(publication.publicationId()).getBody()).isEqualTo(certification);
    }

    @Test
    void certifyPublicationStoresFailedCertificationWhenSuiteFails() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        controller.save(goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertification> response = controller.certify(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().certified()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.suiteFailed");
        assertThat(controller.certification(publication.publicationId()).getBody()).isEqualTo(response.getBody());
    }

    @Test
    void certificationReturnsNotFoundWhenMissingOrPublicationUnknown() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of()));

        assertThat(controller.certification(publication.publicationId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.certify("missing-publication").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.certification("missing-publication").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void saveReturnsNotFoundForUnknownPublication() {
        VisualGraphGoldenCaseController controller = controller(new InMemoryVisualGraphPublicationRepository(),
                new CapturingRunService(Map.of()));

        assertThat(controller.save(goldenCase("missing-publication", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static VisualGraphGoldenCaseController controller(InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner) {
        return controller(publications, runner, new InMemoryVisualGraphRunRepository());
    }

    private static VisualGraphGoldenCaseController controller(InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner,
                                                             InMemoryVisualGraphRunRepository runs) {
        return new VisualGraphGoldenCaseController(
                new InMemoryVisualGraphGoldenCaseRepository(),
                publications,
                runner,
                runs,
                new InMemoryVisualGraphGoldenCertificationRepository(),
                new ObjectMapper().findAndRegisterModules()
        );
    }

    private static VisualGraphGoldenCase goldenCase(String publicationId, Object expectedOutput) {
        return new VisualGraphGoldenCase("", "", publicationId, "prime approval", "", "eligibility",
                Map.of("score", 720), expectedOutput, null);
    }

    private static VisualGraphPublication publication() {
        OperatorDefinition operator = VisualCatalogTestSupport.eligibilityOperator("integer");
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
                1,
                "visualPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("score")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", operator.fingerprint())
        );
        return VisualGraphPublication.from(
                draft,
                List.of(operator),
                new VisualValidationResult(true, List.of()),
                new DslGenerationResult(true, "graph visualPolicy {}", List.of())
        );
    }

    private static class CapturingRunService extends VisualGraphRunService {
        private final Object output;

        CapturingRunService(Object output) {
            super(null, null, null);
            this.output = output;
        }

        @Override
        public VisualGraphRunResponse run(VisualGraphPublication publication,
                                          Map<String, Object> context,
                                          String outputNode) {
            return new VisualGraphRunResponse(
                    true,
                    true,
                    true,
                    publication.graphName(),
                    outputNode,
                    output,
                    Map.of(outputNode, output),
                    Map.of(outputNode, "COMPLETED"),
                    1,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    publication.dsl()
            );
        }
    }
}
