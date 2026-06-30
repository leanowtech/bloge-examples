package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for visual graph validation.
 */
class GraphDraftValidatorTest {

    @Test
    void reportsMissingRequiredResourceInput() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResource());
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "loanPolicy",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "fetchApplicant",
                        "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("fetchApplicant", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.required");
                    assertThat(diagnostic.target()).contains("applicantId");
                });
    }

    @Test
    void rejectsGraphInputSchemaWithRequiredPathMissingFromProperties() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        ),
                        "required", List.of("score", "amount", "riskTier"),
                        "additionalProperties", false
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.requiredUnknown");
                    assertThat(diagnostic.target()).isEqualTo("/inputSchema/schema/required");
                    assertThat(diagnostic.message()).contains("riskTier");
                });
    }

    @Test
    void rejectsGraphInputSchemaWithArrayMissingItems() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number"),
                                "history", Map.of("type", "array")
                        ),
                        "required", List.of("score", "amount"),
                        "additionalProperties", false
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.arrayItemsMissing");
                    assertThat(diagnostic.target()).isEqualTo("/inputSchema/schema/properties/history/items");
                });
    }

    @Test
    void rejectsNodePathBindingWhenOutputTypeDoesNotMatchTargetInputSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("string")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "typedBinding",
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
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("facts", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("integer").contains("string");
                });
    }

    @Test
    void acceptsCompatibleTypedEdge() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsDataEdgeWithoutMatchingNodePathBinding() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.constant(720),
                new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.bindingMissing");
                    assertThat(diagnostic.message()).contains("fetchApplicant.payload.score");
                });
    }

    @Test
    void rejectsNodePathBindingWithoutMatchingDataEdge() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.edgeMissing");
                    assertThat(diagnostic.message()).contains("fetchApplicant.payload.score");
                });
    }

    @Test
    void acceptsDataEdgeRepresentingConfigExpressionReference() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = eligibilityToTransformConfigDraft("eligibility.output.eligible",
                List.of(new GraphDraft.DraftEdge("eligible", "data",
                        new GraphDraft.Endpoint("eligibility", "output", "eligible"),
                        new GraphDraft.Endpoint("mapResult", "inputs", "eligible"))));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsConfigExpressionWhenNodeReferencePathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = eligibilityToTransformConfigDraft("eligibility.output.missing", List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownOutputPath");
                    assertThat(diagnostic.message()).contains("eligibility").contains("missing");
                    assertThat(diagnostic.target()).contains("/config/assignments/eligible");
                });
    }

    @Test
    void rejectsCycleCreatedByConfigExpressionReference() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericPassLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "configCycle",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "mapValue",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of("value", "passValue.output.value")),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "passValue",
                                "risk:numericPass",
                                "",
                                Map.of("value", GraphDraft.Binding.nodePath(
                                        "mapValue",
                                        "output",
                                        "value",
                                        "inputs",
                                        "value")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("value", "data",
                        new GraphDraft.Endpoint("mapValue", "output", "value"),
                        new GraphDraft.Endpoint("passValue", "inputs", "value"))),
                Map.of(),
                new GraphDraft.OutputSelection("passValue", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo("visual.edge.cycle"));
    }

    @Test
    void acceptsContextPathBindingWhenGraphInputSchemaIsCompatible() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsDraftWhenOperatorPolicyDoesNotAllowEnvironment() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer",
                                new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"),
                                        List.of("prod")))));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.policyDenied");
                    assertThat(diagnostic.message()).contains("environment='local'").contains("prod");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/operatorRef");
                });
    }

    @Test
    void rejectsDraftWhenOperatorFingerprintSnapshotHasDrifted() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        )).withOperatorFingerprints(Map.of("eligibility", "sha256:old-definition"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.fingerprintMismatch");
                    assertThat(diagnostic.message())
                            .contains("eligibility")
                            .contains("sha256:old-definition");
                });
    }

    @Test
    void rejectsDraftWhenOperatorFingerprintSnapshotIsIncomplete() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        )).withOperatorFingerprints(Map.of("otherNode", "sha256:other-definition"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.fingerprintMissing");
                    assertThat(diagnostic.message())
                            .contains("eligibility")
                            .contains("risk:eligibility");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/operatorRef");
                });
    }

    @Test
    void acceptsConstantBindingWhenValueMatchesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "amount", Map.of("type", "number")
                ),
                List.of("amount")
        ), Map.of(
                "score", GraphDraft.Binding.constant(720),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsConstantBindingWhenValueDoesNotMatchTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "amount", Map.of("type", "number")
                ),
                List.of("amount")
        ), Map.of(
                "score", GraphDraft.Binding.constant("high"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("Constant").contains("score").contains("integer");
                });
    }

    @Test
    void rejectsUnsupportedInputBindingKind() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "amount", Map.of("type", "number")
                ),
                List.of("amount")
        ), Map.of(
                "score", new GraphDraft.Binding(
                        "templateLiteral",
                        720,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        Map.of()
                ),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.kindUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/score/kind");
                    assertThat(diagnostic.message()).contains("templateLiteral");
                });
    }

    @Test
    void rejectsContextPathBindingWhenGraphInputPathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("riskScore"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownContextPath");
                    assertThat(diagnostic.message()).contains("ctx.riskScore");
                });
    }

    @Test
    void rejectsContextPathBindingWhenGraphInputTypeDoesNotMatchTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("string").contains("integer").contains("ctx.score");
                });
    }

    @Test
    void rejectsExpressionBindingWhenContextReferencePathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.riskScore + 1"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownContextPath");
                    assertThat(diagnostic.message()).contains("ctx.riskScore");
                });
    }

    @Test
    void rejectsExpressionBindingWhenPureContextReferenceTypeDoesNotMatchTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "string"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.score").contains("string").contains("integer");
                });
    }

    @Test
    void rejectsExpressionBindingWhenNodeReferencePathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.expression("fetchApplicant.output.payload.riskScore"),
                new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownOutputPath");
                    assertThat(diagnostic.message()).contains("fetchApplicant").contains("riskScore");
                });
    }

    @Test
    void acceptsCompoundExpressionBindingWhenReferencesExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.score + 1"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsNodePathBindingFromSelectedOutputPort() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = multiOutputEligibilityDraft(
                GraphDraft.Binding.nodePath("scoreFacts", "facts", "score"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenSourcePortIsAmbiguous() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = multiOutputEligibilityDraft(
                GraphDraft.Binding.nodePath("scoreFacts", "score"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.binding.unknownSourcePort"));
    }

    @Test
    void rejectsNodePathBindingWhenSelectedOutputPortDoesNotExposePath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = multiOutputEligibilityDraft(
                GraphDraft.Binding.nodePath("scoreFacts", "summary", "score"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.binding.unknownOutputPath"));
    }

    @Test
    void acceptsDuplicateInputPathsWhenTargetPortsAreExplicit() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = duplicateInputPathDraft(Map.of(
                "customer.id", GraphDraft.Binding.contextPath("customerId", "customer", "id"),
                "order.id", GraphDraft.Binding.contextPath("orderId", "order", "id")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsDuplicateInputTargetsOnSamePortAndPath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("score", GraphDraft.Binding.contextPath("score"));
        inputs.put("overrideScore", GraphDraft.Binding.contextPath("overrideScore", "inputs", "score"));
        inputs.put("amount", GraphDraft.Binding.contextPath("amount"));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "overrideScore", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "overrideScore", "amount")
        ), inputs);

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.duplicateTarget");
                    assertThat(diagnostic.message()).contains("inputs.score").contains("eligibility");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/overrideScore");
                });
    }

    @Test
    void rejectsDuplicateNestedTargetFromObjectTemplateField() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("applicant", new GraphDraft.Binding(
                "objectTemplate",
                null,
                "",
                "",
                "",
                "inputs",
                "applicant",
                "",
                Map.of("score", GraphDraft.Binding.constant(720))
        ));
        inputs.put("manualScore", new GraphDraft.Binding(
                "constant",
                730,
                "",
                "",
                "",
                "inputs",
                "applicant.score",
                "",
                Map.of()
        ));
        GraphDraft draft = nestedApplicantEligibilityDraft(inputs, List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.duplicateTarget");
                    assertThat(diagnostic.message()).contains("inputs.applicant.score").contains("eligibility");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/inputs/manualScore");
                });
    }

    @Test
    void reportsMissingRequiredInputForOneDuplicatePathPort() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = duplicateInputPathDraft(Map.of(
                "customer.id", GraphDraft.Binding.contextPath("customerId", "customer", "id")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.required");
                    assertThat(diagnostic.message()).contains("id").contains("order");
                });
    }

    @Test
    void acceptsNodePathBindingToNestedTargetPath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "payload", "score",
                        "inputs", "applicant.score"),
                new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "applicant.score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenNestedTargetTypeDoesNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "payload", "segment",
                        "inputs", "applicant.score"),
                new GraphDraft.DraftEdge("segment", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "applicant.score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("string").contains("integer");
                });
    }

    @Test
    void reportsMissingNestedRequiredInputPath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                Map.of(),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.required");
                    assertThat(diagnostic.message()).contains("applicant.score");
                });
    }

    @Test
    void reportsMissingNestedRequiredInputPathInsideObjectTemplate() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                Map.of("applicant", new GraphDraft.Binding(
                        "objectTemplate",
                        null,
                        "",
                        "",
                        "",
                        "inputs",
                        "applicant",
                        "",
                        Map.of()
                )),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.input.required");
                    assertThat(diagnostic.message()).contains("applicant.score");
                });
    }

    @Test
    void validatesObjectTemplateFieldsAgainstNestedTargetPath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                Map.of("applicant", new GraphDraft.Binding(
                        "objectTemplate",
                        null,
                        "",
                        "",
                        "",
                        "inputs",
                        "applicant",
                        "",
                        Map.of("score", GraphDraft.Binding.nodePath("fetchApplicant", "payload", "segment"))
                )),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("string").contains("integer");
                });
    }

    @Test
    void acceptsObjectTemplateWhenNestedRequiredFieldsAreBound() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                Map.of("applicant", new GraphDraft.Binding(
                        "objectTemplate",
                        null,
                        "",
                        "",
                        "",
                        "inputs",
                        "applicant",
                        "",
                        Map.of("score", GraphDraft.Binding.constant(720))
                )),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void validatesObjectTemplateConstantFieldsAgainstNestedTargetPath() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedApplicantEligibilityLibrary()));
        GraphDraft draft = nestedApplicantEligibilityDraft(
                Map.of("applicant", new GraphDraft.Binding(
                        "objectTemplate",
                        null,
                        "",
                        "",
                        "",
                        "inputs",
                        "applicant",
                        "",
                        Map.of("score", GraphDraft.Binding.constant("high"))
                )),
                List.of());

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("applicant.score").contains("integer");
                });
    }

    @Test
    void acceptsNodePathBindingWhenArrayItemTypesAreCompatible() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listCompatibilityLibrary("integer", "number")));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenArrayItemTypesDoNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listCompatibilityLibrary("string", "integer")));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("array<string>").contains("array<integer>");
                });
    }

    @Test
    void rejectsEdgeWhenArrayItemTypesDoNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listCompatibilityLibrary("string", "integer")));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.contextPath("items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.typeMismatch");
                    assertThat(diagnostic.message()).contains("array<string>").contains("array<integer>");
                });
    }

    @Test
    void acceptsEnumBindingWhenSourceValuesAreTargetSubset() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.enumCompatibilityLibrary(
                                List.of("APPROVE"),
                                List.of("APPROVE", "REJECT"))));
        GraphDraft draft = enumCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsEnumBindingWhenSourceValuesAreOutsideTargetDomain() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.enumCompatibilityLibrary(
                                List.of("LOW", "HIGH"),
                                List.of("APPROVE", "REJECT"))));
        GraphDraft draft = enumCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("enum<LOW|HIGH>").contains("enum<APPROVE|REJECT>"));
    }

    @Test
    void rejectsPlainStringBindingWhenTargetRequiresEnumDomain() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.enumCompatibilityLibrary(
                                List.of(),
                                List.of("APPROVE", "REJECT"))));
        GraphDraft draft = enumCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
    }

    @Test
    void acceptsObjectBindingWhenTargetRequiredFieldsArePresent() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                applicantProperties("integer", true),
                                List.of("score", "tier"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsObjectBindingWhenSourceMissesTargetRequiredField() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                Map.of("score", Map.of("type", "integer")),
                                List.of("score"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
    }

    @Test
    void rejectsObjectBindingWhenSourceFieldIsOptionalButTargetRequiresIt() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                applicantProperties("integer", false),
                                List.of("score"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
    }

    @Test
    void rejectsObjectBindingWhenNestedRequiredFieldTypeDoesNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                applicantProperties("string", false),
                                List.of("score", "tier"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
    }

    @Test
    void acceptsNodeConfigWhenItMatchesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "strict",
                "enabled", true
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsStructuredConfigExpressionWhenPureContextReferenceMatchesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", Map.of("kind", "expression", "expr", "ctx.threshold"),
                "mode", "strict"
        ), graphInputSchema(
                Map.of("threshold", Map.of("type", "integer")),
                List.of("threshold")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsStructuredConfigExpressionWhenPureContextReferenceTypeDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", Map.of("kind", "expression", "expr", "ctx.threshold"),
                "mode", "strict"
        ), graphInputSchema(
                Map.of("threshold", Map.of("type", "string")),
                List.of("threshold")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/threshold/expr");
                    assertThat(diagnostic.message()).contains("ctx.threshold").contains("string").contains("integer");
                });
    }

    @Test
    void rejectsNodeConfigWhenRequiredConfigIsMissing() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.required");
                    assertThat(diagnostic.target()).contains("mode");
                });
    }

    @Test
    void rejectsNodeConfigWhenTypeOrEnumDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", "high",
                "mode", "experimental"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.config.typeMismatch", "visual.config.enumMismatch");
    }

    @Test
    void rejectsNodeConfigWhenAdditionalPropertiesAreForbidden() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "strict",
                "shadowMode", true
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.unknown");
                    assertThat(diagnostic.target()).contains("shadowMode");
                });
    }

    @Test
    void rejectsRawSecretInDraftWithoutEchoingValue() {
        String rawSecret = "Bearer draft-secret-token";
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "strict",
                "authorization", rawSecret
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.secret.raw");
                    assertThat(diagnostic.message()).doesNotContain(rawSecret);
                    assertThat(diagnostic.target()).contains("authorization");
                });
    }

    @Test
    void rejectsEdgeWhenSourcePathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.contextPath("score"),
                new GraphDraft.DraftEdge("missing", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "missing"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.unknownSourcePath"));
    }

    @Test
    void rejectsEdgeWhenPortTypesDoNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.contextPath("score"),
                new GraphDraft.DraftEdge("segment-score", "data",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "segment"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.typeMismatch"));
    }

    @Test
    void rejectsCyclicEdges() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "cyclic",
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
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(
                        new GraphDraft.DraftEdge("facts", "data",
                                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                                new GraphDraft.Endpoint("eligibility", "inputs", "score")),
                        new GraphDraft.DraftEdge("rule-ref", "data",
                                new GraphDraft.Endpoint("eligibility", "output", "ruleId"),
                                new GraphDraft.Endpoint("fetchApplicant", "params", "applicantId"))
                ),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo("visual.edge.cycle"));
    }

    @Test
    void rejectsCyclesCreatedByImplicitNodePathBindings() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericPassLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "implicitCycle",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "passA",
                                "risk:numericPass",
                                "",
                                Map.of("value", GraphDraft.Binding.nodePath("passB", "output", "value")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "passB",
                                "risk:numericPass",
                                "",
                                Map.of("value", GraphDraft.Binding.nodePath("passA", "output", "value")),
                                Map.of(),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("passA", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.cycle");
                    assertThat(diagnostic.message()).contains("dependencies").contains("acyclic");
                });
    }

    @Test
    void acceptsOutputSelectionWhenPathExistsOnSingleOutputPort() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(
                graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        ),
                        List.of("score", "amount")
                ),
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                new GraphDraft.OutputSelection("eligibility", "eligible")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsOutputSelectionWhenPathDoesNotExistOnSingleOutputPort() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(
                graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        ),
                        List.of("score", "amount")
                ),
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                new GraphDraft.OutputSelection("eligibility", "missing")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.output.unknownPath");
                    assertThat(diagnostic.message()).contains("missing").contains("eligibility");
                });
    }

    @Test
    void acceptsOutputSelectionWhenPortQualifiedPathExistsOnMultiOutputNode() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "multiOutputSelection",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "scoreFacts",
                        "risk:scoreFacts",
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreFacts", "facts.score")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    private static GraphDraft typedEligibilityDraft(GraphDraft.Binding scoreBinding,
                                                    GraphDraft.DraftEdge edge) {
        return typedEligibilityDraft(scoreBinding, List.of(edge));
    }

    private static GraphDraft typedEligibilityDraft(GraphDraft.Binding scoreBinding,
                                                    List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "typedEdge",
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
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", scoreBinding,
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static GraphDraft contextEligibilityDraft(SchemaEnvelope inputSchema,
                                                      Map<String, GraphDraft.Binding> inputs) {
        return contextEligibilityDraft(inputSchema, inputs, new GraphDraft.OutputSelection("eligibility", ""));
    }

    private static GraphDraft contextEligibilityDraft(SchemaEnvelope inputSchema,
                                                      Map<String, GraphDraft.Binding> inputs,
                                                      GraphDraft.OutputSelection output) {
        return new GraphDraft(
                "",
                "",
                0,
                "contextEligibility",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                output
        );
    }

    private static GraphDraft eligibilityToTransformConfigDraft(String eligibleExpression,
                                                                List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "configExpression",
                "",
                "",
                "",
                "",
                graphInputSchema(
                        Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
                        ),
                        List.of("score", "amount")
                ),
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
                                "mapResult",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of("eligible", eligibleExpression)),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("mapResult", "")
        );
    }

    private static SchemaEnvelope graphInputSchema(Map<String, Object> properties, List<String> required) {
        return SchemaEnvelope.object(properties, required);
    }

    private static GraphDraft multiOutputEligibilityDraft(GraphDraft.Binding scoreBinding) {
        return new GraphDraft(
                "",
                "",
                0,
                "multiOutputBinding",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "scoreFacts",
                                "risk:scoreFacts",
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
                                        "score", scoreBinding,
                                        "amount", GraphDraft.Binding.contextPath("amount")
                                ),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("scoreFacts", "facts", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }

    private static GraphDraft duplicateInputPathDraft(Map<String, GraphDraft.Binding> inputs) {
        return new GraphDraft(
                "",
                "",
                0,
                "duplicateInputPath",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "merge",
                        "risk:customerOrderMerge",
                        "",
                        inputs,
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static GraphDraft listCompatibilityDraft(GraphDraft.Binding itemsBinding,
                                                     GraphDraft.DraftEdge edge) {
        return new GraphDraft(
                "",
                "",
                0,
                "listCompatibility",
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
                                Map.of("items", itemsBinding),
                                Map.of(),
                                null
                        )
                ),
                List.of(edge),
                Map.of(),
                new GraphDraft.OutputSelection("listConsumer", "")
        );
    }

    private static GraphDraft enumCompatibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "enumCompatibility",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "decisionProducer",
                                "risk:decisionProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "decisionConsumer",
                                "risk:decisionConsumer",
                                "",
                                Map.of("decision", GraphDraft.Binding.nodePath(
                                        "decisionProducer",
                                        "output",
                                        "decision",
                                        "inputs",
                                        "decision")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("decision", "data",
                        new GraphDraft.Endpoint("decisionProducer", "output", "decision"),
                        new GraphDraft.Endpoint("decisionConsumer", "inputs", "decision"))),
                Map.of(),
                new GraphDraft.OutputSelection("decisionConsumer", "")
        );
    }

    private static GraphDraft objectCompatibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "objectCompatibility",
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
                                Map.of("applicant", GraphDraft.Binding.nodePath(
                                        "applicantProducer",
                                        "output",
                                        "applicant",
                                        "inputs",
                                        "applicant")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("applicant", "data",
                        new GraphDraft.Endpoint("applicantProducer", "output", "applicant"),
                        new GraphDraft.Endpoint("applicantConsumer", "inputs", "applicant"))),
                Map.of(),
                new GraphDraft.OutputSelection("applicantConsumer", "")
        );
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

    private static GraphDraft configurablePolicyDraft(Map<String, Object> config) {
        return configurablePolicyDraft(config, null);
    }

    private static GraphDraft configurablePolicyDraft(Map<String, Object> config, SchemaEnvelope inputSchema) {
        return new GraphDraft(
                "",
                "",
                0,
                "configurablePolicy",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:configurablePolicy",
                        "",
                        Map.of(),
                        config,
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static GraphDraft nestedApplicantEligibilityDraft(GraphDraft.Binding scoreBinding,
                                                             GraphDraft.DraftEdge edge) {
        return nestedApplicantEligibilityDraft(
                Map.of(
                        "applicant.score", scoreBinding
                ),
                List.of(edge));
    }

    private static GraphDraft nestedApplicantEligibilityDraft(Map<String, GraphDraft.Binding> inputs,
                                                             List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "nestedTargetPath",
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
                                Map.of("applicantId", GraphDraft.Binding.contextPath("applicantId")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "eligibility",
                                "risk:nestedApplicantEligibility",
                                "",
                                inputs,
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", "")
        );
    }
}
