package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for schema-neutral BLOGE DSL import projection.
 */
class DslImportServiceTest {

    @Test
    void projectsDslWithInlineOperatorLibraryWithoutDependingOnSchemaOrigin() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "migrated-eligibility.bloge",
                eligibilityDsl(),
                List.of(),
                List.of(VisualCatalogTestSupport.eligibilityLibrary("integer")),
                "preview",
                Map.of()
        ));

        assertThat(projection.schemaVersion()).isEqualTo(DslVisualProjection.SCHEMA_VERSION);
        assertThat(projection.draft().graphName()).isEqualTo("migratedEligibility");
        assertThat(projection.coverage().projectedNodeCount()).isEqualTo(2);
        assertThat(projection.coverage().missingOperatorCount()).isZero();
        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);

        GraphDraft.DraftNode eligibility = projection.draft().nodes().get(0);
        assertThat(eligibility.operatorRef()).isEqualTo("risk:eligibility");
        assertThat(eligibility.inputs().get("score").kind()).isEqualTo("contextPath");
        assertThat(eligibility.inputs().get("score").path()).isEqualTo("score");
        assertThat(eligibility.inputs().get("score").targetPath()).isEqualTo("score");
        assertThat(projection.draft().operatorSnapshots()).containsKey("eligibility");

        assertThat(projection.draft().inputSchema().properties())
                .containsEntry("score", Map.of("type", "integer"))
                .containsEntry("amount", Map.of("type", "number"));
        SchemaEnvelope outputSchema = projection.draft().outputSchema();
        assertThat(outputSchema.properties())
                .containsEntry("eligible", Map.of("type", "boolean"))
                .containsEntry("ruleId", Map.of("type", "string"));
        assertThat(outputSchema(projection).properties()).isEqualTo(outputSchema.properties());
        assertThat(projection.sourceMap().nodes()).containsKey("eligibility");
        assertThat(projection.draft().visualLayout())
                .extracting(layout -> ((Map<?, ?>) layout.get("import")).get("schemaNeutral"))
                .isEqualTo(true);
        assertThat(projection.roundTrip().status()).isEqualTo("DRIFT");
        assertThat(projection.roundTrip().generatedDsl()).contains("graph migratedEligibility");
        assertThat(projection.roundTrip().sourceFingerprint()).isNotBlank();
        assertThat(projection.roundTrip().generatedFingerprint()).isNotBlank();
    }

    @Test
    void marksTransformOnlyDslAsRoundTripSupported() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "transform-only.bloge",
                """
                        graph transformOnly {
                          input {
                            score: Int
                            amount: Decimal
                          }
                          output {
                            score: Int
                            amount: Decimal
                          }
                          transform response {
                            score = ctx.score
                            amount = ctx.amount
                          }
                        }
                        """,
                List.of(),
                List.of(),
                "preview",
                Map.of()
        ));

        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
        assertThat(projection.roundTrip().supported()).isTrue();
        assertThat(projection.roundTrip().status()).isEqualTo("SUPPORTED");
        assertThat(projection.roundTrip().message())
                .contains("same canonical visual semantics");
        assertThat(projection.roundTrip().generatedDsl())
                .contains("graph transformOnly")
                .contains("input {")
                .contains("score: Int")
                .contains("output {")
                .contains("amount: Decimal")
                .contains("transform response");
        assertThat(projection.roundTrip().sourceFingerprint()).isNotBlank();
        assertThat(projection.roundTrip().generatedFingerprint())
                .isEqualTo(projection.roundTrip().sourceFingerprint());
    }

    @Test
    void rendersDraftWithMissingOperatorPlaceholderAndDiagnostic() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "missing-library.bloge",
                eligibilityDsl(),
                List.of(),
                List.of(),
                "preview",
                Map.of()
        ));

        assertThat(projection.draft().nodes()).extracting(GraphDraft.DraftNode::id)
                .containsExactly("eligibility", "response");
        assertThat(projection.coverage().missingOperatorCount()).isEqualTo(1);
        assertThat(projection.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.dslImport.operatorMissing");
    }

    @Test
    void reportsMissingBuiltInFunctionWithoutDroppingTransformNode() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "missing-function.bloge",
                """
                        graph missingFunction {
                          transform response {
                            score = businessRiskScore(ctx.score)
                          }
                        }
                        """,
                List.of(),
                List.of(),
                "preview",
                Map.of()
        ));

        assertThat(projection.draft().nodes()).extracting(GraphDraft.DraftNode::id)
                .containsExactly("response");
        assertThat(projection.coverage().missingFunctionCount()).isEqualTo(1);
        assertThat(projection.diagnostics())
                .extracting(VisualDiagnostic::code)
                .contains("visual.dslImport.functionMissing");
    }

    @Test
    void projectsDecisionTableInputsFromIncomingDataExpressions() throws Exception {
        DslImportService service = service(emptyCatalog());
        String dsl = Files.readString(Path.of(
                "src/main/resources/bloge/gateway/loan-decision-policy.bloge"));

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "loan-decision-policy.bloge",
                dsl,
                List.of(),
                List.of(),
                "preview",
                Map.of()
        ));

        GraphDraft.DraftNode decision = projection.draft().nodes().stream()
                .filter(node -> node.id().equals("loanPolicy"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) decision.config().get("inputs");
        assertThat(inputs)
                .containsEntry("score", "fetchApplicant.output.payload.score")
                .containsEntry("amount", "ctx.requestedAmount");
        assertThat(decision.inputs().get("score").kind()).isEqualTo("nodePath");
        assertThat(decision.inputs().get("score").nodeId()).isEqualTo("fetchApplicant");

        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source().nodeId()).isEqualTo("fetchApplicant");
            assertThat(edge.target().nodeId()).isEqualTo("loanPolicy");
            assertThat(edge.target().path()).isEqualTo("score");
        });
        @SuppressWarnings("unchecked")
        List<Object> rules = (List<Object>) decision.config().get("rules");
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRule = (Map<String, Object>) rules.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> conditions = (Map<String, Object>) firstRule.get("conditions");
        assertThat(conditions).containsEntry("score", "score >= 760");
    }

    private static DslImportService service(DefaultVisualOperatorCatalog catalog) {
        return new DslImportService(catalog, new OperatorLibraryValidator());
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

    private static SchemaEnvelope outputSchema(DslVisualProjection projection) {
        @SuppressWarnings("unchecked")
        Map<String, Object> graphContract = (Map<String, Object>) projection.draft()
                .visualLayout()
                .get("graphContract");
        return (SchemaEnvelope) graphContract.get("outputSchema");
    }

    private static String eligibilityDsl() {
        return """
                graph migratedEligibility {
                  input {
                    score: Int
                    amount: Decimal
                  }
                  output {
                    eligible: Boolean
                    ruleId: String
                  }
                  node eligibility : "risk:eligibility" {
                    input {
                      score = ctx.score
                      amount = ctx.amount
                    }
                  }
                  transform response {
                    eligible = eligibility.output.eligible
                    ruleId = eligibility.output.ruleId
                  }
                }
                """;
    }
}
