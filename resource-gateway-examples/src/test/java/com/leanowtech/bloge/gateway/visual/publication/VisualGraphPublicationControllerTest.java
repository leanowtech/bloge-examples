package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
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

import java.time.Instant;
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
    void exportPublicationReturnsPortableBundle() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationExportBundle> response = controller.export(stored.publicationId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().schemaVersion())
                .isEqualTo(VisualGraphPublicationExportBundle.SCHEMA_VERSION);
        assertThat(response.getBody().bundleFingerprint()).startsWith("sha256:");
        assertThat(response.getBody().bundleFingerprint()).hasSize(71);
        assertThat(response.getBody().bundleFingerprintVerified()).isTrue();
        assertThat(response.getBody().sourcePublicationId()).isEqualTo(stored.publicationId());
        assertThat(response.getBody().sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().sourceDraftRevision()).isEqualTo(stored.draftRevision());
        assertThat(response.getBody().sourceArtifactKind()).isEqualTo(stored.artifactKind());
        assertThat(response.getBody().publication()).isEqualTo(stored);
        assertThat(response.getBody().validation()).isEqualTo(stored.validation());
        assertThat(response.getBody().dependencyReport()).isEqualTo(stored.dependencyReport());

        VisualGraphPublicationExportBundle sameMaterialDifferentExportTime =
                new VisualGraphPublicationExportBundle(
                        response.getBody().schemaVersion(),
                        Instant.EPOCH,
                        response.getBody().sourcePublicationId(),
                        response.getBody().sourceDraftId(),
                        response.getBody().sourceDraftRevision(),
                        response.getBody().sourceArtifactKind(),
                        response.getBody().publication(),
                        response.getBody().validation(),
                        response.getBody().dependencyReport());
        assertThat(sameMaterialDifferentExportTime.bundleFingerprint())
                .isEqualTo(response.getBody().bundleFingerprint());
    }

    @Test
    void importBundleStoresImmutablePublicationSnapshot() {
        InMemoryVisualGraphPublicationRepository sourceRepository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication source = sourceRepository.create(publication("tenant-a", "risk", "prod"));
        VisualGraphPublicationExportBundle bundle = VisualGraphPublicationExportBundle.from(source);
        InMemoryVisualGraphPublicationRepository targetRepository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublicationController controller = new VisualGraphPublicationController(targetRepository, runner(),
                new InMemoryVisualGraphRunRepository(), VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().schemaVersion())
                .isEqualTo(VisualGraphPublicationImportResult.SCHEMA_VERSION);
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().sourceBundleSchemaVersion())
                .isEqualTo(VisualGraphPublicationExportBundle.SCHEMA_VERSION);
        assertThat(response.getBody().sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(response.getBody().sourcePublicationId()).isEqualTo(source.publicationId());
        assertThat(response.getBody().sourceArtifactKind()).isEqualTo(source.artifactKind());
        assertThat(response.getBody().importedPublicationId()).isEqualTo(source.publicationId());
        assertThat(response.getBody().sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(response.getBody().targetDependencyReport().draftId()).isEqualTo(source.draftId());
        assertThat(response.getBody().targetDependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("RUNTIME_EXECUTABLE", 1);
        assertThat(response.getBody().targetDependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.fingerprintState()).isEqualTo("current");
                });
        assertThat(response.getBody().diagnostics()).isEmpty();
        assertThat(targetRepository.find(source.publicationId())).contains(response.getBody().publication());
    }

    @Test
    void importBundleReturnsTargetDependencyReportForMissingCatalogOperator() {
        InMemoryVisualGraphPublicationRepository sourceRepository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication source = sourceRepository.create(publication("tenant-a", "risk", "prod"));
        VisualGraphPublicationExportBundle bundle = VisualGraphPublicationExportBundle.from(source);
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository(),
                emptyCatalog());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().sourceDependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().targetDependencyReport().missingOperatorCount()).isEqualTo(1);
        assertThat(response.getBody().targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("CATALOG_MISSING", 1);
        assertThat(response.getBody().targetDependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.fingerprintState()).isEqualTo("catalog-missing");
                    assertThat(operator.scopeAllowed()).isFalse();
                });
    }

    @Test
    void importBundleExposesRuntimeBindingHandoffForDesignPublication() {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        InMemoryVisualGraphPublicationRepository sourceRepository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication source = sourceRepository.create(designPublication("tenant-a", "risk", "prod"));
        VisualGraphPublicationExportBundle bundle = VisualGraphPublicationExportBundle.from(source);
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository(),
                VisualCatalogTestSupport.catalogWithLibrary(library));

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().publication().artifactKind()).isEqualTo("DESIGN");
        assertThat(response.getBody().targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("DESIGN_ONLY", 1);
        assertThat(response.getBody().targetRuntimeBindingRequirements())
                .isEqualTo(response.getBody().publication().validation().readiness().runtimeBindingRequirements());
        assertThat(response.getBody().targetRuntimeBindingRequirements())
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(requirement.bindingKind()).isEqualTo("executable-lowering");
                    assertThat(requirement.handoffLane()).isEqualTo("operator-platform");
                });
        assertThat(response.getBody().targetRuntimeBindingRequirementKeys())
                .containsExactly("RUNTIME_BINDING|publication|%s|eligibility|executable-lowering|risk:eligibility|DESIGN"
                        .formatted(response.getBody().publication().publicationId()));
    }

    @Test
    void importBundleRejectsUnsupportedBundleSchemaVersion() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationExportBundle unsupported = new VisualGraphPublicationExportBundle(
                "bloge.visualGraphPublicationExport.v2",
                null,
                stored.publicationId(),
                stored.draftId(),
                stored.draftRevision(),
                stored.artifactKind(),
                stored,
                stored.validation(),
                stored.dependencyReport()
        );
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(unsupported);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isFalse();
        assertThat(response.getBody().sourceBundleFingerprint()).isEqualTo(unsupported.bundleFingerprint());
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code())
                            .isEqualTo("visual.publication.bundle.schemaVersionUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void importBundleRejectsMismatchedBundleFingerprint() {
        VisualGraphPublication source = publication("tenant-a", "risk", "prod");
        VisualGraphPublicationExportBundle base = VisualGraphPublicationExportBundle.from(source);
        VisualGraphPublicationExportBundle forged = new VisualGraphPublicationExportBundle(
                base.schemaVersion(),
                base.exportedAt(),
                "sha256:forged",
                base.sourcePublicationId(),
                base.sourceDraftId(),
                base.sourceDraftRevision(),
                base.sourceArtifactKind(),
                base.publication(),
                base.validation(),
                base.dependencyReport());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(forged);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(forged.bundleFingerprintVerified()).isFalse();
        assertThat(forged.computedBundleFingerprint()).isEqualTo(base.bundleFingerprint());
        assertThat(response.getBody().imported()).isFalse();
        assertThat(response.getBody().sourceBundleFingerprint()).isEqualTo("sha256:forged");
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.bundle.fingerprintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/bundleFingerprint");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "sha256:forged")
                            .containsEntry("expected", base.bundleFingerprint());
                });
    }

    @Test
    void importBundleRejectsMissingPublicationSnapshot() {
        VisualGraphPublicationExportBundle missingSnapshot = new VisualGraphPublicationExportBundle(
                VisualGraphPublicationExportBundle.SCHEMA_VERSION,
                null,
                "source-publication",
                "draft-1",
                1,
                "DESIGN",
                null,
                null,
                null
        );
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(missingSnapshot);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isFalse();
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.bundle.snapshotMissing");
                    assertThat(diagnostic.target()).isEqualTo("/publication");
                });
    }

    @Test
    void importBundleRejectsUnsupportedPublicationSchemaVersion() {
        VisualGraphPublication base = publication().withIdentity("publication-v2", java.time.Instant.now());
        VisualGraphPublication unsupportedPublication = new VisualGraphPublication(
                "bloge.visualGraphPublication.v2",
                base.publicationId(),
                base.draftId(),
                base.draftRevision(),
                base.graphName(),
                base.tenantId(),
                base.namespace(),
                base.environment(),
                base.createdAt(),
                base.artifactKind(),
                base.draft(),
                base.operatorSnapshots(),
                base.operatorFingerprints(),
                base.visualLayout(),
                base.dsl(),
                base.validation(),
                base.generation(),
                base.dependencyReport(),
                base.publicationMetadata()
        );
        VisualGraphPublicationExportBundle bundle = VisualGraphPublicationExportBundle.from(unsupportedPublication);
        VisualGraphPublicationController controller = new VisualGraphPublicationController(
                new InMemoryVisualGraphPublicationRepository(), runner(), new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.schemaVersionUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/publication/schemaVersion");
                });
    }

    @Test
    void importBundleRejectsExistingPublicationId() {
        InMemoryVisualGraphPublicationRepository repository = new InMemoryVisualGraphPublicationRepository();
        VisualGraphPublication stored = repository.create(publication());
        VisualGraphPublicationController controller = new VisualGraphPublicationController(repository, runner(),
                new InMemoryVisualGraphRunRepository());

        ResponseEntity<VisualGraphPublicationImportResult> response = controller.importBundle(
                VisualGraphPublicationExportBundle.from(stored));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isFalse();
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.importConflict");
                    assertThat(diagnostic.target()).isEqualTo("/publication/publicationId");
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
        GraphDraft draft = publicationDraft(tenantId, namespace, environment, operator);
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

    private static VisualGraphPublication designPublication(String tenantId, String namespace, String environment) {
        OperatorLibrary library = VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer");
        OperatorDefinition operator = library.operators().get(0);
        GraphDraft draft = publicationDraft(tenantId, namespace, environment, operator);
        return VisualGraphPublication.design(
                draft,
                List.of(operator),
                new VisualValidationResult(true, List.of(), VisualGraphReadiness.from(
                        draft,
                        Map.of("eligibility", operator),
                        List.of()
                )),
                new DslGenerationResult(false, "", List.of()),
                GraphDraftDependencyReport.from(draft, VisualCatalogTestSupport.catalogWithLibrary(library))
        );
    }

    private static GraphDraft publicationDraft(String tenantId,
                                               String namespace,
                                               String environment,
                                               OperatorDefinition operator) {
        return new GraphDraft(
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
    }

    private static com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog emptyCatalog() {
        return VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty-library",
                "Empty library",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of()
        ));
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
