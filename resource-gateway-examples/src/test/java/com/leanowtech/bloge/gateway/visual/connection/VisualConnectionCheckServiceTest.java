package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for interactive schema-aware connection checks.
 */
class VisualConnectionCheckServiceTest {

    @Test
    void acceptsSchemaCompatibleResourceToLibraryConnection() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().source().port()).isEqualTo("payload");
        assertThat(result.edge().target().path()).isEqualTo("score");
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().readiness().state()).isEqualTo("draft-repair-required");
        assertThat(result.summary().schemaVersion()).isEqualTo("bloge.visualConnectionCheckSummary.v1");
        assertThat(result.summary().accepted()).isTrue();
        assertThat(result.summary().kind()).isEqualTo("data");
        assertThat(result.summary().bindingKey()).isEqualTo("score");
        assertThat(result.summary().createsBinding()).isTrue();
        assertThat(result.summary().diagnosticCount()).isZero();
        assertThat(result.summary().candidateValid()).isFalse();
        assertThat(result.summary().graphStillInvalid()).isTrue();
        assertThat(result.summary().validationDiagnosticCount()).isGreaterThan(0);
        assertThat(result.summary().readinessState()).isEqualTo("draft-repair-required");
        assertThat(result.summary().readinessExecutable()).isFalse();
        assertThat(result.summary().message()).isEqualTo(
                "Connection accepted; graph still has validation issues.");
        assertThat(result.validation().diagnostics())
                .extracting("code")
                .contains("visual.input.required");
    }

    @Test
    void connectionPreviewSummarizesRuntimeBindingDebtForSchemaValidDesignOnlyDraft() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraftWithNodeInputs(
                Map.of("applicantId", GraphDraft.Binding.constant("applicant-1")),
                Map.of("amount", GraphDraft.Binding.constant(100000)),
                List.of()
        );

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));
        VisualConnectionCandidatesResult candidates = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                10,
                0,
                "eligibility",
                "input",
                "inputs",
                "score",
                GraphDraft.UnionBranchSelection.empty(),
                Map.of()
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.validation().readiness().state()).isEqualTo("design-only");
        assertThat(result.validation().readiness().executable()).isFalse();
        assertThat(result.summary().candidateValid()).isTrue();
        assertThat(result.summary().graphStillInvalid()).isFalse();
        assertThat(result.summary().readinessState()).isEqualTo("design-only");
        assertThat(result.summary().readinessExecutable()).isFalse();
        assertThat(result.summary().runtimeBindingRequirementCount()).isEqualTo(1);
        assertThat(result.summary().runtimeBindingRequirementKeys())
                .containsExactly("RUNTIME_BINDING|connection-preview||eligibility|executable-lowering|risk:eligibility|");
        assertThat(result.summary().bindingKindCounts()).containsEntry("executable-lowering", 1);
        assertThat(result.summary().handoffLaneCounts()).containsEntry("operator-platform", 1);
        assertThat(result.summary().handoffKindCounts()).containsEntry("operator-implementation", 1);
        assertThat(result.summary().handoffTargetCounts()).containsEntry("risk:eligibility", 1);
        assertThat(result.summary().sourceKindCounts()).containsEntry("user-library", 1);
        assertThat(result.summary().operatorLibraryIdCounts()).containsEntry("risk-policy-design", 1);
        assertThat(result.summary().loweringModeCounts()).containsEntry("design", 1);
        assertThat(result.summary().readinessStateCounts()).containsEntry("design-only", 1);
        assertThat(result.summary().message())
                .isEqualTo("Connection accepted; executable promotion needs runtime binding.");
        assertThat(candidates.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.accepted()).isTrue();
            assertThat(candidate.summary().runtimeBindingRequirementCount()).isEqualTo(1);
            assertThat(candidate.summary().bindingKindCounts()).containsEntry("executable-lowering", 1);
            assertThat(candidate.summary().handoffTargetCounts()).containsEntry("risk:eligibility", 1);
            assertThat(candidate.summary().operatorLibraryIdCounts()).containsEntry("risk-policy-design", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().requirementCount()).isEqualTo(1);
            assertThat(candidate.explanation().targetRuntimeBinding().requirementKeys())
                    .containsExactly("RUNTIME_BINDING|connection-preview||eligibility|executable-lowering|risk:eligibility|");
            assertThat(candidate.explanation().targetRuntimeBinding().bindingKindCounts())
                    .containsEntry("executable-lowering", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().handoffLaneCounts())
                    .containsEntry("operator-platform", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().handoffKindCounts())
                    .containsEntry("operator-implementation", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().handoffTargetCounts())
                    .containsEntry("risk:eligibility", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().sourceKindCounts())
                    .containsEntry("user-library", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().operatorLibraryIdCounts())
                    .containsEntry("risk-policy-design", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().loweringModeCounts())
                    .containsEntry("design", 1);
            assertThat(candidate.explanation().targetRuntimeBinding().readinessStateCounts())
                    .containsEntry("design-only", 1);
        });
    }

    @Test
    void discoversAcceptedAndRejectedConnectionTargetCandidates() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                50
        ));

        assertThat(result.schemaVersion()).isEqualTo("bloge.visualConnectionCandidates.v1");
        assertThat(result.source().nodeId()).isEqualTo("fetchApplicant");
        assertThat(result.kind()).isEqualTo("data");
        assertThat(result.offset()).isZero();
        assertThat(result.totalCandidateCount()).isGreaterThan(0);
        assertThat(result.acceptedCount()).isGreaterThan(0);
        assertThat(result.rejectedCount()).isGreaterThan(0);
        assertThat(result.displayedCount()).isEqualTo(result.candidates().size());
        assertThat(result.truncated()).isFalse();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.targetNodeId()).isEqualTo("eligibility");
                    assertThat(candidate.targetOperatorRef()).isEqualTo("risk:eligibility");
                    assertThat(candidate.targetSurface()).isEqualTo("input");
                    assertThat(candidate.target().port()).isEqualTo("inputs");
                    assertThat(candidate.target().path()).isEqualTo("score");
                    assertThat(candidate.accepted()).isTrue();
                    assertThat(candidate.bindingKey()).isEqualTo("score");
                    assertThat(candidate.summary().accepted()).isTrue();
                    assertThat(candidate.explanation().sourceLabel()).isEqualTo("fetchApplicant.payload.score");
                    assertThat(candidate.explanation().targetLabel()).isEqualTo("eligibility.inputs.score");
                    assertThat(candidate.explanation().sourceSchemaType()).isEqualTo("integer");
                    assertThat(candidate.explanation().targetSchemaType()).isEqualTo("integer");
                    assertThat(candidate.explanation().sourceSchemaKnown()).isTrue();
                    assertThat(candidate.explanation().targetSchemaKnown()).isTrue();
                    assertThat(candidate.explanation().decisionSource()).isEqualTo("server-validator");
                    assertThat(candidate.explanation().firstDiagnosticCode()).isBlank();
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.targetNodeId()).isEqualTo("eligibility");
                    assertThat(candidate.target().port()).isEqualTo("inputs");
                    assertThat(candidate.target().path()).isBlank();
                    assertThat(candidate.accepted()).isFalse();
                    assertThat(candidate.explanation().targetSchemaType()).isEqualTo("object");
                    assertThat(candidate.explanation().firstDiagnosticCode()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(candidate.diagnostics())
                            .extracting("code")
                            .contains("visual.binding.typeMismatch");
                });
    }

    @Test
    void connectionCandidatesCanBeFilteredToTargetNodeAndSurface() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                50,
                0,
                "eligibility",
                "input"
        ));

        assertThat(result.offset()).isZero();
        assertThat(result.totalCandidateCount()).isGreaterThan(0);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.targetNodeId()).isEqualTo("eligibility");
            assertThat(candidate.targetSurface()).isEqualTo("input");
        });
    }

    @Test
    void connectionCandidatesApplyOffsetAfterAcceptedRejectedFiltering() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCandidatesResult first = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                1,
                0,
                "eligibility",
                "input"
        ));
        VisualConnectionCandidatesResult second = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                1,
                1,
                "eligibility",
                "input"
        ));

        assertThat(second.offset()).isEqualTo(1);
        assertThat(second.totalCandidateCount()).isEqualTo(first.totalCandidateCount());
        assertThat(second.displayedCount()).isEqualTo(1);
        assertThat(second.candidates()).hasSize(1);
        assertThat(second.candidates().getFirst().target()).isNotEqualTo(first.candidates().getFirst().target());
    }

    @Test
    void connectionCandidatesReturnOnlyAcceptedRowsByDefault() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score")
        ));

        assertThat(result.totalCandidateCount()).isGreaterThan(result.displayedCount());
        assertThat(result.rejectedCount()).isGreaterThan(0);
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).allMatch(VisualConnectionCandidatesResult.ConnectionCandidate::accepted);
    }

    @Test
    void connectionCandidatesRespectDisplayLimit() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                "data",
                true,
                1
        ));

        assertThat(result.totalCandidateCount()).isGreaterThan(1);
        assertThat(result.displayedCount()).isEqualTo(1);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void connectionCandidatesDiscoverArrayItemTargetPaths() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.listCompatibilityLibrary("integer", "integer")));
        GraphDraft draft = listCompatibilityDraft();

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items.0"),
                "data",
                true,
                50,
                0,
                "listConsumer",
                "input"
        ));

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.target().nodeId()).isEqualTo("listConsumer");
                    assertThat(candidate.target().port()).isEqualTo("inputs");
                    assertThat(candidate.target().path()).isEqualTo("items.0");
                    assertThat(candidate.accepted()).isTrue();
                    assertThat(candidate.bindingKey()).isEqualTo("items.0");
                    assertThat(candidate.explanation().sourceSchemaType()).isEqualTo("integer");
                    assertThat(candidate.explanation().targetSchemaType()).isEqualTo("integer");
                    assertThat(candidate.explanation().sourceSchemaKnown()).isTrue();
                    assertThat(candidate.explanation().targetSchemaKnown()).isTrue();
                    assertThat(candidate.diagnostics()).isEmpty();
                });
    }

    @Test
    void connectionCandidatesDiscoverPrefixItemsAndUniformArrayRemainderPaths() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.listPrefixItemsCompatibilityLibrary(
                        List.of(Map.of("type", "integer")),
                        List.of(Map.of("type", "integer")))));
        GraphDraft draft = listCompatibilityDraft();

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items.0"),
                "data",
                true,
                50,
                0,
                "listConsumer",
                "input"
        ));

        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.target().path()).isEqualTo("items.0");
                    assertThat(candidate.accepted()).isTrue();
                    assertThat(candidate.explanation().targetSchemaType()).isEqualTo("integer");
                    assertThat(candidate.diagnostics()).isEmpty();
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.target().path()).isEqualTo("items.1");
                    assertThat(candidate.accepted()).isFalse();
                    assertThat(candidate.explanation().targetSchemaType()).isEqualTo("string");
                    assertThat(candidate.explanation().firstDiagnosticCode())
                            .isEqualTo("visual.binding.typeMismatch");
                    assertThat(candidate.diagnostics())
                            .extracting("code")
                            .contains("visual.binding.typeMismatch");
                });
    }

    @Test
    void acceptsArrayConnectionWhenSourceItemsAvoidTargetNotContains() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.listSchemaCompatibilityLibrary(
                        Map.of(
                                "type", "array",
                                "items", Map.of("type", "string", "enum", List.of("GOOD", "OK"))
                        ),
                        Map.of(
                                "type", "array",
                                "not", Map.of("contains", Map.of("const", "BAD"))
                        ))));
        GraphDraft draft = listCompatibilityDraft();

        VisualConnectionCheckResult check = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items"),
                new GraphDraft.Endpoint("listConsumer", "inputs", "items"),
                "data"
        ));
        VisualConnectionCandidatesResult candidates = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items"),
                "data",
                true,
                10,
                0,
                "listConsumer",
                "input",
                "inputs",
                "items",
                GraphDraft.UnionBranchSelection.empty(),
                Map.of()
        ));

        assertThat(check.accepted()).as("diagnostics: %s", check.diagnostics()).isTrue();
        assertThat(check.diagnostics()).isEmpty();
        assertThat(check.bindingKey()).isEqualTo("items");
        assertThat(candidates.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.target().path()).isEqualTo("items");
            assertThat(candidate.accepted()).isTrue();
            assertThat(candidate.diagnostics()).isEmpty();
            assertThat(candidate.explanation().decisionSource()).isEqualTo("server-validator");
        });
    }

    @Test
    void rejectsArrayConnectionWhenSourceItemsCouldMatchTargetNotContains() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.listSchemaCompatibilityLibrary(
                        Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        ),
                        Map.of(
                                "type", "array",
                                "not", Map.of("contains", Map.of("const", "BAD"))
                        ))));
        GraphDraft draft = listCompatibilityDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items"),
                new GraphDraft.Endpoint("listConsumer", "inputs", "items"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target excludes schema array contains [BAD] minContains 1")
                        .contains("cannot prove it avoids the excluded domain"));
    }

    @Test
    void acceptsConnectionPreviewWithExplicitTargetUnionBranchSelection() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(unionBranchSelectionLibrary()));
        GraphDraft draft = unionBranchSelectionDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("integerProducer", "output", "value"),
                new GraphDraft.Endpoint("unionConsumer", "inputs", "value"),
                "data",
                "",
                new GraphDraft.UnionBranchSelection("oneOf", 0)
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.bindingKey()).isEqualTo("value");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void connectionCandidatesReuseExplicitTargetUnionBranchSelection() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(unionBranchSelectionLibrary()));
        GraphDraft draft = unionBranchSelectionDraft();

        VisualConnectionCandidatesResult result = service.candidates(new VisualConnectionCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("integerProducer", "output", "value"),
                "data",
                true,
                10,
                0,
                "unionConsumer",
                "input",
                "inputs",
                "value",
                new GraphDraft.UnionBranchSelection("oneOf", 0),
                Map.of()
        ));

        assertThat(result.totalCandidateCount()).isEqualTo(1);
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.target().nodeId()).isEqualTo("unionConsumer");
            assertThat(candidate.target().port()).isEqualTo("inputs");
            assertThat(candidate.target().path()).isEqualTo("value");
            assertThat(candidate.accepted()).isTrue();
            assertThat(candidate.bindingKey()).isEqualTo("value");
            assertThat(candidate.explanation().targetSchemaType()).isEqualTo("integer");
            assertThat(candidate.diagnostics()).isEmpty();
        });
    }

    @Test
    void acceptsConnectionPreviewWithNestedTargetUnionBranchSelection() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(nestedUnionBranchSelectionLibrary()));
        GraphDraft draft = nestedUnionBranchSelectionDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("integerProducer", "output", "value"),
                new GraphDraft.Endpoint("unionConsumer", "inputs", "payload.score"),
                "data",
                "",
                GraphDraft.UnionBranchSelection.empty(),
                Map.of("payload", new GraphDraft.UnionBranchSelection("oneOf", 0))
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.bindingKey()).isEqualTo("payload.score");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsConnectionPreviewThatReplacesExistingInputBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraftWithEligibilityInputs(
                Map.of("score", GraphDraft.Binding.contextPath("score", "inputs", "score")),
                List.of()
        );

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.summary().replacedInputKeys()).containsExactly("score");
        assertThat(result.summary().replacedBindingCount()).isEqualTo(1);
        assertThat(result.summary().replacedEdgeIds()).isEmpty();
        assertThat(result.summary().replacedEdgeCount()).isZero();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsConnectionPreviewThatReplacesRootPortBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraftWithEligibilityInputs(
                Map.of("inputs", GraphDraft.Binding.contextPath("score", "inputs", "")),
                List.of()
        );

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.summary().replacedInputKeys()).containsExactly("inputs");
        assertThat(result.summary().replacedBindingCount()).isEqualTo(1);
        assertThat(result.summary().replacedEdgeIds()).isEmpty();
        assertThat(result.summary().replacedEdgeCount()).isZero();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void acceptsConnectionPreviewThatReplacesLegacyPortAliasBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraftWithEligibilityInputs(
                Map.of("inputs.score", GraphDraft.Binding.contextPath("score", "input", "score")),
                List.of()
        );

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsDuplicateDataConnectionPreview() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of(new GraphDraft.DraftEdge("score", "data",
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"))));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.duplicateConnection");
                    assertThat(diagnostic.target()).isEqualTo("/edges/1");
                });
    }

    @Test
    void reportsExistingDataEdgeReplacedForSameTargetEndpoint() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of(new GraphDraft.DraftEdge("old-score-edge", "data",
                new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"))));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.summary().replacedEdgeIds()).containsExactly("old-score-edge");
        assertThat(result.summary().replacedEdgeCount()).isEqualTo(1);
    }

    @Test
    void rejectsSchemaIncompatibleConnection() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.typeMismatch");
                    assertThat(diagnostic.message())
                            .contains("string")
                            .contains("integer")
                            .contains("source type string cannot feed target type integer");
                });
    }

    @Test
    void rejectsDecisionTableOutputConnectionUsingConfiguredOutputType() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = decisionTableEligibilityDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("loanPolicy", "output", "decision"),
                new GraphDraft.Endpoint("riskEligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.summary().accepted()).isFalse();
        assertThat(result.summary().kind()).isEqualTo("data");
        assertThat(result.summary().bindingKey()).isEqualTo("score");
        assertThat(result.summary().createsBinding()).isTrue();
        assertThat(result.summary().diagnosticCount()).isEqualTo(result.diagnostics().size());
        assertThat(result.summary().errorCount()).isEqualTo(result.diagnostics().size());
        assertThat(result.summary().warningCount()).isZero();
        assertThat(result.summary().diagnosticCodeCounts())
                .containsEntry("visual.binding.typeMismatch", 1)
                .containsEntry("visual.edge.typeMismatch", 1);
        assertThat(result.summary().candidateValid()).isFalse();
        assertThat(result.summary().graphStillInvalid()).isFalse();
        assertThat(result.summary().readinessState()).isEqualTo("draft-repair-required");
        assertThat(result.summary().message()).isEqualTo("Connection rejected by server.");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message())
                            .contains("string")
                            .contains("integer")
                            .contains("source type string cannot feed target type integer");
                })
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo("visual.edge.typeMismatch"));
    }

    @Test
    void rejectsConnectionPreviewWhenSourcePathCannotRenderAsDslPathSegment() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.unsafePathLibrary()));
        GraphDraft draft = unsafePathDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("unsafeFacts", "facts", "bad-field"),
                new GraphDraft.Endpoint("scoreSink", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.pathSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/score/path");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.pathSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/source/path");
                });
    }

    @Test
    void rejectsConnectionPreviewWhenSourceOutputPortCannotRenderAsDslPathSegment() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.unsafeOutputPortLibrary()));
        GraphDraft draft = unsafeOutputPortDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("unsafePortFacts", "graph", "score"),
                new GraphDraft.Endpoint("scoreSink", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.sourcePortSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/score/sourcePort");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.sourcePortSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/source/port");
                });
    }

    @Test
    void rejectsConnectionPreviewWhenTargetPathCannotRenderAsDslPathSegment() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.unsafePathLibrary()));
        GraphDraft draft = unsafePathDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("unsafeFacts", "facts", "safeScore"),
                new GraphDraft.Endpoint("scoreSink", "inputs", "bad-target"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("bad-target");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.targetPathSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/bad-target");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.pathSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/target/path");
                });
    }

    @Test
    void rejectsConnectionPreviewWhenTargetInputPortCannotRenderAsDslPathSegment() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.unsafeInputPortLibrary()));
        GraphDraft draft = unsafeInputPortDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("safeScoreFacts", "output", "score"),
                new GraphDraft.Endpoint("unsafeInputPortSink", "mode", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.targetPortSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/score/targetPort");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.targetPortSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/target/port");
                });
    }

    @Test
    void rejectsUnsupportedEdgeKindPreview() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "control"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.kindUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/kind");
                });
    }

    @Test
    void acceptsCanonicalizedEdgeKindPreview() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                " DATA "
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().kind()).isEqualTo("data");
    }

    @Test
    void rejectsConnectionPreviewWhenDraftSchemaVersionIsUnsupported() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft base = resourceEligibilityDraft(graphInputSchema(), List.of());
        GraphDraft draft = copyDraft(base, "bloge.visualGraphDraft.v2", base.status(), base.inputSchema());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
                });
    }

    @Test
    void rejectsConnectionPreviewWhenDraftStatusIsUnsupported() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft base = resourceEligibilityDraft(graphInputSchema(), List.of());
        GraphDraft draft = copyDraft(base, base.schemaVersion(), "LOCKED", base.inputSchema());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.status.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/status");
                });
    }

    @Test
    void rejectsContextPickerPreviewWhenGraphInputSchemaIsInvalid() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(unsupportedCompositionGraphInputSchema(), List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.compositionUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/inputSchema/schema/if");
                });
    }

    @Test
    void acceptsDependencyEdgePreviewWithoutInputBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = scoreFactsDependencyDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("prepareFacts", "", ""),
                new GraphDraft.Endpoint("publishFacts", "dependency", ""),
                "depends_on"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().kind()).isEqualTo("dependency");
    }

    @Test
    void rejectsDependencyEdgePreviewThatWouldCreateCycle() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = scoreFactsDependencyDraft(List.of(new GraphDraft.DraftEdge("publish-before-prepare",
                "dependency",
                new GraphDraft.Endpoint("publishFacts", "", ""),
                new GraphDraft.Endpoint("prepareFacts", "", ""))));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("prepareFacts", "", ""),
                new GraphDraft.Endpoint("publishFacts", "dependency", ""),
                "dependency"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo("visual.edge.cycle"));
    }

    @Test
    void acceptsRouteEdgePreviewWithoutInputBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routePreviewDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("routeByType", "route", ""),
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                "branch",
                "physical"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().kind()).isEqualTo("route");
        assertThat(result.edge().condition()).isEqualTo("physical");
    }

    @Test
    void rejectsRouteEdgePreviewWithDuplicateCondition() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routePreviewDraft(List.of(new GraphDraft.DraftEdge("route-physical",
                "route",
                new GraphDraft.Endpoint("routeByType", "", ""),
                new GraphDraft.Endpoint("genericFacts", "", ""),
                "physical")));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("routeByType", "route", ""),
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                "route",
                "physical"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.routeConditionDuplicate"));
    }

    @Test
    void rejectsRouteEdgePreviewWithSemanticallyDuplicateQuotedCondition() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routePreviewDraft(List.of(new GraphDraft.DraftEdge("route-physical",
                "route",
                new GraphDraft.Endpoint("routeByType", "", ""),
                new GraphDraft.Endpoint("genericFacts", "", ""),
                "physical")));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("routeByType", "route", ""),
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                "route",
                "\"physical\""
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.routeConditionDuplicate"));
    }

    @Test
    void rejectsRouteEdgePreviewWhenConditionDoesNotMatchSelectorSchema() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routePreviewDraft(List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("routeByType", "route", ""),
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                "route",
                "true"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.routeConditionTypeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/condition");
                });
    }

    @Test
    void acceptsSchemaCompatibleContextPickerBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(graphInputSchema(), List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().source().nodeId()).isEqualTo("__ctx");
        assertThat(result.edge().target().path()).isEqualTo("score");
    }

    @Test
    void acceptsContextRootPortPickerBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = customerOrderMergeDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "customer"),
                new GraphDraft.Endpoint("merge", "customer", ""),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().target().port()).isEqualTo("customer");
        assertThat(result.edge().target().path()).isEmpty();
    }

    @Test
    void returnsPortQualifiedBindingKeyForDuplicateInputPathPorts() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = customerOrderMergeDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "customer.id"),
                new GraphDraft.Endpoint("merge", "customer", "id"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.bindingKey()).isEqualTo("customer.id");
    }

    @Test
    void acceptsNodeOutputRootPortPickerBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.rootObjectPortLibrary()));
        GraphDraft draft = customerFactsToMergeDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("customerFacts", "customer", ""),
                new GraphDraft.Endpoint("merge", "customer", ""),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().source().port()).isEqualTo("customer");
        assertThat(result.edge().source().path()).isEmpty();
        assertThat(result.edge().target().port()).isEqualTo("customer");
        assertThat(result.edge().target().path()).isEmpty();
    }

    @Test
    void rejectsNodeOutputRootPortPickerBindingWhenDynamicFieldCollidesWithTargetOptionalProperty() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.dynamicOptionalCollisionLibrary()));
        GraphDraft draft = dynamicOptionalCollisionDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("dynamicStringFacts", "facts", ""),
                new GraphDraft.Endpoint("optionalScoreSink", "inputs", ""),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("at 'score'")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void rejectsWholeObjectConnectionWithSourceAdditionalFieldsForStrictTarget() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.objectCompatibilityLibrary(
                        applicantProperties("integer", true),
                        List.of("score", "tier"),
                        applicantProperties("integer", false),
                        List.of("score", "tier"))));
        GraphDraft draft = applicantObjectDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("applicantProducer", "output", "applicant"),
                new GraphDraft.Endpoint("applicantConsumer", "inputs", "applicant"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source object declares additional field 'segment'")
                        .contains("additionalProperties=false"));
    }

    @Test
    void rejectsConnectionPreviewWhenArrayIndexSegmentIsNotCanonical() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.listCompatibilityLibrary("integer", "integer")));
        GraphDraft draft = listCompatibilityDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("listFacts", "output", "items.+1"),
                new GraphDraft.Endpoint("listConsumer", "inputs", "items.0"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.unknownSourcePath");
                    assertThat(diagnostic.message()).contains("items.+1");
                });
    }

    @Test
    void rejectsNodeConnectionThatWouldOverlapExistingRootBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.rootObjectPortLibrary()));
        GraphDraft draft = customerRootAlreadyBoundDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("customerFacts", "customer", "id"),
                new GraphDraft.Endpoint("merge", "customer", "id"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.duplicateTarget");
                    assertThat(diagnostic.message()).contains("customer.id");
                });
    }

    @Test
    void acceptsNodeConnectionReplacingExistingSameTargetBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "replaceConnectionCheck",
                "",
                "",
                "",
                "",
                graphInputSchema(),
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("score", "inputs", "amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void rejectsUnknownContextPickerPath() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(graphInputSchema(), List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "missingScore"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.unknownContextPath");
    }

    @Test
    void rejectsIncompatibleContextPickerBinding() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(graphInputSchema(), List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "segment"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch");
    }

    @Test
    void rejectsContextPickerBindingWhenNumberCannotGuaranteeIntegerTarget() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "number"),
                "segment", Map.of("type", "string")
        ), List.of()), List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message())
                            .contains("ctx.score")
                            .contains("target type integer requires integer-valued source");
                });
    }

    @Test
    void rejectsContextPickerBindingWhenAdditionalPropertiesSchemaTypeDoesNotMatch() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(dynamicAdditionalGraphInputSchema(Map.of("type", "string")),
                List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "dynamicScore"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message())
                            .contains("ctx.dynamicScore")
                            .contains("string")
                            .contains("integer");
                });
    }

    @Test
    void acceptsContextPickerBindingThroughUnevaluatedPropertiesSchema() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(dynamicUnevaluatedGraphInputSchema(Map.of("type", "integer")),
                List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "dynamicScore"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.bindingKey()).isEqualTo("score");
    }

    @Test
    void returnsPortQualifiedBindingKeyForDuplicateUnevaluatedInputPathPorts() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.dynamicUnevaluatedDuplicateInputLibrary()));
        GraphDraft draft = dynamicUnevaluatedDuplicateInputDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "dynamicScore"),
                new GraphDraft.Endpoint("merge", "primary", "dynamicScore"),
                "data"
        ));

        assertThat(result.accepted()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.bindingKey()).isEqualTo("primary.dynamicScore");
    }

    @Test
    void acceptsNodePickerBindingThroughDynamicOutputSchema() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.dynamicUnevaluatedOutputLibrary()));
        GraphDraft draft = dynamicOutputFactsDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("riskDynamicFacts", "facts", "dynamicScore"),
                new GraphDraft.Endpoint("riskScoreSink", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.bindingKey()).isEqualTo("score");
        assertThat(result.edge().source().port()).isEqualTo("facts");
        assertThat(result.edge().source().path()).isEqualTo("dynamicScore");
    }

    @Test
    void rejectsContextPickerBindingWhenDynamicPropertyNameViolatesGraphInputSchema() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = resourceEligibilityDraft(
                dynamicAdditionalGraphInputSchema(Map.of("type", "integer"), Map.of("pattern", "^risk[A-Z].*")),
                List.of());

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("__ctx", "ctx", "badScore"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownContextPath");
                    assertThat(diagnostic.message()).contains("ctx.badScore");
                });
    }

    @Test
    void acceptsSchemaCompatibleConfigSourcePickerExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = resourceConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("policy", "config", "threshold"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().target().port()).isEqualTo("config");
        assertThat(result.edge().target().path()).isEqualTo("threshold");
    }

    @Test
    void rejectsNativeConfigInputPreviewWhenNodeAlreadyLowersBusinessConfigSchemaValues() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.nativeConfigCollisionLibrary()));
        GraphDraft draft = nativeConfigCollisionPreviewDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("configFacts", "output", "threshold"),
                new GraphDraft.Endpoint("policy", "inputs", "config"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.bindingKey()).isEqualTo("config");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.configConflict");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/config");
                });
    }

    @Test
    void acceptsRootArraySourcePickerConfigExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(rootArrayConfigLibrary()));
        GraphDraft draft = rootArrayConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("rootFacts", "output", "0.score"),
                new GraphDraft.Endpoint("policy", "config", "threshold"),
                "data"
        ));

        assertThat(result.accepted()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().source().path()).isEqualTo("0.score");
        assertThat(result.edge().target().path()).isEqualTo("threshold");
    }

    @Test
    void acceptsArrayConfigTargetSourcePickerExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(arrayConfigPolicyLibrary()));
        GraphDraft draft = arrayConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("policy", "config", "thresholds.0"),
                "data"
        ));

        assertThat(result.accepted()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().target().path()).isEqualTo("thresholds.0");
    }

    @Test
    void rejectsSchemaIncompatibleConfigSourcePickerExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = resourceConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                new GraphDraft.Endpoint("policy", "config", "threshold"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/config/threshold/expr");
                });
    }

    @Test
    void rejectsConfigSourcePickerExpressionWithDslUnsafeSourcePathSegment() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = resourceConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score-id"),
                new GraphDraft.Endpoint("policy", "config", "threshold"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.expression.pathSegment.invalid");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/config/threshold/expr");
                    assertThat(diagnostic.message()).contains("fetchApplicant.output.payload.score-id");
                });
    }

    @Test
    void acceptsSchemaCompatibleNestedConfigSourcePickerExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.nestedConfigPolicyLibrary()));
        GraphDraft draft = resourceNestedConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("policy", "config", "limits.threshold"),
                "data"
        ));

        assertThat(result.accepted()).isTrue();
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.edge().target().port()).isEqualTo("config");
        assertThat(result.edge().target().path()).isEqualTo("limits.threshold");
    }

    @Test
    void rejectsSchemaIncompatibleNestedConfigSourcePickerExpression() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLoanApplicantResourceAndLibrary(VisualCatalogTestSupport.nestedConfigPolicyLibrary()));
        GraphDraft draft = resourceNestedConfigDraft();

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                new GraphDraft.Endpoint("policy", "config", "limits.threshold"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/config/limits/threshold/expr");
                });
    }

    @Test
    void rejectsConnectionThatWouldCreateCycle() {
        VisualConnectionCheckService service = connectionService(VisualCatalogTestSupport
                .catalogWithLibrary(VisualCatalogTestSupport.numericPassLibrary()));
        GraphDraft draft = numericPassDraft(List.of(new GraphDraft.DraftEdge("b-to-a", "data",
                new GraphDraft.Endpoint("passB", "output", "value"),
                new GraphDraft.Endpoint("passA", "inputs", "value"))));

        VisualConnectionCheckResult result = service.check(new VisualConnectionCheckRequest(
                draft,
                new GraphDraft.Endpoint("passA", "output", "value"),
                new GraphDraft.Endpoint("passB", "inputs", "value"),
                "data"
        ));

        assertThat(result.accepted()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.edge.cycle");
    }

    private static VisualConnectionCheckService connectionService(DefaultVisualOperatorCatalog catalog) {
        return new VisualConnectionCheckService(new GraphDraftValidator(catalog), catalog);
    }

    private static OperatorLibrary unionBranchSelectionLibrary() {
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:integerProducer",
                "1.0.0",
                new OperatorDefinition.Display("Integer producer", "Produces an integer value.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("value", Map.of("type", "integer")), List.of()),
                                true,
                                "Integer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "integerProducer", Map.of()),
                List.of()
        );
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unionConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Union consumer", "Consumes a selected union branch.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("value", Map.of("oneOf", List.of(
                                        Map.of("type", "integer"),
                                        Map.of("type", "number")
                                ))), List.of("value")),
                                true,
                                "Union input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "unionConsumer", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "union-branch-selection",
                "Union branch selection",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static GraphDraft unionBranchSelectionDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "unionBranchSelection",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "integerProducer",
                                "risk:integerProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "unionConsumer",
                                "risk:unionConsumer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("unionConsumer", "")
        );
    }

    private static OperatorLibrary nestedUnionBranchSelectionLibrary() {
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:integerProducer",
                "1.0.0",
                new OperatorDefinition.Display("Integer producer", "Produces an integer value.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("value", Map.of("type", "integer")), List.of()),
                                true,
                                "Integer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "integerProducer", Map.of()),
                List.of()
        );
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nestedUnionConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Nested union consumer", "Consumes a branch child path.",
                        List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("payload", Map.of("oneOf", List.of(
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of("score", Map.of("type", "integer")),
                                                "required", List.of("score"),
                                                "additionalProperties", false
                                        ),
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of("decision", Map.of("type", "string")),
                                                "required", List.of("decision"),
                                                "additionalProperties", false
                                        )
                                ))), List.of("payload")),
                                true,
                                "Nested union input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "nestedUnionConsumer", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "nested-union-branch-selection",
                "Nested union branch selection",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static GraphDraft nestedUnionBranchSelectionDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nestedUnionBranchSelection",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "integerProducer",
                                "risk:integerProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "unionConsumer",
                                "risk:nestedUnionConsumer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("unionConsumer", "")
        );
    }

    private static SchemaEnvelope graphInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "integer"),
                "segment", Map.of("type", "string")
        ), List.of());
    }

    private static SchemaEnvelope dynamicAdditionalGraphInputSchema(Object additionalProperties) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", additionalProperties
        ));
    }

    private static SchemaEnvelope dynamicUnevaluatedGraphInputSchema(Object unevaluatedProperties) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "unevaluatedProperties", unevaluatedProperties
        ));
    }

    private static SchemaEnvelope dynamicAdditionalGraphInputSchema(Object additionalProperties,
                                                                    Object propertyNames) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", additionalProperties,
                "propertyNames", propertyNames
        ));
    }

    private static SchemaEnvelope unsupportedCompositionGraphInputSchema() {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("score", Map.of("type", "integer")),
                "if", Map.of("required", List.of("score"))
        ));
    }

    private static GraphDraft copyDraft(GraphDraft draft,
                                        String schemaVersion,
                                        String status,
                                        SchemaEnvelope inputSchema) {
        return new GraphDraft(
                schemaVersion,
                draft.draftId(),
                draft.revision(),
                draft.graphName(),
                draft.tenantId(),
                draft.namespace(),
                draft.environment(),
                status,
                inputSchema,
                draft.nodes(),
                draft.edges(),
                draft.visualLayout(),
                draft.output(),
                draft.operatorFingerprints(),
                draft.revisionMetadata()
        );
    }

    private static SchemaEnvelope customerOrderInputSchema() {
        return SchemaEnvelope.object(Map.of(
                "customer", Map.of(
                        "type", "object",
                        "properties", Map.of("id", Map.of("type", "string")),
                        "required", List.of("id"),
                        "additionalProperties", false
                ),
                "orderId", Map.of("type", "string")
        ), List.of("customer", "orderId"));
    }

    private static Map<String, Object> applicantProperties(String scoreType, boolean includeExtra) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("score", Map.of("type", scoreType));
        properties.put("tier", Map.of("type", "string"));
        if (includeExtra) {
            properties.put("segment", Map.of("type", "string"));
        }
        return properties;
    }

    private static GraphDraft resourceEligibilityDraft(List<GraphDraft.DraftEdge> edges) {
        return resourceEligibilityDraft(null, edges);
    }

    private static GraphDraft resourceEligibilityDraft(SchemaEnvelope inputSchema, List<GraphDraft.DraftEdge> edges) {
        return resourceEligibilityDraftWithEligibilityInputs(inputSchema, Map.of(), edges);
    }

    private static GraphDraft resourceEligibilityDraftWithEligibilityInputs(
            Map<String, GraphDraft.Binding> eligibilityInputs,
            List<GraphDraft.DraftEdge> edges) {
        return resourceEligibilityDraftWithEligibilityInputs(graphInputSchema(), eligibilityInputs, edges);
    }

    private static GraphDraft resourceEligibilityDraftWithEligibilityInputs(
            SchemaEnvelope inputSchema,
            Map<String, GraphDraft.Binding> eligibilityInputs,
            List<GraphDraft.DraftEdge> edges) {
        return resourceEligibilityDraftWithNodeInputs(inputSchema, Map.of(), eligibilityInputs, edges);
    }

    private static GraphDraft resourceEligibilityDraftWithNodeInputs(
            Map<String, GraphDraft.Binding> resourceInputs,
            Map<String, GraphDraft.Binding> eligibilityInputs,
            List<GraphDraft.DraftEdge> edges) {
        return resourceEligibilityDraftWithNodeInputs(graphInputSchema(), resourceInputs, eligibilityInputs, edges);
    }

    private static GraphDraft resourceEligibilityDraftWithNodeInputs(
            SchemaEnvelope inputSchema,
            Map<String, GraphDraft.Binding> resourceInputs,
            Map<String, GraphDraft.Binding> eligibilityInputs,
            List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "connectionCheck",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                resourceInputs,
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                eligibilityInputs,
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static GraphDraft decisionTableEligibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "decisionTableConnectionCheck",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ), List.of()),
                List.of(
                        new GraphDraft.DraftNode(
                                "loanPolicy",
                                "bloge:decisionTable",
                                "Loan Policy",
                                Map.of(),
                                Map.of(
                                        "inputs", Map.of(
                                                "score", "ctx.score",
                                                "amount", "ctx.amount"
                                        ),
                                        "outputType",
                                        "{ decision: String, rate: Decimal, maxTerm: Int, reviewLane: String, ruleId: String }",
                                        "rules", List.of(Map.of(
                                                "otherwise", true,
                                                "output", Map.of(
                                                        "decision", "approved",
                                                        "rate", 3.5,
                                                        "maxTerm", 360,
                                                        "reviewLane", "auto",
                                                        "ruleId", "R1"
                                                )
                                        ))
                                ),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "riskEligibility",
                                "risk:eligibility",
                                "Eligibility",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("riskEligibility", "")
        );
    }

    private static GraphDraft applicantObjectDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "objectConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "applicantProducer",
                                "risk:applicantObjectProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "applicantConsumer",
                                "risk:applicantObjectConsumer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("applicantConsumer", "")
        );
    }

    private static GraphDraft resourceConfigDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "configConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:configurablePolicy",
                                "",
                                Map.of(),
                                Map.of("mode", "strict"),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft nativeConfigCollisionPreviewDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nativeConfigCollisionPreview",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "configFacts",
                                "risk:configFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:nativeConfigPolicy",
                                "",
                                Map.of(),
                                Map.of("limit", 700),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft rootArrayConfigDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "rootArrayConfigConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "rootFacts",
                                "risk:rootArrayFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:configurablePolicy",
                                "",
                                Map.of(),
                                Map.of("mode", "strict"),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft arrayConfigDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "arrayConfigConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:arrayConfigPolicy",
                                "",
                                Map.of(),
                                Map.of("mode", "strict"),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static OperatorLibrary arrayConfigPolicyLibrary() {
        Map<String, Object> configProperties = new java.util.LinkedHashMap<>();
        configProperties.put("thresholds", Map.of(
                "type", "array",
                "items", Map.of("type", "integer")
        ));
        configProperties.put("mode", Map.of(
                "type", "enum",
                "values", List.of("strict", "relaxed")
        ));
        Map<String, Object> outputProperties = Map.of("accepted", Map.of("type", "boolean"));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:arrayConfigPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Array config policy",
                        "Evaluates policy behavior controlled by array config.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(configProperties, List.of("thresholds", "mode")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "true")
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-array-config-policy",
                "Array config policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static OperatorLibrary rootArrayConfigLibrary() {
        OperatorDefinition rootArrayFacts = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:rootArrayFacts",
                "1.0.0",
                new OperatorDefinition.Display("Root array facts",
                        "Produces an array as the output port root.",
                        List.of("risk", "array")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of("score", Map.of("type", "integer")),
                                                "required", List.of("score"),
                                                "additionalProperties", false
                                        )
                                )),
                                true,
                                "Root array facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskRootArrayFacts", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-root-array-config",
                "Risk root array config operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(rootArrayFacts, VisualCatalogTestSupport.configurablePolicyOperator())
        );
    }

    private static GraphDraft dynamicUnevaluatedDuplicateInputDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "dynamicMapMerge",
                "",
                "",
                "",
                "",
                dynamicUnevaluatedGraphInputSchema(Map.of("type", "integer")),
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:dynamicMapMerge",
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static GraphDraft dynamicOutputFactsDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "dynamicOutputFacts",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "riskDynamicFacts",
                                "risk:dynamicFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "riskScoreSink",
                                "risk:scoreSink",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("riskScoreSink", "")
        );
    }

    private static GraphDraft unsafePathDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "unsafePathConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "unsafeFacts",
                                "risk:unsafeFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "scoreSink",
                                "risk:scoreSink",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreSink", "")
        );
    }

    private static GraphDraft unsafeOutputPortDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "unsafeOutputPortConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "unsafePortFacts",
                                "risk:unsafePortFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "scoreSink",
                                "risk:scoreSink",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreSink", "")
        );
    }

    private static GraphDraft unsafeInputPortDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "unsafeInputPortConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "safeScoreFacts",
                                "risk:safeScoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "unsafeInputPortSink",
                                "risk:unsafeInputPortSink",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("unsafeInputPortSink", "")
        );
    }

    private static GraphDraft listCompatibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "listCompatibilityConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "listFacts",
                                "risk:listFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "listConsumer",
                                "risk:listConsumer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("listConsumer", "")
        );
    }

    private static GraphDraft customerOrderMergeDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "rootPortConnectionCheck",
                "",
                "",
                "",
                "",
                customerOrderInputSchema(),
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        Map.of("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static GraphDraft customerFactsToMergeDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nodeRootPortConnectionCheck",
                "",
                "",
                "",
                "",
                customerOrderInputSchema(),
                List.of(
                        new GraphDraft.DraftNode(
                                "customerFacts",
                                "risk:customerFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "merge",
                                "risk:customerOrderMerge",
                                "",
                                Map.of("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id")),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static GraphDraft customerRootAlreadyBoundDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "rootOverlapConnectionCheck",
                "",
                "",
                "",
                "",
                customerOrderInputSchema(),
                List.of(
                        new GraphDraft.DraftNode(
                                "customerFacts",
                                "risk:customerFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "merge",
                                "risk:customerOrderMerge",
                                "",
                                Map.of(
                                        "customer", GraphDraft.Binding.contextPath("customer", "customer", ""),
                                        "order.id", GraphDraft.Binding.contextPath("orderId", "order", "id")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static GraphDraft dynamicOptionalCollisionDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "dynamicOptionalCollisionConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "dynamicStringFacts",
                                "risk:dynamicStringFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "optionalScoreSink",
                                "risk:optionalScoreSink",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("optionalScoreSink", "")
        );
    }

    private static GraphDraft resourceNestedConfigDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "nestedConfigConnectionCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "fetchApplicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:nestedConfigPolicy",
                                "",
                                Map.of(),
                                Map.of("limits", Map.of("mode", "strict")),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft numericPassDraft(List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "cycleCheck",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode("passA", "risk:numericPass", "", Map.of(), Map.of(), null),
                        new GraphDraft.DraftNode("passB", "risk:numericPass", "", Map.of(), Map.of(), null)
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("passB", "")
        );
    }

    private static GraphDraft scoreFactsDependencyDraft(List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "dependencyPreview",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode("prepareFacts", "risk:scoreFacts", "", Map.of(), Map.of(), null),
                        new GraphDraft.DraftNode("publishFacts", "risk:scoreFacts", "", Map.of(), Map.of(), null)
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("publishFacts", "")
        );
    }

    private static GraphDraft routePreviewDraft(List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "routePreview",
                "",
                "",
                "",
                "",
                SchemaEnvelope.object(Map.of(
                        "productType", Map.of("type", "string")
                ), List.of("productType")),
                List.of(
                        new GraphDraft.DraftNode(
                                "routeByType",
                                "risk:typeRoute",
                                "",
                                Map.of("value", GraphDraft.Binding.contextPath("productType")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode("physicalFacts", "risk:scoreFacts", "", Map.of(), Map.of(), null),
                        new GraphDraft.DraftNode("genericFacts", "risk:scoreFacts", "", Map.of(), Map.of(), null)
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("physicalFacts", "")
        );
    }
}
