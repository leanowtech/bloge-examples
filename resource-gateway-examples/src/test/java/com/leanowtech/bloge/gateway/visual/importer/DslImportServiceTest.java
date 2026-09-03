package com.leanowtech.bloge.gateway.visual.importer;

import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
    void resolvesNamedScalarInputAndOutputPortsFromDslPaths() {
        OperatorDefinition lookup = new OperatorDefinition(
                "bloge.visualOperator.v1", "cancel:lookup", "1.0.0",
                new OperatorDefinition.Display("Lookup", "Looks up a cancellation.", List.of("resource-read")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("orderId",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                        Map.of("type", "string")), true, "Order id.")),
                        List.of(new OperatorDefinition.Port("decision",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12",
                                        Map.of("type", "string")), true, "Decision."))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("design", "", Map.of()), List.of());
        DslImportService service = service(VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1", "cancel", "Cancellation", "1.0.0", "cx-ops",
                "ACTIVE", List.of(lookup))));

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "cancel.bloge", """
                        graph cancellation {
                          input { orderId: String }
                          node lookup : "cancel:lookup" { input { orderId = ctx.orderId } }
                          transform response { decision = lookup.output.decision }
                        }
                        """, List.of("cancel"), List.of(), "preview", Map.of()));

        GraphDraft.DraftNode node = projection.draft().nodes().getFirst();
        assertThat(node.inputs().get("orderId").targetPort()).isEqualTo("orderId");
        assertThat(node.inputs().get("orderId").targetPath()).isEmpty();
        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source().nodeId()).isEqualTo("lookup");
            assertThat(edge.source().port()).isEqualTo("decision");
            assertThat(edge.source().path()).isEmpty();
            assertThat(edge.target().nodeId()).isEqualTo("response");
        });
        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
    }

    @Test
    void connectsTwoLibraryNodesUsingTheirDeclaredNamedPorts() {
        SchemaEnvelope scalar = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of("type", "string"));
        OperatorDefinition lookup = namedPortOperator(
                "cancel:lookup", "orderId", "decision", scalar);
        OperatorDefinition persist = namedPortOperator(
                "cancel:persist", "decision", "receipt", scalar);
        DslImportService service = service(VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1", "cancel", "Cancellation", "1.0.0", "cx-ops",
                "ACTIVE", List.of(lookup, persist))));

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "cancel-chain.bloge", """
                        graph cancellationChain {
                          input { orderId: String }
                          node lookup : "cancel:lookup" { input { orderId = ctx.orderId } }
                          node persist : "cancel:persist" {
                            input { decision = lookup.output.decision }
                          }
                          transform response { receipt = persist.output.receipt }
                        }
                        """, List.of("cancel"), List.of(), "preview", Map.of()));

        GraphDraft.DraftNode node = projection.draft().nodes().stream()
                .filter(value -> "persist".equals(value.id())).findFirst().orElseThrow();
        assertThat(node.inputs().get("decision").targetPort()).isEqualTo("decision");
        assertThat(node.inputs().get("decision").targetPath()).isEmpty();
        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source()).isEqualTo(new GraphDraft.Endpoint("lookup", "decision", ""));
            assertThat(edge.target()).isEqualTo(new GraphDraft.Endpoint("persist", "decision", ""));
        });
        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
    }

    private static OperatorDefinition namedPortOperator(String ref,
                                                        String input,
                                                        String output,
                                                        SchemaEnvelope schema) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display(ref, ref, List.of("pure")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port(input, schema, true, input)),
                        List.of(new OperatorDefinition.Port(output, schema, true, output))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("design", "", Map.of()), List.of());
    }

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
    void blocksRewriteWhenDeclaredDslOutputCannotBeRoundTripped() {
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
        assertThat(projection.roundTrip().supported()).isFalse();
        assertThat(projection.roundTrip().status()).isEqualTo("DRIFT");
        assertThat(projection.roundTrip().message())
                .contains("canonical visual semantics differ");
        assertThat(projection.roundTrip().generatedDsl())
                .contains("graph transformOnly")
                .contains("input {")
                .contains("score: Int")
                .doesNotContain("output {")
                .contains("transform response");
        assertThat(projection.roundTrip().sourceFingerprint()).isNotBlank();
        assertThat(projection.roundTrip().generatedFingerprint())
                .isNotEqualTo(projection.roundTrip().sourceFingerprint());
    }

    @Test
    void marksTransformWithoutDeclaredOutputAsRoundTripSupported() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "transform-only.bloge",
                """
                        graph transformOnly {
                          input {
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
        assertThat(projection.roundTrip().supported())
                .as("round-trip summary: %s", projection.roundTrip())
                .isTrue();
        assertThat(projection.roundTrip().status()).isEqualTo("SUPPORTED");
        assertThat(projection.roundTrip().message())
                .contains("same canonical visual semantics");
        assertThat(projection.roundTrip().generatedDsl())
                .contains("graph transformOnly")
                .contains("input {")
                .contains("score: Int")
                .contains("amount: Decimal")
                .doesNotContain("output {")
                .contains("transform response");
        assertThat(projection.roundTrip().sourceFingerprint()).isNotBlank();
        assertThat(projection.roundTrip().generatedFingerprint())
                .isEqualTo(projection.roundTrip().sourceFingerprint());
    }

    @Test
    void semanticFingerprintUsesFirstClassOutputSchemaInsteadOfLegacyLayoutCopy() throws Exception {
        SchemaEnvelope inputSchema = SchemaEnvelope.object(Map.of(
                "score", Map.of("type", "integer")
        ), List.of("score"));
        SchemaEnvelope currentOutput = SchemaEnvelope.object(Map.of(
                "decision", Map.of("type", "boolean")
        ), List.of("decision"));
        SchemaEnvelope staleLayoutOutput = SchemaEnvelope.object(Map.of(
                "legacy", Map.of("type", "string")
        ), List.of("legacy"));
        SchemaEnvelope nextOutput = SchemaEnvelope.object(Map.of(
                "decision", Map.of("type", "string")
        ), List.of("decision"));

        GraphDraft canonical = draftWithOutputSchema(inputSchema, currentOutput, staleLayoutOutput);
        GraphDraft sameFirstClassContract = draftWithOutputSchema(inputSchema, currentOutput, SchemaEnvelope.opaque());
        GraphDraft changedFirstClassContract = draftWithOutputSchema(inputSchema, nextOutput, staleLayoutOutput);

        assertThat(semanticFingerprint(canonical))
                .isEqualTo(semanticFingerprint(sameFirstClassContract));
        assertThat(semanticFingerprint(canonical))
                .isNotEqualTo(semanticFingerprint(changedFirstClassContract));
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
        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
        assertThat(projection.diagnostics())
                .filteredOn(diagnostic -> "visual.dslImport.operatorMissing".equals(diagnostic.code()))
                .first()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.metadata()).containsEntry("projectionMode", "topology-only");
                });
        Map<String, Object> importMetadata = importMetadata(projection);
        assertThat(importMetadata)
                .containsEntry("projectionMode", "topology-only")
                .containsEntry("topologyOnly", true)
                .containsEntry("schemaPrecision", "inferred");
        assertThat(importMetadata.get("operatorRefs"))
                .asList()
                .contains("risk:eligibility", "bloge:transform");
        assertThat(importMetadata.get("missingOperatorRefs"))
                .asList()
                .containsExactly("risk:eligibility");
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
        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
        assertThat(projection.diagnostics())
                .filteredOn(diagnostic -> "visual.dslImport.functionMissing".equals(diagnostic.code()))
                .first()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.metadata()).containsEntry("projectionMode", "topology-only");
                });
        Map<String, Object> importMetadata = importMetadata(projection);
        assertThat(importMetadata)
                .containsEntry("projectionMode", "topology-only")
                .containsEntry("schemaPrecision", "inferred");
        assertThat(importMetadata.get("functionNames"))
                .asList()
                .containsExactly("businessRiskScore");
        assertThat(importMetadata.get("missingFunctionNames"))
                .asList()
                .containsExactly("businessRiskScore");
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

    @Test
    void projectsMultiLayerStrategyTablesAfterDocLegendSeparator() {
        DslImportService service = service(emptyCatalog());

        DslVisualProjection projection = service.preview(new DslImportPreviewRequest(
                "stg-like.bloge",
                """
                        /// Strategy tree converted from a policy table.
                        graph supportStrategy {
                          /// Field legend:
                          ///   trigger <- source trigger event
                          ///   kind    <- downstream policy kind
                          // ────────────────────────────────────────────
                          /// D01 root router
                          decision_table d01Pick(
                            trigger = ctx.trigger
                          ) hit=first -> String {
                            rule (trigger: trigger == "toD02") -> "GOTO_D02"
                            otherwise -> "direct"
                          }
                          transform d01 {
                            code: String = when {
                              d01Pick.output.value == "GOTO_D02" -> d02.code
                              otherwise -> d01Pick.output.value
                            }
                          }

                          // ────────────────────────────────────────────
                          /// D02 nested policy
                          decision_table d02Pick(
                            kind = ctx.kind
                          ) hit=first -> String {
                            rule (kind: kind == "vip") -> "manual"
                            otherwise -> "robot"
                          }
                          transform d02 {
                            code: String = d02Pick.output.value
                          }

                          transform strategyOutput {
                            actionCode: String = d01.code
                          }
                        }
                        """,
                List.of(),
                List.of(),
                "preview",
                Map.of()
        ));

        assertThat(projection.diagnostics()).noneMatch(VisualDiagnostic::error);
        assertThat(projection.coverage().projectedNodeCount()).isEqualTo(5);
        assertThat(projection.draft().nodes()).extracting(GraphDraft.DraftNode::id)
                .containsExactly("d01Pick", "d01", "d02Pick", "d02", "strategyOutput");
        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source().nodeId()).isEqualTo("d01Pick");
            assertThat(edge.target().nodeId()).isEqualTo("d01");
        });
        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source().nodeId()).isEqualTo("d02");
            assertThat(edge.target().nodeId()).isEqualTo("d01");
        });
        assertThat(projection.draft().edges()).anySatisfy(edge -> {
            assertThat(edge.source().nodeId()).isEqualTo("d01");
            assertThat(edge.target().nodeId()).isEqualTo("strategyOutput");
        });
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

    private static Map<String, Object> importMetadata(DslVisualProjection projection) {
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) projection.draft()
                .visualLayout()
                .get("import");
        return metadata;
    }

    private static GraphDraft draftWithOutputSchema(SchemaEnvelope inputSchema,
                                                    SchemaEnvelope outputSchema,
                                                    SchemaEnvelope layoutOutputSchema) {
        return new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "",
                0,
                "contractFingerprint",
                "demo-tenant",
                "local",
                "local",
                GraphDraft.STATUS_DRAFT,
                inputSchema,
                outputSchema,
                List.of(),
                List.of(),
                Map.of("graphContract", Map.of("outputSchema", layoutOutputSchema)),
                Map.of(),
                GraphDraft.OutputSelection.empty(),
                Map.of(),
                Map.of(),
                GraphDraft.RevisionMetadata.empty()
        );
    }

    private static String semanticFingerprint(GraphDraft draft) throws Exception {
        Method method = DslImportService.class.getDeclaredMethod("semanticFingerprint", GraphDraft.class);
        method.setAccessible(true);
        return (String) method.invoke(null, draft);
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
