package com.leanowtech.bloge.gateway.visual.connection;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
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
                    assertThat(diagnostic.target()).isEqualTo("/inputSchema/schema/oneOf");
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
        return new VisualConnectionCheckService(new GraphDraftValidator(catalog));
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

    private static SchemaEnvelope unsupportedCompositionGraphInputSchema() {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("score", Map.of("type", "integer")),
                "oneOf", List.of(Map.of("required", List.of("score")))
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
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
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
