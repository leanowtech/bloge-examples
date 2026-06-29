package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

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
                List.of(edge),
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

    private static GraphDraft configurablePolicyDraft(Map<String, Object> config) {
        return new GraphDraft(
                "",
                "",
                0,
                "configurablePolicy",
                "",
                "",
                "",
                "",
                null,
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
