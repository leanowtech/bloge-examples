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
}
