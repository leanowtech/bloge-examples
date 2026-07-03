package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
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

        ResponseEntity<?> response = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenCase stored = (VisualGraphGoldenCase) response.getBody();
        assertThat(stored).isNotNull();
        assertThat(stored.caseId()).isNotBlank();
        assertThat(controller.list(publication.publicationId())).containsExactly(stored);
        assertThat(controller.get(stored.caseId()).getBody()).isEqualTo(stored);
    }

    @Test
    void saveReturnsStructuredPersistenceFailureWithoutStoring() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        FailingGoldenCaseRepository goldenCases = new FailingGoldenCaseRepository(FailingGoldenMutation.SAVE);
        VisualGraphGoldenCaseController controller = controller(goldenCases, publications,
                new CapturingRunService(Map.of()));

        ResponseEntity<?> response = controller.save(goldenCase(publication.publicationId(),
                Map.of("approved", true)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.savePersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/case");
                    assertThat(diagnostic.metadata())
                            .containsEntry("publicationId", publication.publicationId())
                            .containsEntry("caseId", "")
                            .containsEntry("mutationAction", "SAVE")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(goldenCases.findByPublicationId(publication.publicationId())).isEmpty();
    }

    @Test
    void deleteGoldenCaseRemovesItAndMakesCertificationStale() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)));
        VisualGraphGoldenCase first = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));
        VisualGraphGoldenCase second = saveCase(controller, new VisualGraphGoldenCase("", "",
                publication.publicationId(), "alternate approval", "", "eligibility",
                Map.of("score", 760), Map.of("approved", true), null));
        VisualGraphGoldenCertification certification = controller.certify(publication.publicationId()).getBody();

        ResponseEntity<?> response = controller.delete(first.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(first.caseId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.list(publication.publicationId())).containsExactly(second);
        ResponseEntity<VisualGraphGoldenCertificationStatus> status =
                controller.certificationStatus(publication.publicationId());
        assertThat(status.getBody()).isNotNull();
        assertThat(status.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.STALE);
        assertThat(status.getBody().caseCount()).isOne();
        assertThat(status.getBody().caseSetFingerprint()).isNotEqualTo(certification.caseSetFingerprint());
        assertThat(controller.delete(first.caseId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteReturnsStructuredPersistenceFailureAndKeepsCurrentCase() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        FailingGoldenCaseRepository goldenCases = new FailingGoldenCaseRepository(FailingGoldenMutation.DELETE);
        VisualGraphGoldenCase stored = goldenCases.seed(goldenCase(publication.publicationId(),
                Map.of("approved", true)));
        VisualGraphGoldenCaseController controller = controller(goldenCases, publications,
                new CapturingRunService(Map.of()));

        ResponseEntity<?> response = controller.delete(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.deletePersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/caseId");
                    assertThat(diagnostic.metadata())
                            .containsEntry("publicationId", publication.publicationId())
                            .containsEntry("caseId", stored.caseId())
                            .containsEntry("mutationAction", "DELETE")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(goldenCases.find(stored.caseId())).contains(stored);
    }

    @Test
    void saveRejectsGoldenCaseWhenContextViolatesPublicationInputSchema() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = new VisualGraphGoldenCase("", "", publication.publicationId(),
                "bad context", "", "eligibility", Map.of("score", "high"),
                Map.of("approved", true), null);

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.context.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/context/score");
                });
    }

    @Test
    void saveRejectsGoldenCaseWithUnsupportedSchemaVersion() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = new VisualGraphGoldenCase("bloge.visualGraphGoldenCase.v2", "",
                publication.publicationId(), "future case", "", "eligibility",
                Map.of("score", 720), Map.of("approved", true), null);

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void saveRejectsGoldenCaseWhenOutputNodeIsUnknown() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = new VisualGraphGoldenCase("", "", publication.publicationId(),
                "bad output", "", "missingNode", Map.of("score", 720),
                Map.of("approved", true), null);

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.outputNode.unknown");
                    assertThat(diagnostic.target()).isEqualTo("/outputNode");
                });
    }

    @Test
    void saveRejectsGoldenCaseWhenAssertionPathIsNotJsonPointer() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = goldenCaseWithAssertions(publication.publicationId(),
                List.of(assertion(VisualGraphGoldenAssertion.Mode.PATH_EXISTS, "approved", null)));

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.assertionInvalidPath");
                    assertThat(diagnostic.target()).isEqualTo("/assertions/0/path");
                });
    }

    @Test
    void saveRejectsGoldenCaseWhenSchemaAssertionExpectedValueIsNotSchemaObject() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = goldenCaseWithAssertions(publication.publicationId(),
                List.of(assertion(VisualGraphGoldenAssertion.Mode.OUTPUT_MATCHES_SCHEMA, "", "boolean")));

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.assertionSchemaInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/assertions/0/expectedValue");
                });
    }

    @Test
    void saveRejectsGoldenCaseWhenApproximateAssertionExpectedValueIsInvalid() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = goldenCaseWithAssertions(publication.publicationId(),
                List.of(assertion(VisualGraphGoldenAssertion.Mode.PATH_APPROX_EQUALS, "/score",
                        Map.of("value", "720", "tolerance", 0.1))));

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.assertionToleranceInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/assertions/0/expectedValue/value");
                });
    }

    @Test
    void saveRejectsGoldenCaseWhenApproximateAssertionHasNoPositiveTolerance() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications, new CapturingRunService(Map.of()));
        VisualGraphGoldenCase testCase = goldenCaseWithAssertions(publication.publicationId(),
                List.of(approxAssertion("/score", 720, 0.0, 0.0)));

        ResponseEntity<?> response = controller.save(testCase);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult validation = (VisualValidationResult) response.getBody();
        assertThat(validation.valid()).isFalse();
        assertThat(validation.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.assertionToleranceInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/assertions/0/expectedValue");
                });
    }

    @Test
    void runGoldenCaseRecordsRunAndPassesWhenOutputMatches() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        VisualGraphGoldenCase stored = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));

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
    void runGoldenCaseReturnsStructuredRunHistoryPersistenceFailure() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        FailingRunRepository runs = new FailingRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        VisualGraphGoldenCase stored = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        VisualGraphGoldenCaseRunResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.passed()).isFalse();
        assertThat(result.run().runId()).isBlank();
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.runHistoryPersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/run");
                    assertThat(diagnostic.metadata())
                            .containsEntry("publicationId", publication.publicationId())
                            .containsEntry("caseId", stored.caseId())
                            .containsEntry("graphName", publication.graphName())
                            .containsEntry("outputNode", stored.outputNode())
                            .containsEntry("mutationAction", "RUN_HISTORY")
                            .containsEntry("exceptionType", "IllegalStateException");
                });
        assertThat(runs.all()).isEmpty();
    }

    @Test
    void runGoldenCasePassesWhenAssertionsMatch() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true, "decision", "APPROVED")));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(
                        assertion(VisualGraphGoldenAssertion.Mode.PATH_EQUALS, "/approved", true),
                        assertion(VisualGraphGoldenAssertion.Mode.PATH_EXISTS, "/decision", null),
                        assertion(VisualGraphGoldenAssertion.Mode.PATH_ABSENT, "/warnings", null)
                )));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isTrue();
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void runGoldenCasePassesWhenApproximateAssertionIsWithinTolerance() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("score", 720.04)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(approxAssertion("/score", 720.0, 0.1, 0.0))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isTrue();
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void runGoldenCasePassesWhenApproximateAssertionIsWithinRelativeTolerance() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("score", 101.0)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(approxAssertion("/score", 100.0, 0.0, 0.02))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isTrue();
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void runGoldenCaseFailsWhenApproximateAssertionIsOutsideTolerance() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("score", 720.5)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(approxAssertion("/score", 720.0, 0.1, 0.0))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.assertionFailed");
        assertThat(response.getBody().diagnostics().getFirst().target())
                .isEqualTo("/assertions/0/expectedValue");
    }

    @Test
    void runGoldenCaseFailsWhenApproximateAssertionPathIsNotNumeric() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("score", "720")));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(approxAssertion("/score", 720.0, 0.1, 0.0))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.assertionFailed");
        assertThat(response.getBody().diagnostics().getFirst().target())
                .isEqualTo("/assertions/0/path");
    }

    @Test
    void runGoldenCasePassesWhenOutputMatchesSchemaAssertion() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true, "decision", "APPROVED")));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(schemaAssertion(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "approved", Map.of("type", "boolean"),
                                "decision", Map.of("type", "string")
                        ),
                        "required", List.of("approved"),
                        "additionalProperties", true
                )))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isTrue();
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void runGoldenCaseFailsWhenOutputViolatesSchemaAssertion() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", "yes")));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(schemaAssertion(Map.of(
                        "type", "object",
                        "properties", Map.of("approved", Map.of("type", "boolean")),
                        "required", List.of("approved"),
                        "additionalProperties", false
                )))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.schemaAssertionFailed");
        assertThat(response.getBody().diagnostics().getFirst().target())
                .isEqualTo("/assertions/0/expectedValue/approved");
    }

    @Test
    void runGoldenCaseReturnsAssertionFailureDiagnostic() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCaseWithAssertions(publication.publicationId(),
                List.of(assertion(VisualGraphGoldenAssertion.Mode.PATH_EQUALS, "/approved", true))));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.assertionFailed");
        assertThat(response.getBody().diagnostics().getFirst().target())
                .isEqualTo("/assertions/0/expectedValue");
    }

    @Test
    void runGoldenCaseReturnsInvalidJsonPointerDiagnostic() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphGoldenCaseRepository goldenCases = new InMemoryVisualGraphGoldenCaseRepository();
        VisualGraphGoldenCaseController controller = controller(goldenCases, publications,
                new CapturingRunService(Map.of("approved", true)));
        VisualGraphGoldenCase stored = goldenCases.save(new VisualGraphGoldenCase("", "",
                publication.publicationId(), "legacy bad assertion", "", "eligibility",
                Map.of("score", 720), Map.of("legacy", "ignored"),
                List.of(assertion(VisualGraphGoldenAssertion.Mode.PATH_EXISTS, "approved", null)),
                null));

        ResponseEntity<VisualGraphGoldenCaseRunResult> response = controller.run(stored.caseId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().passed()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.assertionInvalidPath");
        assertThat(response.getBody().diagnostics().getFirst().target())
                .isEqualTo("/assertions/0/path");
    }

    @Test
    void runGoldenCaseReturnsMismatchDiagnostic() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));

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
        VisualGraphGoldenCase passing = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));
        VisualGraphGoldenCase failing = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", false)));

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
    void runPublicationGoldenCasesReturnsConflictWhenRunHistoryPersistenceFails() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        FailingRunRepository runs = new FailingRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));
        saveCase(controller, new VisualGraphGoldenCase("", "", publication.publicationId(),
                "alternate approval", "", "eligibility", Map.of("score", 760),
                Map.of("approved", true), null));

        ResponseEntity<VisualGraphGoldenSuiteRunResult> response =
                controller.runPublication(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        VisualGraphGoldenSuiteRunResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.passed()).isFalse();
        assertThat(result.totalCases()).isEqualTo(2);
        assertThat(result.failedCases()).isEqualTo(2);
        assertThat(result.diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.runHistoryPersistenceFailed", "visual.golden.suiteFailed");
        assertThat(result.diagnostics().getFirst().metadata())
                .containsEntry("publicationId", publication.publicationId())
                .containsEntry("mutationAction", "RUN_HISTORY")
                .containsEntry("failedRunHistoryRecords", 2L)
                .containsEntry("totalCases", 2);
        assertThat(result.results())
                .allSatisfy(caseResult -> assertThat(caseResult.diagnostics())
                        .extracting(VisualDiagnostic::code)
                        .containsExactly("visual.golden.runHistoryPersistenceFailed"));
        assertThat(runs.all()).isEmpty();
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
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertification> response = controller.certify(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VisualGraphGoldenCertification certification = response.getBody();
        assertThat(certification).isNotNull();
        assertThat(certification.certified()).isTrue();
        assertThat(certification.totalCases()).isEqualTo(1);
        assertThat(certification.passedCases()).isEqualTo(1);
        assertThat(certification.failedCases()).isZero();
        assertThat(certification.runIds()).hasSize(1);
        assertThat(certification.caseSetFingerprint()).isNotBlank();
        assertThat(runs.find(certification.runIds().get(0))).isPresent();
        assertThat(controller.certification(publication.publicationId()).getBody()).isEqualTo(certification);
    }

    @Test
    void certifyPublicationReturnsConflictWhenRunHistoryPersistenceFails() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        FailingRunRepository runs = new FailingRunRepository();
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)), runs);
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertification> response = controller.certify(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        VisualGraphGoldenCertification certification = response.getBody();
        assertThat(certification).isNotNull();
        assertThat(certification.certified()).isFalse();
        assertThat(certification.runIds()).isEmpty();
        assertThat(certification.diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.runHistoryPersistenceFailed", "visual.golden.suiteFailed");
        assertThat(controller.certification(publication.publicationId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(runs.all()).isEmpty();
    }

    @Test
    void certifyPublicationReturnsStructuredCertificationPersistenceFailure() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        FailingGoldenCertificationRepository certifications = new FailingGoldenCertificationRepository();
        VisualGraphGoldenCaseController controller = controller(
                new InMemoryVisualGraphGoldenCaseRepository(),
                publications,
                new CapturingRunService(Map.of("approved", true)),
                runs,
                certifications
        );
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertification> response = controller.certify(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        VisualGraphGoldenCertification certification = response.getBody();
        assertThat(certification).isNotNull();
        assertThat(certification.certified()).isFalse();
        assertThat(certification.runIds()).hasSize(1);
        assertThat(runs.find(certification.runIds().getFirst())).isPresent();
        assertThat(certification.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.golden.certificationPersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/certification");
                    assertThat(diagnostic.metadata())
                            .containsEntry("publicationId", publication.publicationId())
                            .containsEntry("mutationAction", "CERTIFY")
                            .containsEntry("exceptionType", "IllegalStateException");
                    assertThat(diagnostic.metadata().get("caseSetFingerprint")).isInstanceOf(String.class);
                    assertThat((List<?>) diagnostic.metadata().get("runIds")).hasSize(1);
                });
        assertThat(certifications.find(publication.publicationId())).isEmpty();
    }

    @Test
    void certifyPublicationStoresFailedCertificationWhenSuiteFails() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));

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
    void certificationStatusRequiresGoldenCases() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of()));

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.MISSING_CASES);
        assertThat(response.getBody().promotionReady()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.status.noCases");
    }

    @Test
    void certificationStatusRequiresCertificationRun() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of()));
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.UNCERTIFIED);
        assertThat(response.getBody().promotionReady()).isFalse();
        assertThat(response.getBody().caseSetFingerprint()).isNotBlank();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.status.uncertified");
    }

    @Test
    void certificationStatusIsPromotionReadyAfterPassingCertification() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)));
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));
        VisualGraphGoldenCertification certification = controller.certify(publication.publicationId()).getBody();

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.CERTIFIED);
        assertThat(response.getBody().promotionReady()).isTrue();
        assertThat(response.getBody().certification()).isEqualTo(certification);
        assertThat(response.getBody().caseSetFingerprint()).isEqualTo(certification.caseSetFingerprint());
        assertThat(response.getBody().diagnostics()).isEmpty();
    }

    @Test
    void certificationStatusFailsWhenLatestCertificationFailed() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", false)));
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));
        controller.certify(publication.publicationId());

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.FAILED);
        assertThat(response.getBody().promotionReady()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.status.failed", "visual.golden.suiteFailed");
    }

    @Test
    void certificationStatusBecomesStaleWhenCasesChangeAfterCertification() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)));
        saveCase(controller, goldenCase(publication.publicationId(), Map.of("approved", true)));
        VisualGraphGoldenCertification certification = controller.certify(publication.publicationId()).getBody();
        saveCase(controller, new VisualGraphGoldenCase("", "", publication.publicationId(), "declined approval",
                "", "eligibility", Map.of("score", 500), Map.of("approved", false), null));

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.STALE);
        assertThat(response.getBody().promotionReady()).isFalse();
        assertThat(response.getBody().caseCount()).isEqualTo(2);
        assertThat(response.getBody().certification()).isEqualTo(certification);
        assertThat(response.getBody().caseSetFingerprint()).isNotEqualTo(certification.caseSetFingerprint());
        assertThat(response.getBody().diagnostics())
                .extracting(VisualDiagnostic::code)
                .containsExactly("visual.golden.status.stale");
    }

    @Test
    void certificationStatusBecomesStaleWhenCaseAssertionsChangeAfterCertification() {
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication publication = publications.create(publication());
        VisualGraphGoldenCaseController controller = controller(publications,
                new CapturingRunService(Map.of("approved", true)));
        VisualGraphGoldenCase stored = saveCase(controller, goldenCase(publication.publicationId(),
                Map.of("approved", true)));
        VisualGraphGoldenCertification certification = controller.certify(publication.publicationId()).getBody();
        saveCase(controller, new VisualGraphGoldenCase("", stored.caseId(), publication.publicationId(),
                stored.name(), stored.description(), stored.outputNode(), stored.context(),
                stored.expectedOutput(), List.of(assertion(VisualGraphGoldenAssertion.Mode.PATH_EQUALS,
                        "/approved", true)), stored.createdAt()));

        ResponseEntity<VisualGraphGoldenCertificationStatus> response =
                controller.certificationStatus(publication.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(VisualGraphGoldenCertificationStatus.Status.STALE);
        assertThat(response.getBody().certification()).isEqualTo(certification);
        assertThat(response.getBody().caseSetFingerprint()).isNotEqualTo(certification.caseSetFingerprint());
    }

    @Test
    void saveReturnsNotFoundForUnknownPublication() {
        VisualGraphGoldenCaseController controller = controller(new InMemoryVisualGraphPublicationRepository(),
                new CapturingRunService(Map.of()));

        assertThat(controller.save(goldenCase("missing-publication", Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static VisualGraphGoldenCase saveCase(VisualGraphGoldenCaseController controller,
                                                  VisualGraphGoldenCase testCase) {
        ResponseEntity<?> response = controller.save(testCase);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(VisualGraphGoldenCase.class);
        return (VisualGraphGoldenCase) response.getBody();
    }

    private static VisualGraphGoldenCaseController controller(InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner) {
        return controller(publications, runner, new InMemoryVisualGraphRunRepository());
    }

    private static VisualGraphGoldenCaseController controller(VisualGraphGoldenCaseRepository goldenCases,
                                                             InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner) {
        return controller(goldenCases, publications, runner, new InMemoryVisualGraphRunRepository());
    }

    private static VisualGraphGoldenCaseController controller(InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner,
                                                             InMemoryVisualGraphRunRepository runs) {
        return controller(new InMemoryVisualGraphGoldenCaseRepository(), publications, runner, runs);
    }

    private static VisualGraphGoldenCaseController controller(VisualGraphGoldenCaseRepository goldenCases,
                                                             InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner,
                                                             VisualGraphRunRepository runs) {
        return controller(goldenCases, publications, runner, runs,
                new InMemoryVisualGraphGoldenCertificationRepository());
    }

    private static VisualGraphGoldenCaseController controller(VisualGraphGoldenCaseRepository goldenCases,
                                                             InMemoryVisualGraphPublicationRepository publications,
                                                             VisualGraphRunService runner,
                                                             VisualGraphRunRepository runs,
                                                             VisualGraphGoldenCertificationRepository certifications) {
        return new VisualGraphGoldenCaseController(
                goldenCases,
                publications,
                runner,
                runs,
                certifications,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    private static VisualGraphGoldenCase goldenCase(String publicationId, Object expectedOutput) {
        return new VisualGraphGoldenCase("", "", publicationId, "prime approval", "", "eligibility",
                Map.of("score", 720), expectedOutput, null);
    }

    private static VisualGraphGoldenCase goldenCaseWithAssertions(String publicationId,
                                                                  List<VisualGraphGoldenAssertion> assertions) {
        return new VisualGraphGoldenCase("", "", publicationId, "prime approval", "", "eligibility",
                Map.of("score", 720), Map.of("legacy", "ignored"), assertions, null);
    }

    private static VisualGraphGoldenAssertion assertion(VisualGraphGoldenAssertion.Mode mode,
                                                       String path,
                                                       Object expectedValue) {
        return new VisualGraphGoldenAssertion(mode, path, expectedValue);
    }

    private static VisualGraphGoldenAssertion schemaAssertion(Object schema) {
        return assertion(VisualGraphGoldenAssertion.Mode.OUTPUT_MATCHES_SCHEMA, "", schema);
    }

    private static VisualGraphGoldenAssertion approxAssertion(String path,
                                                             double value,
                                                             double tolerance,
                                                             double relativeTolerance) {
        return assertion(VisualGraphGoldenAssertion.Mode.PATH_APPROX_EQUALS, path,
                Map.of("value", value, "tolerance", tolerance, "relativeTolerance", relativeTolerance));
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
                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
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

    private enum FailingGoldenMutation {
        SAVE,
        DELETE
    }

    private static final class FailingGoldenCaseRepository extends InMemoryVisualGraphGoldenCaseRepository {
        private final FailingGoldenMutation mutation;

        private FailingGoldenCaseRepository(FailingGoldenMutation mutation) {
            this.mutation = mutation;
        }

        private VisualGraphGoldenCase seed(VisualGraphGoldenCase testCase) {
            return super.save(testCase);
        }

        @Override
        public VisualGraphGoldenCase save(VisualGraphGoldenCase testCase) {
            if (mutation == FailingGoldenMutation.SAVE) {
                throw new IllegalStateException("Injected golden case save failure");
            }
            return super.save(testCase);
        }

        @Override
        public boolean delete(String caseId) {
            if (mutation == FailingGoldenMutation.DELETE) {
                throw new IllegalStateException("Injected golden case delete failure");
            }
            return super.delete(caseId);
        }
    }

    private static final class FailingRunRepository extends InMemoryVisualGraphRunRepository {
        @Override
        public VisualGraphRunRecord create(VisualGraphRunRecord record) {
            throw new IllegalStateException("Injected run history persistence failure");
        }
    }

    private static final class FailingGoldenCertificationRepository
            extends InMemoryVisualGraphGoldenCertificationRepository {
        @Override
        public VisualGraphGoldenCertification save(VisualGraphGoldenCertification certification) {
            throw new IllegalStateException("Injected golden certification persistence failure");
        }
    }
}
