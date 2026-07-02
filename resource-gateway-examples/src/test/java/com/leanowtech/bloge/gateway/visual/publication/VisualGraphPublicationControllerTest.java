package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph publication API.
 */
class VisualGraphPublicationControllerTest {

    @Test
    void listAndGetPublications() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        assertThat(controller.list()).containsExactly(stored);
        assertThat(controller.get(stored.publicationId()))
                .extracting(ResponseEntity::getBody)
                .isEqualTo(stored);
    }

    @Test
    void summariesExposeFrozenReadinessWithoutFullPublicationPayload() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        List<VisualGraphPublicationSummary> summaries = controller.summaries();

        assertThat(summaries)
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.schemaVersion()).isEqualTo("bloge.visualGraphPublicationSummary.v1");
                    assertThat(summary.publicationId()).isEqualTo(stored.publicationId());
                    assertThat(summary.draftId()).isEqualTo(stored.draftId());
                    assertThat(summary.draftRevision()).isEqualTo(stored.draftRevision());
                    assertThat(summary.graphName()).isEqualTo(stored.graphName());
                    assertThat(summary.artifactKind()).isEqualTo("EXECUTABLE");
                    assertThat(summary.valid()).isTrue();
                    assertThat(summary.nodeCount()).isEqualTo(1);
                    assertThat(summary.operatorDependencyCount()).isEqualTo(1);
                    assertThat(summary.runtimeReadinessStateCounts()).containsEntry("RUNTIME_EXECUTABLE", 1);
                    assertThat(summary.readiness().state()).isEqualTo("runtime-executable");
                });
    }

    @Test
    void listAndSummariesFilterPublicationsByAuthoringScope() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication included = repository.create(publication("tenant-a", "risk", "dev"));
        VisualGraphPublication excluded = repository.create(publication("tenant-b", "risk", "dev"));
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        assertThat(controller.list("tenant-a", "risk", "dev"))
                .extracting(VisualGraphPublication::publicationId)
                .containsExactly(included.publicationId());
        assertThat(controller.summaries("tenant-a", "risk", "dev"))
                .extracting(VisualGraphPublicationSummary::publicationId)
                .containsExactly(included.publicationId());
        assertThat(controller.summaries("tenant-b", "risk", "dev"))
                .extracting(VisualGraphPublicationSummary::publicationId)
                .containsExactly(excluded.publicationId());
    }

    @Test
    void getReturnsNotFoundForUnknownPublication() {
        VisualGraphPublicationController controller =
                new VisualGraphPublicationController(new InMemoryVisualGraphPublicationRepository(), runner(),
                        new InMemoryVisualGraphRunRepository());

        assertThat(controller.get("missing").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void dependenciesReturnsFrozenPublicationDependencyReport() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        ResponseEntity<GraphDraftDependencyReport> response = controller.dependencies(stored.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEqualTo(stored.dependencyReport());
        assertThat(response.getBody().draftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.fingerprintState()).isEqualTo("current");
                });
    }

    @Test
    void runPublicationDelegatesToRunner() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        CapturingRunService runner = new CapturingRunService();
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner, runs);

        ResponseEntity<VisualGraphRunResponse> response = controller.run(stored.publicationId(),
                new VisualStoredDraftRunRequest(Map.of("score", 720), "eligibility"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().runId()).isNotBlank();
        assertThat(runner.publication).isEqualTo(stored);
        assertThat(runner.context).containsEntry("score", 720);
        assertThat(runner.outputNode).isEqualTo("eligibility");
        VisualGraphRunRecord record = runs.find(response.getBody().runId()).orElseThrow();
        assertThat(record.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_PUBLICATION);
        assertThat(record.publicationId()).isEqualTo(stored.publicationId());
        assertThat(record.draftId()).isEqualTo(stored.draftId());
    }

    private static VisualGraphRunService runner() {
        return new CapturingRunService();
    }

    private static VisualGraphPublication publication() {
        return publication("demo-tenant", "local", "local");
    }

    private static VisualGraphPublication publication(String tenantId, String namespace, String environment) {
        OperatorDefinition operator = VisualCatalogTestSupport.eligibilityOperator("integer");
        GraphDraft draft = new GraphDraft(
                "",
                "draft-1",
                1,
                "visualPolicy",
                tenantId,
                namespace,
                environment,
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
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
                new VisualValidationResult(true, List.of(), VisualGraphReadiness.from(
                        draft,
                        Map.of("eligibility", operator),
                        List.of()
                )),
                new DslGenerationResult(true, "graph visualPolicy {}", List.of()),
                GraphDraftDependencyReport.from(draft, VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")))
        );
    }

    private static class CapturingRunService extends VisualGraphRunService {
        private VisualGraphPublication publication;
        private Map<String, Object> context;
        private String outputNode;

        CapturingRunService() {
            super(null, null, null);
        }

        @Override
        public VisualGraphRunResponse run(VisualGraphPublication publication,
                                          Map<String, Object> context,
                                          String outputNode) {
            this.publication = publication;
            this.context = context;
            this.outputNode = outputNode;
            return new VisualGraphRunResponse(
                    true,
                    true,
                    true,
                    publication.graphName(),
                    outputNode,
                    Map.of("ok", true),
                    Map.of(),
                    Map.of(),
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
