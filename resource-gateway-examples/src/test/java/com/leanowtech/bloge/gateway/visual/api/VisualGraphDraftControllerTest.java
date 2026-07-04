package com.leanowtech.bloge.gateway.visual.api;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.codegen.GraphDraftDslGenerator;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDiff;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftExportBundle;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftHistorySummary;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftImportResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftOperatorFingerprintRebaseRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftPatchService;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRevisionRestoreRequest;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftSummary;
import com.leanowtech.bloge.gateway.example.DynamicGatewayComposerService;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationResult;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublishRequest;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for visual graph draft APIs.
 */
class VisualGraphDraftControllerTest {

    @Test
    void compileBlocksInvalidDraftBeforeDslGeneration() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, null);
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.score").contains("string").contains("integer");
                });
    }

    @Test
    void validateRejectsUnsupportedDraftSchemaVersion() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = withSchemaVersion(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "bloge.visualGraphDraft.v2");

        var result = controller.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "bloge.visualGraphDraft.v2")
                            .containsEntry("expected", GraphDraft.SCHEMA_VERSION);
                });
    }

    @Test
    void validateReturnsGraphRuntimeReadiness() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, null);
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        VisualValidationResult result = controller.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.readiness().schemaVersion()).isEqualTo("bloge.visualGraphReadiness.v1");
        assertThat(result.readiness().state()).isEqualTo("runtime-executable");
        assertThat(result.readiness().executable()).isTrue();
        assertThat(result.readiness().artifactKinds()).containsExactly("EXECUTABLE", "DESIGN");
        assertThat(result.readiness().runtimeExecutableNodeCount()).isEqualTo(1);
        assertThat(result.readiness().nodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.nodeId()).isEqualTo("eligibility");
                    assertThat(node.state()).isEqualTo("runtime-executable");
                    assertThat(node.executable()).isTrue();
                });
        assertThat(result.actionReadiness().schemaVersion())
                .isEqualTo(VisualGraphActionReadiness.SCHEMA_VERSION);
        assertThat(result.actionReadiness().state()).isEqualTo("runtime-executable-ready");
        assertThat(result.actionReadiness().compileNow()).isTrue();
        assertThat(result.actionReadiness().runNow()).isTrue();
        assertThat(result.actionReadiness().publishExecutableNow()).isTrue();
        assertThat(result.actionReadiness().publishDesignNow()).isTrue();
        assertThat(result.actionReadiness().requiresAckWarnings()).isFalse();
    }

    @Test
    void compileGeneratesDslAfterVisualValidationPasses() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, null);
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.dsl()).contains("transform eligibility");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("runtime-executable");
        assertThat(result.validation().readiness().artifactKinds()).containsExactly("EXECUTABLE", "DESIGN");
    }

    @Test
    void compileRejectsDraftWithoutOperatorFingerprints() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.fingerprintMissing");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/operatorRef");
                });
    }

    @Test
    void compileRejectsGeneratedDslWhenRuntimeOperatorIsMissing() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary());
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = withFingerprints(nativePolicyDraft(), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).contains("node policy : riskMissingRuntime");
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("runtime-executable");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("bloge.dsl");
                    assertThat(diagnostic.message()).contains("riskMissingRuntime");
                });
    }

    @Test
    void compileBlocksDesignOnlyDraftAfterSchemaValidationPasses() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = withFingerprints(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), catalog);

        DslGenerationResult result = controller.compile(draft);

        assertThat(result.generated()).isFalse();
        assertThat(result.dsl()).isBlank();
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("design-only");
        assertThat(result.validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(result.validation().actionReadiness().state()).isEqualTo("design-artifact-ready");
        assertThat(result.validation().actionReadiness().compileNow()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code", "target")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "visual.action.compileBlocked",
                        "/actionReadiness"));
    }

    @Test
    void dependenciesSummarizeStoredDraftLineageAndRuntimeReadiness() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(twoNodeEligibilityDraft());

        ResponseEntity<GraphDraftDependencyReport> response = controller.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.schemaVersion()).isEqualTo(GraphDraftDependencyReport.SCHEMA_VERSION);
        assertThat(report.draftId()).isEqualTo(stored.draftId());
        assertThat(report.revision()).isEqualTo(stored.revision());
        assertThat(report.nodeCount()).isEqualTo(2);
        assertThat(report.edgeCount()).isEqualTo(1);
        assertThat(report.operatorDependencyCount()).isEqualTo(1);
        assertThat(report.missingOperatorCount()).isZero();
        assertThat(report.scopeMismatchOperatorCount()).isZero();
        assertThat(report.driftedFingerprintCount()).isZero();
        assertThat(report.missingFingerprintCount()).isZero();
        assertThat(report.schemaBreakingDriftCount()).isZero();
        assertThat(report.schemaCompatibleDriftCount()).isZero();
        assertThat(report.schemaCompatibilityStateCounts()).containsEntry("current", 2);
        assertThat(report.sourceKindCounts()).containsEntry("user-library", 2);
        assertThat(report.operatorLibraryIdCounts()).containsEntry("risk-policy", 2);
        assertThat(report.loweringModeCounts()).containsEntry("transform", 2);
        assertThat(report.runtimeReadinessStateCounts()).containsEntry("RUNTIME_EXECUTABLE", 2);
        assertThat(report.operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(operator.executable()).isTrue();
                    assertThat(operator.scopeAllowed()).isTrue();
                    assertThat(operator.policyViolations()).isEmpty();
                    assertThat(operator.artifactKinds()).containsExactly("EXECUTABLE");
                    assertThat(operator.fingerprintState()).isEqualTo("current");
                    assertThat(operator.schemaCompatibilityState()).isEqualTo("current");
                    assertThat(operator.schemaCompatibilityIssues()).isEmpty();
                    assertThat(operator.nodeIds()).containsExactly("eligibility", "audit");
                });
        assertThat(report.nodes())
                .filteredOn(node -> node.nodeId().equals("audit"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(node.bindingSourceNodes()).containsExactly("eligibility");
                    assertThat(node.edgeSourceNodes()).containsExactly("eligibility");
                    assertThat(node.upstreamNodes()).containsExactly("eligibility");
                    assertThat(node.bindingTargetNodes()).isEmpty();
                    assertThat(node.edgeTargetNodes()).isEmpty();
                    assertThat(node.downstreamNodes()).isEmpty();
                    assertThat(node.fingerprintState()).isEqualTo("current");
                    assertThat(node.schemaCompatibilityState()).isEqualTo("current");
                    assertThat(node.schemaCompatibilityIssues()).isEmpty();
                    assertThat(node.scopeAllowed()).isTrue();
                    assertThat(node.policyViolations()).isEmpty();
                });
        assertThat(report.nodes())
                .filteredOn(node -> node.nodeId().equals("eligibility"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.bindingTargetNodes()).containsExactly("audit");
                    assertThat(node.edgeTargetNodes()).containsExactly("audit");
                    assertThat(node.downstreamNodes()).containsExactly("audit");
                });
    }

    @Test
    void dependenciesReportBindingOnlyDownstreamLineage() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(twoNodeBindingOnlyEligibilityDraft());

        ResponseEntity<GraphDraftDependencyReport> response = controller.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.edgeCount()).isZero();
        assertThat(report.nodes())
                .filteredOn(node -> node.nodeId().equals("audit"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.bindingSourceNodes()).containsExactly("eligibility");
                    assertThat(node.edgeSourceNodes()).isEmpty();
                    assertThat(node.upstreamNodes()).containsExactly("eligibility");
                    assertThat(node.downstreamNodes()).isEmpty();
                });
        assertThat(report.nodes())
                .filteredOn(node -> node.nodeId().equals("eligibility"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.bindingTargetNodes()).containsExactly("audit");
                    assertThat(node.edgeTargetNodes()).isEmpty();
                    assertThat(node.downstreamNodes()).containsExactly("audit");
                });
    }

    @Test
    void dependenciesClassifyCompatibleOperatorSchemaDrift() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog currentCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft stored = controllerWithCatalog(initialCatalog, drafts).create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        VisualGraphDraftController controllerWithCurrentCatalog = controllerWithCatalog(currentCatalog, drafts);

        ResponseEntity<GraphDraftDependencyReport> response =
                controllerWithCurrentCatalog.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.driftedFingerprintCount()).isEqualTo(1);
        assertThat(report.schemaBreakingDriftCount()).isZero();
        assertThat(report.schemaCompatibleDriftCount()).isEqualTo(1);
        assertThat(report.schemaCompatibilityStateCounts()).containsEntry("compatible", 1);
        assertThat(report.schemaRebaseDecisionStateCounts()).containsEntry("ready-rebase", 1);
        assertThat(report.schemaRebaseDecisions())
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.nodeId()).isEqualTo("eligibility");
                    assertThat(decision.queueState()).isEqualTo("ready-rebase");
                    assertThat(decision.rebaseEligible()).isTrue();
                    assertThat(decision.recommendedAction()).contains("review drift evidence");
                    assertThat(decision.issueCount()).isEqualTo(1);
                    assertThat(decision.compatibleIssueCount()).isEqualTo(1);
                    assertThat(decision.affectedPaths()).containsExactly("input.inputs.score");
                    assertThat(decision.reviewSummary()).contains("1 schema issue").contains("score");
                });
        assertThat(report.operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.fingerprintState()).isEqualTo("drifted");
                    assertThat(operator.schemaCompatibilityState()).isEqualTo("compatible");
                    assertThat(operator.schemaCompatibilityIssues())
                            .singleElement()
                            .satisfies(issue -> {
                                assertThat(issue.nodeId()).isEqualTo("eligibility");
                                assertThat(issue.surface()).isEqualTo("input");
                                assertThat(issue.portName()).isEqualTo("inputs");
                                assertThat(issue.compatibility()).isEqualTo("compatible");
                                assertThat(issue.path()).isEqualTo("score");
                                assertThat(issue.savedType()).isEqualTo("integer");
                                assertThat(issue.currentType()).isEqualTo("number");
                                assertThat(issue.reviewHint()).contains("Review downstream expectations");
                                assertThat(issue.schemaChanges())
                                        .singleElement()
                                        .satisfies(change -> {
                                            assertThat(change.path()).isEqualTo("score");
                                            assertThat(change.keyword()).isEqualTo("type");
                                            assertThat(change.savedValue()).isEqualTo("integer");
                                            assertThat(change.currentValue()).isEqualTo("number");
                                            assertThat(change.compatibility()).isEqualTo("compatible");
                                            assertThat(change.summary()).isEqualTo("type: integer -> number");
                                        });
                                assertThat(issue.schemaPreview().path()).isEqualTo("score");
                                assertThat(issue.schemaPreview().savedSchema()).containsEntry("type", "integer");
                                assertThat(issue.schemaPreview().currentSchema()).containsEntry("type", "number");
                                assertThat(issue.schemaPreview().truncated()).isFalse();
                            });
                });
        assertThat(report.nodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.fingerprintState()).isEqualTo("drifted");
                    assertThat(node.schemaCompatibilityState()).isEqualTo("compatible");
                    assertThat(node.schemaCompatibilityIssues())
                            .singleElement()
                            .satisfies(issue -> {
                                assertThat(issue.surface()).isEqualTo("input");
                                assertThat(issue.portName()).isEqualTo("inputs");
                                assertThat(issue.path()).isEqualTo("score");
                                assertThat(issue.savedType()).isEqualTo("integer");
                                assertThat(issue.currentType()).isEqualTo("number");
                                assertThat(issue.reviewHint()).contains("Review downstream expectations");
                                assertThat(issue.schemaChanges())
                                        .singleElement()
                                        .satisfies(change -> {
                                            assertThat(change.keyword()).isEqualTo("type");
                                            assertThat(change.savedValue()).isEqualTo("integer");
                                            assertThat(change.currentValue()).isEqualTo("number");
                                        });
                                assertThat(issue.schemaPreview().path()).isEqualTo("score");
                                assertThat(issue.schemaPreview().savedSchema()).containsEntry("type", "integer");
                                assertThat(issue.schemaPreview().currentSchema()).containsEntry("type", "number");
                                assertThat(issue.message()).contains("can still feed current input schema");
                            });
                });
    }

    @Test
    void dependenciesClassifyBreakingOperatorSchemaDrift() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        DefaultVisualOperatorCatalog currentCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft stored = controllerWithCatalog(initialCatalog, drafts).create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        VisualGraphDraftController controllerWithCurrentCatalog = controllerWithCatalog(currentCatalog, drafts);

        ResponseEntity<GraphDraftDependencyReport> response =
                controllerWithCurrentCatalog.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.driftedFingerprintCount()).isEqualTo(1);
        assertThat(report.schemaBreakingDriftCount()).isEqualTo(1);
        assertThat(report.schemaCompatibleDriftCount()).isZero();
        assertThat(report.schemaCompatibilityStateCounts()).containsEntry("breaking", 1);
        assertThat(report.schemaRebaseDecisionStateCounts()).containsEntry("repair-review", 1);
        assertThat(report.schemaRebaseDecisions())
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.nodeId()).isEqualTo("eligibility");
                    assertThat(decision.queueState()).isEqualTo("repair-review");
                    assertThat(decision.rebaseEligible()).isTrue();
                    assertThat(decision.recommendedAction()).contains("repair bindings");
                    assertThat(decision.issueCount()).isEqualTo(1);
                    assertThat(decision.breakingIssueCount()).isEqualTo(1);
                    assertThat(decision.affectedSurfaces()).containsExactly("input.inputs");
                    assertThat(decision.affectedPaths()).containsExactly("input.inputs.score");
                    assertThat(decision.reviewSummary()).contains("target type integer requires integer-valued source");
                });
        assertThat(report.operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.fingerprintState()).isEqualTo("drifted");
                    assertThat(operator.schemaCompatibilityState()).isEqualTo("breaking");
                    assertThat(operator.schemaCompatibilityIssues())
                            .singleElement()
                            .satisfies(issue -> {
                                assertThat(issue.nodeId()).isEqualTo("eligibility");
                                assertThat(issue.surface()).isEqualTo("input");
                                assertThat(issue.portName()).isEqualTo("inputs");
                                assertThat(issue.compatibility()).isEqualTo("breaking");
                                assertThat(issue.path()).isEqualTo("score");
                                assertThat(issue.savedType()).isEqualTo("number");
                                assertThat(issue.currentType()).isEqualTo("integer");
                                assertThat(issue.reviewHint()).contains("Review bindings before rebase");
                                assertThat(issue.schemaChanges())
                                        .singleElement()
                                        .satisfies(change -> {
                                            assertThat(change.path()).isEqualTo("score");
                                            assertThat(change.keyword()).isEqualTo("type");
                                            assertThat(change.savedValue()).isEqualTo("number");
                                            assertThat(change.currentValue()).isEqualTo("integer");
                                            assertThat(change.compatibility()).isEqualTo("breaking");
                                            assertThat(change.summary()).isEqualTo("type: number -> integer");
                                        });
                                assertThat(issue.schemaPreview().path()).isEqualTo("score");
                                assertThat(issue.schemaPreview().savedSchema()).containsEntry("type", "number");
                                assertThat(issue.schemaPreview().currentSchema()).containsEntry("type", "integer");
                                assertThat(issue.schemaPreview().truncated()).isFalse();
                                assertThat(issue.message())
                                        .contains("target type integer requires integer-valued source")
                                        .contains("source type number has no integral multipleOf");
                            });
                });
        assertThat(report.nodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.fingerprintState()).isEqualTo("drifted");
                    assertThat(node.schemaCompatibilityState()).isEqualTo("breaking");
                    assertThat(node.schemaCompatibilityIssues())
                            .singleElement()
                            .satisfies(issue -> {
                                assertThat(issue.path()).isEqualTo("score");
                                assertThat(issue.savedType()).isEqualTo("number");
                                assertThat(issue.currentType()).isEqualTo("integer");
                                assertThat(issue.reviewHint()).contains("Review bindings before rebase");
                                assertThat(issue.schemaChanges())
                                        .singleElement()
                                        .satisfies(change -> {
                                            assertThat(change.keyword()).isEqualTo("type");
                                            assertThat(change.savedValue()).isEqualTo("number");
                                            assertThat(change.currentValue()).isEqualTo("integer");
                                        });
                                assertThat(issue.schemaPreview().path()).isEqualTo("score");
                                assertThat(issue.schemaPreview().savedSchema()).containsEntry("type", "number");
                                assertThat(issue.schemaPreview().currentSchema()).containsEntry("type", "integer");
                                assertThat(issue.message())
                                        .contains("target type integer requires integer-valued source")
                                        .contains("source type number has no integral multipleOf");
                            });
                });
    }

    @Test
    void dependenciesKeepSavedSnapshotContextWhenCurrentCatalogIsMissing() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft stored = controllerWithCatalog(eligibilityCatalog(), drafts).create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        VisualGraphDraftController controllerWithMissingCatalog = controllerWithCatalog(emptyCatalog(), drafts);

        ResponseEntity<GraphDraftDependencyReport> response =
                controllerWithMissingCatalog.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.missingOperatorCount()).isEqualTo(1);
        assertThat(report.scopeMismatchOperatorCount()).isZero();
        assertThat(report.sourceKindCounts()).containsEntry("user-library", 1);
        assertThat(report.operatorLibraryIdCounts()).containsEntry("risk-policy", 1);
        assertThat(report.runtimeReadinessStateCounts()).containsEntry("CATALOG_MISSING", 1);
        assertThat(report.operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(operator.currentFingerprint()).isBlank();
                    assertThat(operator.fingerprintState()).isEqualTo("catalog-missing");
                    assertThat(operator.scopeAllowed()).isFalse();
                    assertThat(operator.policyViolations()).isEmpty();
                    assertThat(operator.executable()).isFalse();
                    assertThat(operator.nodeIds()).containsExactly("eligibility");
                });
        assertThat(report.nodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(node.savedFingerprint()).startsWith("sha256:");
                    assertThat(node.currentFingerprint()).isBlank();
                    assertThat(node.fingerprintState()).isEqualTo("catalog-missing");
                    assertThat(node.runtimeReadinessState()).isEqualTo("CATALOG_MISSING");
                    assertThat(node.scopeAllowed()).isFalse();
                    assertThat(node.policyViolations()).isEmpty();
                });
    }

    @Test
    void dependenciesReportScopeMismatchWhenOperatorExistsOutsideDraftScope() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer",
                        new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("prod"))));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftDependencyReport> response = controller.dependencies(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDependencyReport report = response.getBody();
        assertThat(report.missingOperatorCount()).isZero();
        assertThat(report.scopeMismatchOperatorCount()).isEqualTo(1);
        assertThat(report.operatorLibraryIdCounts()).containsEntry("risk-policy", 1);
        assertThat(report.runtimeReadinessStateCounts()).containsEntry("SCOPE_MISMATCH", 1);
        assertThat(report.operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(operator.currentFingerprint()).startsWith("sha256:");
                    assertThat(operator.fingerprintState()).isEqualTo("scope-mismatch");
                    assertThat(operator.scopeAllowed()).isFalse();
                    assertThat(operator.policyViolations()).containsExactly("environment 'local' is not in [prod]");
                    assertThat(operator.executable()).isFalse();
                });
        assertThat(report.nodes())
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.operatorLibraryId()).isEqualTo("risk-policy");
                    assertThat(node.fingerprintState()).isEqualTo("scope-mismatch");
                    assertThat(node.runtimeReadinessState()).isEqualTo("SCOPE_MISMATCH");
                    assertThat(node.currentFingerprint()).startsWith("sha256:");
                    assertThat(node.scopeAllowed()).isFalse();
                    assertThat(node.policyViolations()).containsExactly("environment 'local' is not in [prod]");
                    assertThat(node.executable()).isFalse();
                });
    }

    @Test
    void createStoresCurrentOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        GraphDraft stored = controller.create(draft);

        assertThat(stored.operatorFingerprints())
                .containsEntry("eligibility", catalog.find("risk:eligibility").orElseThrow().fingerprint());
        assertThat(stored.operatorSnapshots())
                .containsKey("eligibility");
        assertThat(stored.operatorSnapshots().get("eligibility").operatorRef()).isEqualTo("risk:eligibility");
    }

    @Test
    void createRejectsUnsupportedDraftSchemaVersionBeforeStorage() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft draft = withSchemaVersion(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "bloge.visualGraphDraft.v2");

        assertThatThrownBy(() -> controller.create(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bloge.visualGraphDraft.v2");
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void createDraftReturnsPersistenceDiagnosticWhenRepositorySaveFails() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        FailingSaveGraphDraftRepository repository = new FailingSaveGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, repository);

        ResponseEntity<Object> response = controller.createDraft(eligibilityDraft(graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        )
                )),
                "architect@example.com",
                "browser-canvas",
                "Created schema-only draft.",
                "Persist a design-only graph before runtime implementation exists.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        VisualValidationResult result = (VisualValidationResult) response.getBody();
        assertThat(result.valid()).isFalse();
        assertThat(result.readiness().state()).isEqualTo("design-only");
        assertThat(result.readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.createPersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/draft");
                    assertThat(diagnostic.metadata())
                            .containsEntry("submittedDraftId", "")
                            .containsEntry("submittedRevision", 0L)
                            .containsEntry("graphName", "compileGate")
                            .containsEntry("exceptionType", "IllegalStateException")
                            .containsEntry("exceptionMessage", "draft store unavailable");
                });
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void createDoesNotSnapshotDeprecatedOperatorAsNewExecutableNode() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                deprecatedNumericPassLibrary());
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());

        GraphDraft stored = controller.create(numericPassDraft());
        DslGenerationResult result = controller.compile(stored);

        assertThat(stored.operatorFingerprints()).doesNotContainKey("pass");
        assertThat(result.generated()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMissing");
    }

    @Test
    void createStoresRevisionMetadataSnapshot() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();

        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        assertThat(stored.revisionMetadata().createdAt()).isNotBlank();
        assertThat(stored.revisionMetadata().updatedAt()).isNotBlank();
        assertThat(stored.revisionMetadata().createdBy()).isEqualTo("visual-canvas");
        assertThat(stored.revisionMetadata().updatedBy()).isEqualTo("visual-canvas");
        assertThat(stored.revisionMetadata().changeSource()).isEqualTo("api");
        assertThat(stored.revisionMetadata().changeSummary()).isEqualTo("Created draft.");
        assertThat(stored.revisionMetadata().changedPaths()).containsExactly("/");
        assertThat(stored.revisionMetadata().reason()).isEmpty();
    }

    @Test
    void createStoresProvidedRevisionMetadataSnapshot() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();

        GraphDraft stored = controller.create(
                eligibilityDraft(graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        )
                )),
                "architect@example.com",
                "browser-canvas",
                "Created onboarding policy draft.",
                "Initial schema-only business design session."
        );

        assertThat(stored.revisionMetadata().createdBy()).isEqualTo("architect@example.com");
        assertThat(stored.revisionMetadata().updatedBy()).isEqualTo("architect@example.com");
        assertThat(stored.revisionMetadata().changeSource()).isEqualTo("browser-canvas");
        assertThat(stored.revisionMetadata().changeSummary()).isEqualTo("Created onboarding policy draft.");
        assertThat(stored.revisionMetadata().changedPaths()).containsExactly("/");
        assertThat(stored.revisionMetadata().reason()).isEqualTo("Initial schema-only business design session.");
    }

    @Test
    void createIgnoresSubmittedIdentityAndDoesNotOverwriteExistingDraft() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft submittedWithExistingIdentity = renameDraft(first, "attemptedOverwrite");

        GraphDraft second = controller.create(submittedWithExistingIdentity);

        assertThat(second.draftId()).isNotBlank().isNotEqualTo(first.draftId());
        assertThat(second.revision()).isEqualTo(1);
        assertThat(repository.find(first.draftId()).orElseThrow().graphName()).isEqualTo(first.graphName());
        assertThat(repository.find(second.draftId()).orElseThrow().graphName()).isEqualTo("attemptedOverwrite");
    }

    @Test
    void exportDraftIncludesOperatorSnapshotsAndValidationDiagnostics() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftExportBundle> response = controller.exportDraft(stored.draftId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraftExportBundle bundle = response.getBody();
        assertThat(bundle).isNotNull();
        assertThat(bundle.schemaVersion()).isEqualTo(GraphDraftExportBundle.SCHEMA_VERSION);
        assertThat(bundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(bundle.bundleFingerprint()).hasSize(71);
        assertThat(bundle.bundleFingerprintVerified()).isTrue();
        assertThat(bundle.sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(bundle.sourceRevision()).isEqualTo(stored.revision());
        assertThat(bundle.draft()).isEqualTo(stored);
        assertThat(bundle.operatorSnapshots())
                .extracting(OperatorDefinition::operatorRef)
                .containsExactly("risk:eligibility");
        assertThat(bundle.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch");
        assertThat(bundle.validation().valid()).isFalse();
        assertThat(bundle.validation().diagnostics()).isEqualTo(bundle.diagnostics());
        assertThat(bundle.validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(bundle.dependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(bundle.dependencyReport().revision()).isEqualTo(stored.revision());
        assertThat(bundle.dependencyReport().missingOperatorCount()).isZero();
        assertThat(bundle.dependencyReport().scopeMismatchOperatorCount()).isZero();
        assertThat(bundle.dependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.fingerprintState()).isEqualTo("current");
                    assertThat(operator.scopeAllowed()).isTrue();
                });

        GraphDraftExportBundle sameMaterialDifferentExportTime = new GraphDraftExportBundle(
                bundle.schemaVersion(),
                Instant.EPOCH,
                bundle.sourceDraftId(),
                bundle.sourceRevision(),
                bundle.draft(),
                bundle.operatorSnapshots(),
                bundle.diagnostics(),
                bundle.validation(),
                bundle.dependencyReport());
        assertThat(sameMaterialDifferentExportTime.bundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
    }

    @Test
    void importDraftBundleCreatesNewDraftWithCurrentOperatorFingerprints() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository initialRepository = new InMemoryGraphDraftRepository();
        InMemoryGraphDraftRepository importedRepository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, initialRepository);
        VisualGraphDraftController importController = controllerWithCatalog(evolvedCatalog, importedRepository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = initialController.exportDraft(stored.draftId()).getBody();
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftImportResult> response = importController.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().schemaVersion()).isEqualTo(GraphDraftImportResult.SCHEMA_VERSION);
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().sourceBundleSchemaVersion()).isEqualTo(GraphDraftExportBundle.SCHEMA_VERSION);
        assertThat(response.getBody().sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(response.getBody().sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().sourceRevision()).isEqualTo(stored.revision());
        assertThat(response.getBody().diagnostics()).isEmpty();
        assertThat(response.getBody().validation().valid()).isTrue();
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("runtime-executable");
        assertThat(response.getBody().validation().readiness().artifactKinds())
                .containsExactly("EXECUTABLE", "DESIGN");
        assertThat(response.getBody().sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(response.getBody().sourceDependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().sourceDependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().dependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().dependencyReport().scopeMismatchOperatorCount()).isZero();
        assertThat(response.getBody().dependencyReport().runtimeReadinessStateCounts())
                .containsEntry("RUNTIME_EXECUTABLE", 1);
        GraphDraft imported = response.getBody().draft();
        assertThat(response.getBody().targetDependencyReport()).isEqualTo(response.getBody().dependencyReport());
        assertThat(response.getBody().targetDependencyReport().draftId()).isEqualTo(imported.draftId());
        assertThat(imported.draftId()).isNotBlank().isNotEqualTo(stored.draftId());
        assertThat(imported.revision()).isEqualTo(1);
        assertThat(imported.graphName()).isEqualTo(stored.graphName());
        assertThat(imported.operatorFingerprints())
                .containsEntry("eligibility", evolvedFingerprint)
                .doesNotContainEntry("eligibility", initialFingerprint);
        assertThat(imported.operatorSnapshots().get("eligibility").fingerprint()).isEqualTo(evolvedFingerprint);
        assertThat(imported.revisionMetadata().changeSource()).isEqualTo("import");
        assertThat(imported.revisionMetadata().changeSummary()).isEqualTo("Imported draft from export bundle.");
        assertThat(importedRepository.all()).containsExactly(imported);
    }

    @Test
    void validateDraftBundlePreviewsTargetEnvironmentWithoutStorage() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        VisualGraphDraftController sourceController =
                controllerWithCatalog(initialCatalog, new InMemoryGraphDraftRepository());
        InMemoryGraphDraftRepository targetRepository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController targetController = controllerWithCatalog(evolvedCatalog, targetRepository);
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftImportResult> response = targetController.validateDraftBundle(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftImportResult result = response.getBody();
        assertThat(result.schemaVersion()).isEqualTo(GraphDraftImportResult.SCHEMA_VERSION);
        assertThat(result.imported()).isFalse();
        assertThat(result.sourceBundleSchemaVersion()).isEqualTo(GraphDraftExportBundle.SCHEMA_VERSION);
        assertThat(result.sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(result.sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(result.sourceRevision()).isEqualTo(stored.revision());
        assertThat(result.sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(result.draft().draftId()).isEqualTo(stored.draftId());
        assertThat(result.draft().revision()).isEqualTo(stored.revision());
        assertThat(result.draft().operatorFingerprints()).containsEntry("eligibility", evolvedFingerprint);
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("runtime-executable");
        assertThat(result.targetDependencyReport()).isEqualTo(result.dependencyReport());
        assertThat(result.targetDependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(result.targetDependencyReport().operatorDependencyCount()).isEqualTo(1);
        assertThat(result.targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("RUNTIME_EXECUTABLE", 1);
        assertThat(targetRepository.all()).isEmpty();
    }

    @Test
    void importDraftBundleStoresCallerProvidedRevisionMetadata() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController sourceController =
                controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        VisualGraphDraftController importController =
                controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();

        ResponseEntity<GraphDraftImportResult> response = importController.importDraft(
                bundle,
                "migration-bot",
                "portfolio-migration",
                "Imported draft from staging export.",
                "Preserve a reviewed design-only graph across environments.");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        GraphDraft imported = response.getBody().draft();
        assertThat(imported.revisionMetadata().createdBy()).isEqualTo("migration-bot");
        assertThat(imported.revisionMetadata().updatedBy()).isEqualTo("migration-bot");
        assertThat(imported.revisionMetadata().changeSource()).isEqualTo("portfolio-migration");
        assertThat(imported.revisionMetadata().changeSummary()).isEqualTo("Imported draft from staging export.");
        assertThat(imported.revisionMetadata().changedPaths()).containsExactly("/");
        assertThat(imported.revisionMetadata().reason())
                .isEqualTo("Preserve a reviewed design-only graph across environments.");
        assertThat(importController.history())
                .filteredOn(summary -> summary.draftId().equals(imported.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.changeSummary()).isEqualTo("Imported draft from staging export.");
                    assertThat(summary.reason())
                            .isEqualTo("Preserve a reviewed design-only graph across environments.");
                });
    }

    @Test
    void importDraftBundleReturnsTargetEnvironmentDiagnosticsForMissingOperators() {
        DefaultVisualOperatorCatalog sourceCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog targetCatalog = emptyCatalog();
        InMemoryGraphDraftRepository targetRepository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController sourceController =
                controllerWithCatalog(sourceCatalog, new InMemoryGraphDraftRepository());
        VisualGraphDraftController targetController = controllerWithCatalog(targetCatalog, targetRepository);
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();

        ResponseEntity<GraphDraftImportResult> response = targetController.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().sourceRevision()).isEqualTo(stored.revision());
        assertThat(response.getBody().draft().draftId()).isNotBlank();
        assertThat(targetRepository.all()).containsExactly(response.getBody().draft());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.operator.unknown");
        assertThat(response.getBody().validation().valid()).isFalse();
        assertThat(response.getBody().validation().diagnostics()).isEqualTo(response.getBody().diagnostics());
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(response.getBody().sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(response.getBody().sourceDependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(response.getBody().sourceDependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().targetDependencyReport()).isEqualTo(response.getBody().dependencyReport());
        assertThat(response.getBody().dependencyReport().draftId()).isEqualTo(response.getBody().draft().draftId());
        assertThat(response.getBody().dependencyReport().missingOperatorCount()).isEqualTo(1);
        assertThat(response.getBody().dependencyReport().scopeMismatchOperatorCount()).isZero();
        assertThat(response.getBody().dependencyReport().runtimeReadinessStateCounts())
                .containsEntry("CATALOG_MISSING", 1);
        assertThat(response.getBody().dependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.fingerprintState()).isEqualTo("catalog-missing");
                    assertThat(operator.scopeAllowed()).isFalse();
                    assertThat(operator.policyViolations()).isEmpty();
                });
    }

    @Test
    void importDraftBundleReturnsDependencyReportForTargetScopeMismatch() {
        DefaultVisualOperatorCatalog sourceCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog targetCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer",
                        new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("prod"))));
        InMemoryGraphDraftRepository targetRepository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController sourceController =
                controllerWithCatalog(sourceCatalog, new InMemoryGraphDraftRepository());
        VisualGraphDraftController targetController = controllerWithCatalog(targetCatalog, targetRepository);
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();

        ResponseEntity<GraphDraftImportResult> response = targetController.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isTrue();
        assertThat(targetRepository.all()).containsExactly(response.getBody().draft());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.operator.policyDenied");
        assertThat(response.getBody().validation().valid()).isFalse();
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(response.getBody().sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(response.getBody().sourceDependencyReport().scopeMismatchOperatorCount()).isZero();
        assertThat(response.getBody().targetDependencyReport()).isEqualTo(response.getBody().dependencyReport());
        assertThat(response.getBody().dependencyReport().draftId()).isEqualTo(response.getBody().draft().draftId());
        assertThat(response.getBody().dependencyReport().missingOperatorCount()).isZero();
        assertThat(response.getBody().dependencyReport().scopeMismatchOperatorCount()).isEqualTo(1);
        assertThat(response.getBody().dependencyReport().runtimeReadinessStateCounts())
                .containsEntry("SCOPE_MISMATCH", 1);
        assertThat(response.getBody().dependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.fingerprintState()).isEqualTo("scope-mismatch");
                    assertThat(operator.scopeAllowed()).isFalse();
                    assertThat(operator.policyViolations()).containsExactly("environment 'local' is not in [prod]");
                    assertThat(operator.executable()).isFalse();
                });
    }

    @Test
    void exportAndImportDraftBundleCarryDesignOnlyReadiness() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        InMemoryGraphDraftRepository sourceRepository = new InMemoryGraphDraftRepository();
        InMemoryGraphDraftRepository targetRepository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController sourceController = controllerWithCatalog(catalog, sourceRepository);
        VisualGraphDraftController targetController = controllerWithCatalog(catalog, targetRepository);
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();
        ResponseEntity<GraphDraftImportResult> response = targetController.importDraft(bundle);

        assertThat(bundle).isNotNull();
        assertThat(bundle.validation().valid()).isTrue();
        assertThat(bundle.validation().readiness().state()).isEqualTo("design-only");
        assertThat(bundle.validation().readiness().executable()).isFalse();
        assertThat(bundle.validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(bundle.validation().actionReadiness().state()).isEqualTo("design-artifact-ready");
        assertThat(bundle.validation().actionReadiness().compileNow()).isFalse();
        assertThat(bundle.validation().actionReadiness().runNow()).isFalse();
        assertThat(bundle.validation().actionReadiness().publishDesignNow()).isTrue();
        assertThat(bundle.validation().actionReadiness().publishExecutableNow()).isFalse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().imported()).isTrue();
        assertThat(response.getBody().validation().valid()).isTrue();
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("design-only");
        assertThat(response.getBody().validation().readiness().executable()).isFalse();
        assertThat(response.getBody().validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(response.getBody().targetRuntimeBindingRequirements())
                .isEqualTo(response.getBody().validation().readiness().runtimeBindingRequirements());
        assertThat(response.getBody().targetRuntimeBindingRequirements())
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(requirement.bindingKind()).isEqualTo("executable-lowering");
                    assertThat(requirement.handoffLane()).isEqualTo("operator-platform");
                });
        assertThat(response.getBody().targetRuntimeBindingRequirementKeys())
                .containsExactly("RUNTIME_BINDING|draft|%s|eligibility|executable-lowering|risk:eligibility|"
                        .formatted(response.getBody().draft().draftId()));
        assertThat(response.getBody().validation().actionReadiness().state()).isEqualTo("design-artifact-ready");
        assertThat(response.getBody().sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(response.getBody().targetDependencyReport()).isEqualTo(response.getBody().dependencyReport());
        assertThat(response.getBody().targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("DESIGN_ONLY", 1);
        assertThat(targetRepository.all()).containsExactly(response.getBody().draft());
    }

    @Test
    void importDraftBundleReturnsPersistenceDiagnosticWhenRepositorySaveFails() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphDraftController sourceController =
                controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        FailingSaveGraphDraftRepository targetRepository = new FailingSaveGraphDraftRepository();
        VisualGraphDraftController targetController = controllerWithCatalog(catalog, targetRepository);
        GraphDraft stored = sourceController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftExportBundle bundle = sourceController.exportDraft(stored.draftId()).getBody();

        ResponseEntity<GraphDraftImportResult> response = targetController.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        GraphDraftImportResult result = response.getBody();
        assertThat(result.imported()).isFalse();
        assertThat(result.sourceBundleSchemaVersion()).isEqualTo(GraphDraftExportBundle.SCHEMA_VERSION);
        assertThat(result.sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(result.sourceDraftId()).isEqualTo(stored.draftId());
        assertThat(result.sourceRevision()).isEqualTo(stored.revision());
        assertThat(result.draft().draftId()).isEqualTo(stored.draftId());
        assertThat(result.draft().revision()).isEqualTo(stored.revision());
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("design-only");
        assertThat(result.validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(result.sourceDependencyReport()).isEqualTo(bundle.dependencyReport());
        assertThat(result.targetDependencyReport()).isEqualTo(result.dependencyReport());
        assertThat(result.targetDependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(result.targetDependencyReport().runtimeReadinessStateCounts())
                .containsEntry("DESIGN_ONLY", 1);
        assertThat(result.targetRuntimeBindingRequirements())
                .isEqualTo(result.validation().readiness().runtimeBindingRequirements());
        assertThat(result.targetRuntimeBindingRequirementKeys())
                .containsExactly("RUNTIME_BINDING|draft|%s|eligibility|executable-lowering|risk:eligibility|"
                        .formatted(stored.draftId()));
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.importPersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/draft");
                    assertThat(diagnostic.metadata())
                            .containsEntry("sourceDraftId", stored.draftId())
                            .containsEntry("sourceRevision", stored.revision())
                            .containsEntry("sourceBundleFingerprint", bundle.bundleFingerprint())
                            .containsEntry("previewDraftId", stored.draftId())
                            .containsEntry("previewRevision", stored.revision())
                            .containsEntry("graphName", stored.graphName())
                            .containsEntry("exceptionType", "IllegalStateException")
                            .containsEntry("exceptionMessage", "draft store unavailable");
                });
        assertThat(targetRepository.all()).isEmpty();
    }

    @Test
    void importDraftBundleRejectsUnsupportedBundleContractBeforeStorage() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));
        GraphDraftExportBundle bundle = new GraphDraftExportBundle(
                "bloge.visualGraphDraftExport.v2",
                null,
                "source-draft",
                7,
                draft,
                List.of(),
                List.of()
        );

        ResponseEntity<GraphDraftImportResult> response = controller.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        GraphDraftImportResult result = response.getBody();
        assertThat(result.imported()).isFalse();
        assertThat(result.sourceBundleSchemaVersion()).isEqualTo("bloge.visualGraphDraftExport.v2");
        assertThat(result.sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(result.sourceDraftId()).isEqualTo("source-draft");
        assertThat(result.sourceRevision()).isEqualTo(7);
        assertThat(result.draft()).isNull();
        assertThat(result.sourceDependencyReport().operatorDependencyCount()).isZero();
        assertThat(result.targetDependencyReport().operatorDependencyCount()).isZero();
        assertThat(result.dependencyReport()).isEqualTo(result.targetDependencyReport());
        assertThat(result.targetRuntimeBindingRequirements()).isEmpty();
        assertThat(result.targetRuntimeBindingRequirementKeys()).isEmpty();
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draftExport.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "bloge.visualGraphDraftExport.v2")
                            .containsEntry("expected", GraphDraftExportBundle.SCHEMA_VERSION);
                });
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void importDraftBundleRejectsMismatchedBundleFingerprintBeforeStorage() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));
        GraphDraftExportBundle base = GraphDraftExportBundle.from(
                draft,
                List.of(),
                new VisualValidationResult(true, List.of()),
                GraphDraftDependencyReport.empty());
        GraphDraftExportBundle forged = new GraphDraftExportBundle(
                base.schemaVersion(),
                base.exportedAt(),
                "sha256:forged",
                base.sourceDraftId(),
                base.sourceRevision(),
                base.draft(),
                base.operatorSnapshots(),
                base.diagnostics(),
                base.validation(),
                base.dependencyReport());

        ResponseEntity<GraphDraftImportResult> response = controller.importDraft(forged);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        GraphDraftImportResult result = response.getBody();
        assertThat(forged.bundleFingerprintVerified()).isFalse();
        assertThat(forged.computedBundleFingerprint()).isEqualTo(base.bundleFingerprint());
        assertThat(result.imported()).isFalse();
        assertThat(result.sourceBundleFingerprint()).isEqualTo("sha256:forged");
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draftExport.fingerprintMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/bundleFingerprint");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "sha256:forged")
                            .containsEntry("expected", base.bundleFingerprint());
                });
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void importDraftBundleRejectsUnsupportedDraftContractBeforeStorage() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraftExportBundle bundle = new GraphDraftExportBundle(
                "",
                null,
                "source-draft",
                7,
                withSchemaVersion(eligibilityDraft(graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        )
                )), "bloge.visualGraphDraft.v2"),
                List.of(),
                List.of()
        );

        ResponseEntity<GraphDraftImportResult> response = controller.importDraft(bundle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        GraphDraftImportResult result = response.getBody();
        assertThat(result.imported()).isFalse();
        assertThat(result.sourceBundleSchemaVersion()).isEqualTo(GraphDraftExportBundle.SCHEMA_VERSION);
        assertThat(result.sourceBundleFingerprint()).isEqualTo(bundle.bundleFingerprint());
        assertThat(result.sourceDraftId()).isEqualTo("source-draft");
        assertThat(result.sourceRevision()).isEqualTo(7);
        assertThat(result.draft()).isNull();
        assertThat(result.sourceDependencyReport().operatorDependencyCount()).isZero();
        assertThat(result.targetDependencyReport().operatorDependencyCount()).isZero();
        assertThat(result.dependencyReport()).isEqualTo(result.targetDependencyReport());
        assertThat(result.targetRuntimeBindingRequirements()).isEmpty();
        assertThat(result.targetRuntimeBindingRequirementKeys()).isEmpty();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/draft/schemaVersion");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "bloge.visualGraphDraft.v2")
                            .containsEntry("expected", GraphDraft.SCHEMA_VERSION);
                });
        assertThat(repository.all()).isEmpty();
    }

    @Test
    void patchStoredDraftAppliesExpectedRevisionAndIncrementsRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "patchedPolicy")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isTrue();
        assertThat(response.getBody().draft().graphName()).isEqualTo("patchedPolicy");
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision() + 1);
    }

    @Test
    void patchRejectsUnsupportedDraftStatusBeforeSaving() {
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/status", "locked")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.status.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/status");
                });
        assertThat(repository.find(stored.draftId()).orElseThrow().status()).isEqualTo(GraphDraft.STATUS_DRAFT);
        assertThat(repository.find(stored.draftId()).orElseThrow().revision()).isEqualTo(stored.revision());
    }

    @Test
    void patchStoredDraftCapturesRevisionMetadata() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(
                        stored.revision(),
                        "alice@example.com",
                        "browser-save",
                        "Rename graph",
                        "Operator reviewed schema-constrained canvas changes before saving.",
                        List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "patchedPolicy"))
                ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.revisionMetadata().createdAt()).isEqualTo(stored.revisionMetadata().createdAt());
        assertThat(patched.revisionMetadata().createdBy()).isEqualTo(stored.revisionMetadata().createdBy());
        assertThat(patched.revisionMetadata().updatedAt()).isNotBlank();
        assertThat(patched.revisionMetadata().updatedBy()).isEqualTo("alice@example.com");
        assertThat(patched.revisionMetadata().changeSource()).isEqualTo("browser-save");
        assertThat(patched.revisionMetadata().changeSummary()).isEqualTo("Rename graph");
        assertThat(patched.revisionMetadata().changedPaths()).containsExactly("/graphName");
        assertThat(patched.revisionMetadata().reason())
                .isEqualTo("Operator reviewed schema-constrained canvas changes before saving.");
    }

    @Test
    void patchStoredDraftPreservesExistingOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftPatchResult> response = evolvedController.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "renamedPolicy")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
        assertThat(validator(evolvedCatalog).validate(patched).diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMismatch");
    }

    @Test
    void updatePreservesSubmittedOperatorFingerprintSnapshot() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<Object> response = evolvedController.update(
                stored.draftId(),
                renameDraft(stored, "renamedPolicy"),
                "architect",
                "test-suite",
                "Full-save draft rename.",
                "Verified full-save audit metadata propagation."
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(GraphDraft.class);
        GraphDraft updated = (GraphDraft) response.getBody();
        assertThat(updated.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
        assertThat(updated.revisionMetadata().updatedBy()).isEqualTo("architect");
        assertThat(updated.revisionMetadata().changeSource()).isEqualTo("test-suite");
        assertThat(updated.revisionMetadata().changeSummary()).isEqualTo("Full-save draft rename.");
        assertThat(updated.revisionMetadata().changedPaths()).containsExactly("/");
        assertThat(updated.revisionMetadata().reason())
                .isEqualTo("Verified full-save audit metadata propagation.");
        assertThat(validator(evolvedCatalog).validate(updated).diagnostics())
                .extracting("code")
                .contains("visual.operator.fingerprintMismatch");
    }

    @Test
    void updateKeepsExistingOperatorFingerprintSnapshotOverSubmittedRebase() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        GraphDraft rebased = renameDraft(stored.withOperatorFingerprints(Map.of(
                "eligibility", evolvedFingerprint
        )), "rebasedPolicy");

        ResponseEntity<Object> response = evolvedController.update(stored.draftId(), rebased);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft updated = (GraphDraft) response.getBody();
        assertThat(updated.operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint)
                .doesNotContainEntry("eligibility", evolvedFingerprint);
    }

    @Test
    void rebaseOperatorFingerprintsRefreshesStoredSnapshotWithRevisionGuard() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();
        String evolvedFingerprint = evolvedCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftPatchResult> response = evolvedController.rebaseOperatorFingerprints(
                stored.draftId(),
                new GraphDraftOperatorFingerprintRebaseRequest(
                        stored.revision(),
                        List.of("eligibility"),
                        "architect",
                        "test-suite",
                        "Refresh eligibility fingerprint after catalog drift review.",
                        "Architect approved the compatible eligibility schema drift rebase."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isTrue();
        GraphDraft rebased = response.getBody().draft();
        assertThat(rebased.revision()).isEqualTo(stored.revision() + 1);
        assertThat(rebased.operatorFingerprints())
                .containsEntry("eligibility", evolvedFingerprint)
                .doesNotContainEntry("eligibility", initialFingerprint);
        assertThat(rebased.operatorSnapshots().get("eligibility").fingerprint()).isEqualTo(evolvedFingerprint);
        assertThat(rebased.graphName()).isEqualTo(stored.graphName());
        assertThat(rebased.revisionMetadata().updatedBy()).isEqualTo("architect");
        assertThat(rebased.revisionMetadata().changeSource()).isEqualTo("test-suite");
        assertThat(rebased.revisionMetadata().changeSummary())
                .isEqualTo("Refresh eligibility fingerprint after catalog drift review.");
        assertThat(rebased.revisionMetadata().reason())
                .isEqualTo("Architect approved the compatible eligibility schema drift rebase.");
        assertThat(rebased.revisionMetadata().changedPaths())
                .containsExactly("/operatorFingerprints/eligibility", "/operatorSnapshots/eligibility");
        assertThat(validator(evolvedCatalog).validate(rebased).diagnostics())
                .extracting("code")
                .doesNotContain("visual.operator.fingerprintMismatch");
    }

    @Test
    void rebaseOperatorFingerprintsRejectsStaleRevisionAndKeepsSnapshot() {
        DefaultVisualOperatorCatalog initialCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        DefaultVisualOperatorCatalog evolvedCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("number"));
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController evolvedController = controllerWithCatalog(evolvedCatalog, repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = evolvedController.patch(stored.draftId(), new GraphDraftPatchRequest(
                stored.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "newerPolicy"))
        )).getBody();
        assertThat(patched).isNotNull();
        String initialFingerprint = initialCatalog.find("risk:eligibility").orElseThrow().fingerprint();

        ResponseEntity<GraphDraftPatchResult> response = evolvedController.rebaseOperatorFingerprints(
                stored.draftId(),
                new GraphDraftOperatorFingerprintRebaseRequest(stored.revision(), List.of("eligibility")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().draft().revision()).isEqualTo(patched.draft().revision());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.revisionConflict");
        assertThat(repository.find(stored.draftId()).orElseThrow().operatorFingerprints())
                .containsEntry("eligibility", initialFingerprint);
    }

    @Test
    void rebaseOperatorFingerprintsRejectsUnknownNode() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, repository);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.rebaseOperatorFingerprints(
                stored.draftId(),
                new GraphDraftOperatorFingerprintRebaseRequest(stored.revision(), List.of("missingNode")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operatorFingerprintRebase.nodeUnknown");
                    assertThat(diagnostic.target()).isEqualTo("/nodeIds/0");
                });
        assertThat(repository.find(stored.draftId()).orElseThrow().revision()).isEqualTo(stored.revision());
    }

    @Test
    void rebaseOperatorFingerprintsRejectsUnavailableOperator() {
        DefaultVisualOperatorCatalog initialCatalog = eligibilityCatalog();
        InMemoryGraphDraftRepository repository = new InMemoryGraphDraftRepository();
        VisualGraphDraftController initialController = controllerWithCatalog(initialCatalog, repository);
        VisualGraphDraftController emptyController = controllerWithCatalog(emptyCatalog(), repository);
        GraphDraft stored = initialController.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = emptyController.rebaseOperatorFingerprints(
                stored.draftId(),
                new GraphDraftOperatorFingerprintRebaseRequest(stored.revision(), List.of("eligibility")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operatorFingerprintRebase.operatorUnavailable");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/operatorRef");
                });
        assertThat(repository.find(stored.draftId()).orElseThrow().revision()).isEqualTo(stored.revision());
    }

    @Test
    void updateRejectsUnsupportedDraftSchemaVersionAndKeepsCurrentDraft() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft futureDraft = withSchemaVersion(renameDraft(stored, "futureContract"), "bloge.visualGraphDraft.v2");

        ResponseEntity<Object> response = controller.update(stored.draftId(), futureDraft);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(VisualValidationResult.class);
        var result = (VisualValidationResult) response.getBody();
        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                    assertThat(diagnostic.metadata())
                            .containsEntry("actual", "bloge.visualGraphDraft.v2")
                            .containsEntry("expected", GraphDraft.SCHEMA_VERSION);
                });
        assertThat(controller.get(stored.draftId()).getBody().schemaVersion()).isEqualTo(GraphDraft.SCHEMA_VERSION);
        assertThat(controller.get(stored.draftId()).getBody().graphName()).isEqualTo(stored.graphName());
    }

    @Test
    void updateRejectsStaleRevisionAndKeepsCurrentDraft() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<Object> freshResponse = controller.update(stored.draftId(), renameDraft(stored, "freshPolicy"));
        assertThat(freshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(freshResponse.getBody()).isInstanceOf(GraphDraft.class);
        GraphDraft fresh = (GraphDraft) freshResponse.getBody();

        ResponseEntity<Object> staleResponse = controller.update(stored.draftId(), renameDraft(stored, "stalePolicy"));

        assertThat(staleResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleResponse.getBody()).isInstanceOf(GraphDraftPatchResult.class);
        GraphDraftPatchResult result = (GraphDraftPatchResult) staleResponse.getBody();
        assertThat(result.patched()).isFalse();
        assertThat(result.draft().revision()).isEqualTo(fresh.revision());
        assertThat(result.draft().graphName()).isEqualTo("freshPolicy");
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.draft.revisionConflict");
        assertThat(controller.get(stored.draftId()).getBody().graphName()).isEqualTo("freshPolicy");
    }

    @Test
    void updateReturnsPersistenceDiagnosticWhenRepositorySaveIfRevisionFails() {
        FailingSaveIfRevisionGraphDraftRepository repository = new FailingSaveIfRevisionGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), repository);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<Object> response = controller.update(stored.draftId(), renameDraft(stored, "blockedUpdate"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(GraphDraftPatchResult.class);
        GraphDraftPatchResult result = (GraphDraftPatchResult) response.getBody();
        assertThat(result.patched()).isFalse();
        assertThat(result.draft()).isEqualTo(stored);
        assertThat(result.diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.updatePersistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/draft");
                    assertThat(diagnostic.metadata())
                            .containsEntry("draftId", stored.draftId())
                            .containsEntry("expectedRevision", stored.revision())
                            .containsEntry("currentRevision", stored.revision())
                            .containsEntry("currentGraphName", stored.graphName())
                            .containsEntry("attemptedGraphName", "blockedUpdate")
                            .containsEntry("exceptionType", "IllegalStateException")
                            .containsEntry("exceptionMessage", "draft revision store unavailable");
                });
        assertThat(controller.get(stored.draftId()).getBody().graphName()).isEqualTo(stored.graphName());
    }

    @Test
    void patchRejectsOperatorFingerprintMutation() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String fingerprint = stored.operatorFingerprints().get("eligibility");

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace",
                                "/operatorFingerprints/eligibility", "manual-rebase")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.patchPathForbidden");
        assertThat(controller.get(stored.draftId()).getBody().operatorFingerprints())
                .containsEntry("eligibility", fingerprint);
    }

    @Test
    void patchRejectsDraftSchemaVersionMutation() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace",
                                "/schemaVersion", "bloge.visualGraphDraft.v2")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.patchPathForbidden");
        assertThat(controller.get(stored.draftId()).getBody().schemaVersion()).isEqualTo(GraphDraft.SCHEMA_VERSION);
    }

    @Test
    void patchRejectsMissingPatchOperationWithStructuredDiagnostic() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), Collections.singletonList(null)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.patchOperationMissing");
    }

    @Test
    void patchFillsFingerprintForNewNodeWithoutClientFingerprintPatch() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        String fingerprint = catalog.find("risk:eligibility").orElseThrow().fingerprint();
        GraphDraft.DraftNode newNode = new GraphDraft.DraftNode(
                "eligibility2",
                "risk:eligibility",
                "",
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                Map.of(),
                null
        );

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("add", "/nodes/-", newNode)
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GraphDraft patched = response.getBody().draft();
        assertThat(patched.nodes())
                .extracting(GraphDraft.DraftNode::id)
                .contains("eligibility", "eligibility2");
        assertThat(patched.operatorFingerprints())
                .containsEntry("eligibility", fingerprint)
                .containsEntry("eligibility2", fingerprint);
    }

    @Test
    void patchStoredDraftRejectsStaleRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<GraphDraftPatchResult> response = controller.patch(stored.draftId(),
                new GraphDraftPatchRequest(stored.revision() - 1, List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "stalePatch")
                )));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().patched()).isFalse();
        assertThat(response.getBody().draft().revision()).isEqualTo(stored.revision());
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.draft.revisionConflict");
    }

    @Test
    void revisionsReturnStoredDraftHistory() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<GraphDraftPatchResult> patched = controller.patch(first.draftId(),
                new GraphDraftPatchRequest(first.revision(), List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "revisionTwo")
                )));
        GraphDraft second = patched.getBody().draft();

        ResponseEntity<List<GraphDraft>> response = controller.revisions(first.draftId());
        ResponseEntity<GraphDraft> firstRevision = controller.revision(first.draftId(), first.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .extracting(GraphDraft::revision)
                .containsExactly(second.revision(), first.revision());
        assertThat(firstRevision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstRevision.getBody()).isEqualTo(first);
    }

    @Test
    void revisionDiffReturnsMachineReadableDraftChangeReview() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft.DraftNode auditNode = new GraphDraft.DraftNode(
                "audit",
                "risk:eligibility",
                "Audit eligibility",
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                Map.of("mode", "audit"),
                new GraphDraft.Position(260, 120)
        );
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(
                        new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "revisionTwo"),
                        new GraphDraftPatchRequest.PatchOperation("add", "/nodes/-", auditNode),
                        new GraphDraftPatchRequest.PatchOperation("add", "/edges/-", new GraphDraft.DraftEdge(
                                "eligibility-to-audit",
                                "dependency",
                                new GraphDraft.Endpoint("eligibility", "", ""),
                                new GraphDraft.Endpoint("audit", "", "")
                        )),
                        new GraphDraftPatchRequest.PatchOperation("replace", "/output",
                                new GraphDraft.OutputSelection("audit", ""))
                )
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<GraphDraftDiff> response = controller.revisionDiff(first.draftId(),
                first.revision(),
                patched.draft().revision());
        ResponseEntity<GraphDraftDiff> missingTarget = controller.revisionDiff(first.draftId(),
                first.revision(),
                99);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraftDiff diff = response.getBody();
        assertThat(diff.schemaVersion()).isEqualTo(GraphDraftDiff.SCHEMA_VERSION);
        assertThat(diff.draftId()).isEqualTo(first.draftId());
        assertThat(diff.baseRevision()).isEqualTo(first.revision());
        assertThat(diff.targetRevision()).isEqualTo(patched.draft().revision());
        assertThat(diff.changed()).isTrue();
        assertThat(diff.changeRisk()).isEqualTo("RUNTIME_BINDING");
        assertThat(diff.addedNodeCount()).isEqualTo(1);
        assertThat(diff.addedEdgeCount()).isEqualTo(1);
        assertThat(diff.graphChanges())
                .extracting(GraphDraftDiff.GraphChange::field)
                .contains("graphName", "output");
        assertThat(diff.nodeChanges())
                .extracting(GraphDraftDiff.NodeChange::nodeId, GraphDraftDiff.NodeChange::changeKind)
                .contains(org.assertj.core.groups.Tuple.tuple("audit", "ADDED"));
        assertThat(diff.edgeChanges())
                .extracting(GraphDraftDiff.EdgeChange::edgeId, GraphDraftDiff.EdgeChange::changeKind)
                .contains(org.assertj.core.groups.Tuple.tuple("eligibility-to-audit", "ADDED"));
        assertThat(missingTarget.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void restoreRevisionCreatesAuditedLatestDraftRevision() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "revisionTwo"))
        )).getBody();
        assertThat(patched).isNotNull();
        GraphDraft second = patched.draft();

        ResponseEntity<GraphDraftPatchResult> response = controller.restoreRevision(first.draftId(),
                first.revision(),
                new GraphDraftRevisionRestoreRequest(second.revision(),
                        "architect",
                        "test-suite",
                        "Rollback to baseline draft.",
                        "Architect approved restore after comparing revision diff."));
        ResponseEntity<GraphDraftPatchResult> stale = controller.restoreRevision(first.draftId(),
                first.revision(),
                new GraphDraftRevisionRestoreRequest(first.revision(), "", "", ""));
        ResponseEntity<GraphDraftPatchResult> missingRevision = controller.restoreRevision(first.draftId(),
                99,
                new GraphDraftRevisionRestoreRequest(0, "", "", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        GraphDraft restored = response.getBody().draft();
        assertThat(response.getBody().patched()).isTrue();
        assertThat(restored.revision()).isEqualTo(second.revision() + 1);
        assertThat(restored.graphName()).isEqualTo(first.graphName());
        assertThat(restored.operatorFingerprints()).isEqualTo(first.operatorFingerprints());
        assertThat(restored.revisionMetadata().updatedBy()).isEqualTo("architect");
        assertThat(restored.revisionMetadata().changeSource()).isEqualTo("test-suite");
        assertThat(restored.revisionMetadata().changeSummary()).isEqualTo("Rollback to baseline draft.");
        assertThat(restored.revisionMetadata().changedPaths()).containsExactly("/");
        assertThat(restored.revisionMetadata().reason())
                .isEqualTo("Architect approved restore after comparing revision diff.");
        assertThat(controller.revisions(first.draftId()).getBody())
                .extracting(GraphDraft::revision)
                .containsExactly(restored.revision(), second.revision(), first.revision());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).isNotNull();
        assertThat(stale.getBody().diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.revisionConflict");
        assertThat(missingRevision.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void restoreRevisionCanRecoverDeletedDraftFromPreservedHistory() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "deletedLater"))
        )).getBody();
        assertThat(patched).isNotNull();
        GraphDraft second = patched.draft();

        ResponseEntity<Object> deleted = controller.delete(first.draftId(), second.revision(),
                "reviewer",
                "test-suite",
                "Delete before recovery.",
                "Reviewer confirmed deleted drafts remain recoverable from history.");
        ResponseEntity<GraphDraft> currentAfterDelete = controller.get(first.draftId());
        List<GraphDraft> historyAfterDelete = controller.revisions(first.draftId()).getBody();
        assertThat(historyAfterDelete).isNotNull();
        long deleteRevision = historyAfterDelete.getFirst().revision();
        ResponseEntity<GraphDraftPatchResult> staleRestore = controller.restoreRevision(first.draftId(),
                first.revision(),
                new GraphDraftRevisionRestoreRequest(second.revision(), "", "", ""));

        ResponseEntity<GraphDraftPatchResult> restoredResponse = controller.restoreRevision(first.draftId(),
                first.revision(),
                new GraphDraftRevisionRestoreRequest(deleteRevision,
                        "reviewer",
                        "test-suite",
                        "Recover deleted draft.",
                        "Reviewer approved recovery from retained delete revision."));

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(currentAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(historyAfterDelete)
                .extracting(GraphDraft::revision)
                .containsExactly(deleteRevision, second.revision(), first.revision());
        assertThat(historyAfterDelete.getFirst().revisionMetadata().changeSummary())
                .isEqualTo("Delete before recovery.");
        assertThat(historyAfterDelete.getFirst().revisionMetadata().reason())
                .isEqualTo("Reviewer confirmed deleted drafts remain recoverable from history.");
        assertThat(staleRestore.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleRestore.getBody()).isNotNull();
        assertThat(staleRestore.getBody().draft().revision()).isEqualTo(deleteRevision);
        assertThat(restoredResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restoredResponse.getBody()).isNotNull();
        GraphDraft restored = restoredResponse.getBody().draft();
        assertThat(restored.revision()).isEqualTo(deleteRevision + 1);
        assertThat(restored.graphName()).isEqualTo(first.graphName());
        assertThat(restored.revisionMetadata().changeSummary()).isEqualTo("Recover deleted draft.");
        assertThat(restored.revisionMetadata().reason())
                .isEqualTo("Reviewer approved recovery from retained delete revision.");
        assertThat(controller.get(first.draftId()).getBody()).isEqualTo(restored);
    }

    @Test
    void historyReturnsActiveAndRecoverableDeletedDraftSummaries() {
        VisualGraphDraftController controller = controllerWithEligibilityLibrary();
        GraphDraft active = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft deleted = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        ResponseEntity<Object> deleteResponse = controller.delete(deleted.draftId(), deleted.revision(),
                "reviewer",
                "test-suite",
                "Deleted but recoverable.",
                "Reviewer validated deleted draft history remains visible.");

        List<GraphDraftHistorySummary> history = controller.history();

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(history)
                .filteredOn(summary -> summary.draftId().equals(active.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.active()).isTrue();
                    assertThat(summary.currentRevision()).isEqualTo(active.revision());
                    assertThat(summary.latestRevision()).isEqualTo(active.revision());
                });
        assertThat(history)
                .filteredOn(summary -> summary.draftId().equals(deleted.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.active()).isFalse();
                    assertThat(summary.currentRevision()).isZero();
                    assertThat(summary.latestRevision()).isEqualTo(deleted.revision() + 1);
                    assertThat(summary.revisionCount()).isEqualTo(2);
                    assertThat(summary.changeSummary()).isEqualTo("Deleted but recoverable.");
                    assertThat(summary.reason()).isEqualTo("Reviewer validated deleted draft history remains visible.");
                });
    }

    @Test
    void summariesExposeAuthoritativeReadinessForActiveAndRecoverableDrafts() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        DefaultVisualOperatorCatalog designCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(designCatalog, drafts);
        GraphDraft active = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraft deleted = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        controller.delete(deleted.draftId(), deleted.revision(),
                "reviewer",
                "test-suite",
                "Deleted but recoverable.",
                "Verify deleted design drafts remain visible in the asset index.");

        List<GraphDraftSummary> summaries = controller.summaries();

        assertThat(summaries)
                .filteredOn(summary -> summary.draftId().equals(active.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.schemaVersion()).isEqualTo("bloge.visualGraphDraftSummary.v1");
                    assertThat(summary.tenantId()).isEqualTo(active.tenantId());
                    assertThat(summary.namespace()).isEqualTo(active.namespace());
                    assertThat(summary.environment()).isEqualTo(active.environment());
                    assertThat(summary.active()).isTrue();
                    assertThat(summary.currentRevision()).isEqualTo(active.revision());
                    assertThat(summary.valid()).isTrue();
                    assertThat(summary.nodeCount()).isEqualTo(1);
                    assertThat(summary.readiness().state()).isEqualTo("design-only");
                    assertThat(summary.readiness().artifactKinds()).containsExactly("DESIGN");
                    assertThat(summary.operatorDependencyCount()).isEqualTo(1);
                    assertThat(summary.runtimeReadinessStateCounts()).containsEntry("DESIGN_ONLY", 1);
                });
        assertThat(summaries)
                .filteredOn(summary -> summary.draftId().equals(deleted.draftId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.active()).isFalse();
                    assertThat(summary.currentRevision()).isZero();
                    assertThat(summary.latestRevision()).isEqualTo(deleted.revision() + 1);
                    assertThat(summary.valid()).isTrue();
                    assertThat(summary.readiness().state()).isEqualTo("design-only");
                    assertThat(summary.changeSummary()).isEqualTo("Deleted but recoverable.");
                    assertThat(summary.reason()).isEqualTo("Verify deleted design drafts remain visible in the asset index.");
                });
    }

    @Test
    void assetIndexesFilterDraftsAndRetainedHistoryByAuthoringScope() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        DefaultVisualOperatorCatalog designCatalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphDraftController controller = controllerWithCatalog(designCatalog, drafts);
        GraphDraft activeIncluded = controller.create(withScope(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "tenant-a", "risk", "dev"));
        GraphDraft deletedIncluded = controller.create(withScope(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "tenant-a", "risk", "dev"));
        GraphDraft excluded = controller.create(withScope(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )), "tenant-b", "risk", "dev"));

        controller.delete(deletedIncluded.draftId(), deletedIncluded.revision(),
                "reviewer",
                "test-suite",
                "Deleted but recoverable.",
                "Verify retained history remains scope-filtered.");

        assertThat(controller.list("tenant-a", "risk", "dev"))
                .extracting(GraphDraft::draftId)
                .containsExactly(activeIncluded.draftId());
        assertThat(controller.history("tenant-a", "risk", "dev"))
                .extracting(GraphDraftHistorySummary::draftId)
                .containsExactlyInAnyOrder(activeIncluded.draftId(), deletedIncluded.draftId());
        assertThat(controller.summaries("tenant-a", "risk", "dev"))
                .extracting(GraphDraftSummary::draftId)
                .containsExactlyInAnyOrder(activeIncluded.draftId(), deletedIncluded.draftId());
        assertThat(controller.summaries("tenant-b", "risk", "dev"))
                .extracting(GraphDraftSummary::draftId)
                .containsExactly(excluded.draftId());
    }

    @Test
    void deleteRejectsStaleExpectedRevisionAndKeepsCurrentDraft() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "deleteGuarded"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<Object> response = controller.delete(first.draftId(), first.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOf(GraphDraftPatchResult.class);
        GraphDraftPatchResult result = (GraphDraftPatchResult) response.getBody();
        assertThat(result.draft()).isEqualTo(patched.draft());
        assertThat(result.diagnostics())
                .extracting("code")
                .containsExactly("visual.draft.revisionConflict");
        assertThat(drafts.find(first.draftId())).contains(patched.draft());
    }

    @Test
    void deleteRemovesDraftWhenExpectedRevisionMatches() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<Object> response = controller.delete(stored.draftId(), stored.revision());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(drafts.find(stored.draftId())).isEmpty();
        assertThat(drafts.revisions(stored.draftId()))
                .extracting(GraphDraft::revision)
                .containsExactly(stored.revision() + 1, stored.revision());
        assertThat(drafts.revisions(stored.draftId()).getFirst().revisionMetadata().changeSource())
                .isEqualTo("delete");
    }

    @Test
    void runStoredDraftRejectsStaleExpectedRevision() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        VisualGraphDraftController controller = controllerWithCatalog(eligibilityCatalog(), drafts);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "runGuarded"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<VisualGraphRunResponse> response = controller.runStored(first.draftId(),
                new VisualStoredDraftRunRequest(
                        Map.of("score", 720, "amount", 100_000),
                        "eligibility",
                        first.revision()
                ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.revisionConflict");
                    assertThat(diagnostic.message())
                            .contains("expected %d".formatted(first.revision()))
                            .contains("current revision is %d".formatted(patched.draft().revision()));
                });
        assertThat(response.getBody().errors())
                .anySatisfy(error -> assertThat(error).contains("Draft revision conflict"));
    }

    @Test
    void runTransientRecordsRunHistoryAndReturnsRunId() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, new InMemoryGraphDraftRepository(),
                new InMemoryVisualGraphPublicationRepository(), runs, new FixedRunService());
        GraphDraft draft = eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        ));

        VisualGraphRunResponse response = controller.runTransient(new VisualGraphRunRequest(
                draft,
                Map.of("score", 720, "amount", 100_000, "apiToken", "secret-token"),
                "eligibility"
        ));

        assertThat(response.runId()).isNotBlank();
        VisualGraphRunRecord record = runs.find(response.runId()).orElseThrow();
        assertThat(record.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_TRANSIENT_DRAFT);
        assertThat(record.graphName()).isEqualTo("compileGate");
        assertThat(record.outputNode()).isEqualTo("eligibility");
        assertThat(record.contextSummary()).containsKeys("amount", "apiToken", "score");
        assertThat(record.toString()).doesNotContain("secret-token");
    }

    @Test
    void runStoredDraftRecordsSourceRevision() {
        DefaultVisualOperatorCatalog catalog = eligibilityCatalog();
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts,
                new InMemoryVisualGraphPublicationRepository(), runs, new FixedRunService());
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphRunResponse> response = controller.runStored(stored.draftId(),
                new VisualStoredDraftRunRequest(Map.of("score", 720, "amount", 100_000), "eligibility",
                        stored.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().runId()).isNotBlank();
        VisualGraphRunRecord record = runs.find(response.getBody().runId()).orElseThrow();
        assertThat(record.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_STORED_DRAFT);
        assertThat(record.draftId()).isEqualTo(stored.draftId());
        assertThat(record.draftRevision()).isEqualTo(stored.revision());
        assertThat(record.statusMap()).containsEntry("eligibility", "COMPLETED");
    }

    @Test
    void publishStoredDraftCreatesImmutablePublication() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().publicationId()).isNotBlank();
        assertThat(result.publication().dsl()).contains("transform eligibility");
        assertThat(result.publication().operatorSnapshots())
                .extracting("operatorRef")
                .containsExactly("risk:eligibility");
        assertThat(result.publication().operatorFingerprints()).containsKey("eligibility");
        assertThat(result.publication().dependencyReport().draftId()).isEqualTo(stored.draftId());
        assertThat(result.publication().dependencyReport().revision()).isEqualTo(stored.revision());
        assertThat(result.publication().dependencyReport().missingOperatorCount()).isZero();
        assertThat(result.publication().dependencyReport().runtimeReadinessStateCounts())
                .containsEntry("RUNTIME_EXECUTABLE", 1);
        assertThat(result.publication().dependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.fingerprintState()).isEqualTo("current");
                    assertThat(operator.scopeAllowed()).isTrue();
                });
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishRequiresWarningAcknowledgementBeforeProductionPromotion() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                eligibilityLibraryWithCapabilities(new OperatorDefinition.Capabilities(
                        "WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, false)));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.governance.nonIdempotent");
                    assertThat(diagnostic.message()).contains("production promotion");
                });
        assertThat(response.getBody().validation().actionReadiness().state())
                .isEqualTo("governance-review-required");
        assertThat(response.getBody().validation().actionReadiness().compileNow()).isTrue();
        assertThat(response.getBody().validation().actionReadiness().runNow()).isTrue();
        assertThat(response.getBody().validation().actionReadiness().publishExecutableNow()).isFalse();
        assertThat(response.getBody().validation().actionReadiness().publishExecutableAfterReview()).isTrue();
        assertThat(response.getBody().validation().actionReadiness().requiresAckWarnings()).isTrue();
        assertThat(response.getBody().validation().actionReadiness().requiresGovernanceEvidence()).isTrue();
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishStoresWarningDraftWhenWarningsAcknowledged() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                eligibilityLibraryWithCapabilities(new OperatorDefinition.Capabilities(
                        "WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, false)));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), true,
                        VisualGraphPublication.ARTIFACT_EXECUTABLE,
                        "publisher",
                        "promotion-review",
                        "Published non-idempotent risk policy.",
                        "Business owner reviewed non-idempotent side effects."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().validation().diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.code()).isEqualTo("visual.operator.governance.nonIdempotent"));
        assertThat(result.publication().publicationMetadata().actor()).isEqualTo("publisher");
        assertThat(result.publication().publicationMetadata().changeSource()).isEqualTo("promotion-review");
        assertThat(result.publication().publicationMetadata().changeSummary())
                .isEqualTo("Published non-idempotent risk policy.");
        assertThat(result.publication().publicationMetadata().reason())
                .isEqualTo("Business owner reviewed non-idempotent side effects.");
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishRequiresGovernanceEvidenceWhenWarningsAreAcknowledged() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                eligibilityLibraryWithCapabilities(new OperatorDefinition.Capabilities(
                        "WRITE_EXTERNAL", "NON_IDEMPOTENT", false, false, false)));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.governanceEvidenceMissing");
                    assertThat(diagnostic.target()).isEqualTo("/actor");
                    assertThat(diagnostic.metadata()).containsEntry("artifactKind",
                            VisualGraphPublication.ARTIFACT_EXECUTABLE);
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.governanceEvidenceMissing");
                    assertThat(diagnostic.target()).isEqualTo("/reason");
                });
        assertThat(response.getBody().validation().diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.code()).isEqualTo("visual.operator.governance.nonIdempotent"));
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsStaleExpectedRevision() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(catalog, drafts, publications);
        GraphDraft first = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));
        GraphDraftPatchResult patched = controller.patch(first.draftId(), new GraphDraftPatchRequest(
                first.revision(),
                List.of(new GraphDraftPatchRequest.PatchOperation("replace", "/graphName", "latestDraft"))
        )).getBody();
        assertThat(patched).isNotNull();

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(first.draftId(),
                new VisualGraphPublishRequest(first.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.revisionConflict");
                    assertThat(diagnostic.message())
                            .contains("expected %d".formatted(first.revision()))
                            .contains("current revision is %d".formatted(patched.draft().revision()));
                });
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsInvalidStoredDraft() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch");
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsGeneratedDslWhenRuntimeOperatorIsMissing() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(nativePolicyLibrary());
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(nativePolicyDraft());

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("bloge.dsl");
                    assertThat(diagnostic.message()).contains("riskMissingRuntime");
                });
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishDesignArtifactFreezesSchemaOnlyDraftWithoutExecutableDsl() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), false, VisualGraphPublication.ARTIFACT_DESIGN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().artifactKind()).isEqualTo(VisualGraphPublication.ARTIFACT_DESIGN);
        assertThat(result.publication().designArtifact()).isTrue();
        assertThat(result.publication().validation().valid()).isTrue();
        assertThat(result.publication().generation().generated()).isFalse();
        assertThat(result.publication().generation().diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.code()).isEqualTo("visual.action.compileBlocked"));
        assertThat(result.publication().operatorSnapshots())
                .extracting("operatorRef")
                .containsExactly("risk:eligibility");
        assertThat(result.publication().dependencyReport().runtimeReadinessStateCounts())
                .containsEntry("DESIGN_ONLY", 1);
        assertThat(result.publication().dependencyReport().operators())
                .singleElement()
                .satisfies(operator -> {
                    assertThat(operator.operatorRef()).isEqualTo("risk:eligibility");
                    assertThat(operator.runtimeReadinessState()).isEqualTo("DESIGN_ONLY");
                    assertThat(operator.artifactKinds()).containsExactly("DESIGN");
                    assertThat(operator.executable()).isFalse();
        });
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishDesignArtifactFreezesRuntimeBlockedStreamingDraftWhenWarningsAcknowledged() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                eligibilityLibraryWithCapabilities(new OperatorDefinition.Capabilities(
                        "PURE", "DETERMINISTIC", true, false, false)));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> missingAck = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), false, VisualGraphPublication.ARTIFACT_DESIGN));

        assertThat(missingAck.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missingAck.getBody()).isNotNull();
        assertThat(missingAck.getBody().published()).isFalse();
        assertThat(missingAck.getBody().validation().valid()).isTrue();
        assertThat(missingAck.getBody().validation().readiness().state()).isEqualTo("runtime-blocked");
        assertThat(missingAck.getBody().validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(missingAck.getBody().validation().actionReadiness().state()).isEqualTo("warning-ack-required");
        assertThat(missingAck.getBody().validation().actionReadiness().publishDesignNow()).isFalse();
        assertThat(missingAck.getBody().validation().actionReadiness().publishDesignAfterReview()).isTrue();
        assertThat(missingAck.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.runtime.streamingUnsupported");
                });
        assertThat(publications.all()).isEmpty();

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), true,
                        VisualGraphPublication.ARTIFACT_DESIGN,
                        "publisher",
                        "visual-canvas",
                        "Freeze streaming schema design.",
                        "Runtime owner will bind the streaming execution lane later."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        VisualGraphPublicationResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.published()).isTrue();
        assertThat(result.publication().artifactKind()).isEqualTo(VisualGraphPublication.ARTIFACT_DESIGN);
        assertThat(result.publication().designArtifact()).isTrue();
        assertThat(result.publication().validation().valid()).isTrue();
        assertThat(result.publication().validation().readiness().state()).isEqualTo("runtime-blocked");
        assertThat(result.publication().validation().readiness().runtimeBindingRequirements())
                .singleElement()
                .satisfies(requirement -> {
                    assertThat(requirement.bindingKind()).isEqualTo("streaming-runtime");
                    assertThat(requirement.handoffLane()).isEqualTo("streaming-runtime");
                    assertThat(requirement.handoffKind()).isEqualTo("streaming-execution");
                });
        assertThat(result.publication().generation().generated()).isFalse();
        assertThat(result.publication().generation().diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.code()).isEqualTo("visual.action.compileBlocked"));
        assertThat(result.publication().publicationMetadata().actor()).isEqualTo("publisher");
        assertThat(result.publication().publicationMetadata().reason())
                .isEqualTo("Runtime owner will bind the streaming execution lane later.");
        assertThat(publications.find(result.publication().publicationId())).contains(result.publication());
    }

    @Test
    void publishDesignArtifactReturnsPersistenceDiagnosticWhenRepositoryCreateFails() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        FailingCreateVisualGraphPublicationRepository publications =
                new FailingCreateVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), false, VisualGraphPublication.ARTIFACT_DESIGN));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().publication()).isNull();
        assertThat(response.getBody().validation()).isNotNull();
        assertThat(response.getBody().validation().valid()).isTrue();
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("design-only");
        assertThat(response.getBody().diagnostics())
                .singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.persistenceFailed");
                    assertThat(diagnostic.target()).isEqualTo("/publication");
                    assertThat(diagnostic.metadata())
                            .containsEntry("draftId", stored.draftId())
                            .containsEntry("draftRevision", stored.revision())
                            .containsEntry("graphName", stored.graphName())
                            .containsEntry("artifactKind", VisualGraphPublication.ARTIFACT_DESIGN)
                            .containsEntry("exceptionType", "IllegalStateException")
                            .containsEntry("exceptionMessage", "publication store unavailable");
                });
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishExecutableStillRejectsDesignOnlyDraft() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic ->
                        assertThat(diagnostic.code()).isEqualTo("visual.action.compileBlocked"));
        assertThat(response.getBody().validation()).isNotNull();
        assertThat(response.getBody().validation().valid()).isTrue();
        assertThat(response.getBody().validation().readiness().state()).isEqualTo("design-only");
        assertThat(response.getBody().validation().readiness().executable()).isFalse();
        assertThat(response.getBody().validation().readiness().artifactKinds()).containsExactly("DESIGN");
        assertThat(response.getBody().validation().actionReadiness().state()).isEqualTo("design-artifact-ready");
        assertThat(response.getBody().validation().actionReadiness().compileNow()).isFalse();
        assertThat(response.getBody().validation().actionReadiness().runNow()).isFalse();
        assertThat(response.getBody().validation().actionReadiness().publishDesignNow()).isTrue();
        assertThat(response.getBody().validation().actionReadiness().publishExecutableNow()).isFalse();
        assertThat(publications.all()).isEmpty();
    }

    @Test
    void publishRejectsUnsupportedArtifactKind() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        InMemoryVisualGraphPublicationRepository publications = new InMemoryVisualGraphPublicationRepository();
        VisualGraphDraftController controller = controllerWithCatalog(
                catalog,
                new InMemoryGraphDraftRepository(),
                publications
        );
        GraphDraft stored = controller.create(eligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )
        )));

        ResponseEntity<VisualGraphPublicationResult> response = controller.publish(stored.draftId(),
                new VisualGraphPublishRequest(stored.revision(), false, "prototype"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().published()).isFalse();
        assertThat(response.getBody().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.publication.artifactKindUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/artifactKind");
                });
        assertThat(publications.all()).isEmpty();
    }

    private static VisualGraphDraftController controllerWithEligibilityLibrary() {
        return controllerWithCatalog(eligibilityCatalog(), null);
    }

    private static DefaultVisualOperatorCatalog eligibilityCatalog() {
        return VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
    }

    private static OperatorLibrary eligibilityLibraryWithCapabilities(OperatorDefinition.Capabilities capabilities) {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                capabilities,
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static DefaultVisualOperatorCatalog emptyCatalog() {
        return VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty-risk-policy",
                "Empty risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of()
        ));
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository) {
        return controllerWithCatalog(catalog, repository, new InMemoryVisualGraphPublicationRepository());
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository,
                                                                    InMemoryVisualGraphPublicationRepository publications) {
        return controllerWithCatalog(catalog, repository, publications, new InMemoryVisualGraphRunRepository(),
                runner(catalog));
    }

    private static VisualGraphDraftController controllerWithCatalog(DefaultVisualOperatorCatalog catalog,
                                                                    InMemoryGraphDraftRepository repository,
                                                                    InMemoryVisualGraphPublicationRepository publications,
                                                                    InMemoryVisualGraphRunRepository runs,
                                                                    VisualGraphRunService runner) {
        return new VisualGraphDraftController(
                repository == null ? new InMemoryGraphDraftRepository() : repository,
                validator(catalog),
                runner,
                catalog,
                publications,
                new GraphDraftPatchService(new ObjectMapper()),
                runs
        );
    }

    private static GraphDraftValidator validator(DefaultVisualOperatorCatalog catalog) {
        return new GraphDraftValidator(catalog);
    }

    private static GraphDraftDslGenerator generator(DefaultVisualOperatorCatalog catalog) {
        return new GraphDraftDslGenerator(catalog);
    }

    private static VisualGraphRunService runner(DefaultVisualOperatorCatalog catalog) {
        GraphDraftValidator validator = validator(catalog);
        GraphDraftDslGenerator generator = generator(catalog);
        return new VisualGraphRunService(validator, generator,
                new DynamicGatewayComposerService(httpResourceOperatorStub()));
    }

    private static HttpResourceOperator httpResourceOperatorStub() {
        return new HttpResourceOperator(null, null, null, null, null, null);
    }

    private static class FixedRunService extends VisualGraphRunService {
        FixedRunService() {
            super(null, null, null);
        }

        @Override
        public VisualGraphRunResponse run(GraphDraft draft,
                                          Map<String, Object> context,
                                          String outputNode) {
            return new VisualGraphRunResponse(
                    true,
                    true,
                    true,
                    draft.graphName(),
                    outputNode,
                    Map.of("eligible", true),
                    Map.of("eligibility", Map.of("eligible", true)),
                    Map.of("eligibility", "COMPLETED"),
                    12,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    "graph %s {}".formatted(draft.graphName())
            );
        }
    }

    private static class FailingCreateVisualGraphPublicationRepository extends InMemoryVisualGraphPublicationRepository {
        @Override
        public VisualGraphPublication create(VisualGraphPublication publication) {
            throw new IllegalStateException("publication store unavailable");
        }
    }

    private static class FailingSaveGraphDraftRepository extends InMemoryGraphDraftRepository {
        @Override
        public GraphDraft save(GraphDraft draft) {
            throw new IllegalStateException("draft store unavailable");
        }
    }

    private static class FailingSaveIfRevisionGraphDraftRepository extends InMemoryGraphDraftRepository {
        @Override
        public Optional<GraphDraft> saveIfRevision(String draftId, long expectedRevision, GraphDraft draft) {
            throw new IllegalStateException("draft revision store unavailable");
        }
    }

    private static GraphDraft eligibilityDraft(SchemaEnvelope inputSchema) {
        return new GraphDraft(
                "",
                "",
                0,
                "compileGate",
                "",
                "",
                "",
                "",
                inputSchema,
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
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static GraphDraft withScope(GraphDraft draft, String tenantId, String namespace, String environment) {
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                tenantId,
                namespace,
                environment,
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.operatorSnapshots(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft twoNodeEligibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "dependencyGate",
                "",
                "",
                "",
                "",
                graphInputSchema(Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                )),
                List.of(
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "audit",
                                "risk:eligibility",
                                "Audit eligibility",
                                Map.of(
                                        "score", GraphDraft.Binding.expression("eligibility.output.ruleId"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge(
                        "eligibility-to-audit",
                        "dependency",
                        new GraphDraft.Endpoint("eligibility", "", ""),
                        new GraphDraft.Endpoint("audit", "", "")
                )),
                Map.of(),
                new GraphDraft.OutputSelection("audit", "")
        );
    }

    private static GraphDraft twoNodeBindingOnlyEligibilityDraft() {
        GraphDraft draft = twoNodeEligibilityDraft();
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                List.of(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static SchemaEnvelope graphInputSchema(Map<String, Object> properties) {
        return SchemaEnvelope.object(properties, properties.keySet().stream().toList());
    }

    private static GraphDraft nativePolicyDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nativeCompileGate",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:nativePolicy",
                        "",
                        Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft renameDraft(GraphDraft draft, String graphName) {
        return new GraphDraft(
                draft.schemaVersion(),
                draft.draftId(),
                draft.revision(),
                graphName,
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft withSchemaVersion(GraphDraft draft, String schemaVersion) {
        return new GraphDraft(
                schemaVersion,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                draft.status(),
                draft.inputSchema(),
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static GraphDraft numericPassDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "deprecatedGate",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "value", Map.of("type", "integer")
                ), List.of("value")),
                List.of(new GraphDraft.DraftNode(
                        "pass",
                        "risk:numericPass",
                        "",
                        Map.of("value", GraphDraft.Binding.contextPath("value")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("pass", "")
        );
    }

    private static GraphDraft withFingerprints(GraphDraft draft, DefaultVisualOperatorCatalog catalog) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            catalog.find(node.operatorRef())
                    .ifPresent(operator -> fingerprints.put(node.id(), operator.fingerprint()));
        }
        return draft.withOperatorFingerprints(fingerprints);
    }

    private static OperatorLibrary nativePolicyLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nativePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Native policy", "Requires a runtime native operator.",
                        List.of("risk", "native")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of(
                                        "applicantId", Map.of("type", "string")
                                ), List.of("applicantId")),
                                true,
                                "Native inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "decision", Map.of("type", "string")
                                ), List.of()),
                                true,
                                "Native output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskMissingRuntime", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-policy",
                "Risk native operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary deprecatedNumericPassLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-numeric-pass",
                "Numeric pass operators",
                "1.0.0",
                "risk-team",
                "DEPRECATED",
                List.of(VisualCatalogTestSupport.numericPassOperator())
        );
    }
}
