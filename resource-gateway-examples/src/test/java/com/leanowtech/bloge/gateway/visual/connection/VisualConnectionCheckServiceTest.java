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
                    assertThat(diagnostic.message()).contains("string").contains("integer");
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
