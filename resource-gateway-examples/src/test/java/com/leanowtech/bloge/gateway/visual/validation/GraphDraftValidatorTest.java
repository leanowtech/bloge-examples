package com.leanowtech.bloge.gateway.visual.validation;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
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
    void rejectsUnsupportedDraftSchemaVersion() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft validDraft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));
        GraphDraft futureDraft = new GraphDraft(
                "bloge.visualGraphDraft.v2",
                validDraft.draftId(),
                validDraft.revision(),
                validDraft.graphName(),
                validDraft.tenantId(),
                validDraft.namespace(),
                validDraft.environment(),
                validDraft.status(),
                validDraft.inputSchema(),
                validDraft.nodes(),
                validDraft.edges(),
                validDraft.visualLayout(),
                validDraft.output(),
                validDraft.operatorFingerprints(),
                validDraft.revisionMetadata()
        );

        VisualValidationResult result = validator.validate(futureDraft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.schemaVersion.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/schemaVersion");
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
    void rejectsGraphInputSchemaWithInvalidRequiredShape() {
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
                        "required", "score"
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.schema.requiredInvalid");
                    assertThat(diagnostic.target()).isEqualTo("/inputSchema/schema/required");
                });
    }

    @Test
    void rejectsGraphInputSchemaWithInvalidObjectShape() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of(
                        "type", "object",
                        "properties", "score",
                        "additionalProperties", "false"
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.constant(720),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.propertiesInvalid",
                        "visual.schema.additionalPropertiesInvalid"
                );
    }

    @Test
    void rejectsGraphInputSchemaWithInvalidEnumShape() {
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
                                "decision", Map.of("type", "string", "enum", "APPROVE"),
                                "tier", Map.of("type", "string", "enum", List.of("LOW", 1, "LOW"))
                        )
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.constant(720),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains(
                        "visual.schema.enumInvalid",
                        "visual.schema.enumDuplicate",
                        "visual.schema.enumTypeMismatch"
                );
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
    void rejectsGraphInputSchemaWithUnsupportedJsonSchemaKeywords() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "score", Map.of("type", "integer", "minimum", 0),
                                "amount", Map.of("type", "number"),
                                "decision", Map.of("oneOf", List.of(
                                        Map.of("type", "string"),
                                        Map.of("type", "integer")
                                )),
                                "customer", Map.of("$ref", "#/$defs/Customer")
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
                .extracting("code")
                .contains(
                        "visual.schema.constraintUnsupported",
                        "visual.schema.compositionUnsupported",
                        "visual.schema.refUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/inputSchema/schema/properties/score/minimum",
                        "/inputSchema/schema/properties/decision/oneOf",
                        "/inputSchema/schema/properties/customer/$ref"
                );
    }

    @Test
    void rejectsGraphInputSchemaWithUnsupportedEnvelope() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = new SchemaEnvelope(
                "protobuf",
                "draft-07",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number")
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
                .extracting("code")
                .contains(
                        "visual.schema.formatUnsupported",
                        "visual.schema.versionUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
                        "/inputSchema/format",
                        "/inputSchema/version"
                );
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
    void acceptsCanonicalizedEdgeKind() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge("score", " DATA ",
                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                new GraphDraft.Endpoint("eligibility", "inputs", "score"));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "score"),
                edge);

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(edge.kind()).isEqualTo("data");
    }

    @Test
    void rejectsUnsupportedEdgeKind() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                Map.of(
                        "score", GraphDraft.Binding.contextPath("score"),
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                List.of(new GraphDraft.DraftEdge("control", "control",
                        new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score"))));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.kindUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/kind");
                });
    }

    @Test
    void rejectsDuplicateEdgeIds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                Map.of(
                        "score", GraphDraft.Binding.nodePath(
                                "fetchApplicant", "payload", "score", "inputs", "score"),
                        "amount", GraphDraft.Binding.nodePath(
                                "fetchApplicant", "payload", "score", "inputs", "amount")
                ),
                List.of(
                        new GraphDraft.DraftEdge("score-edge", "data",
                                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                                new GraphDraft.Endpoint("eligibility", "inputs", "score")),
                        new GraphDraft.DraftEdge("score-edge", "data",
                                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                                new GraphDraft.Endpoint("eligibility", "inputs", "amount"))
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.duplicateId");
                    assertThat(diagnostic.target()).isEqualTo("/edges/1/id");
                });
    }

    @Test
    void rejectsDuplicateEdgeConnections() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = typedEligibilityDraft(
                GraphDraft.Binding.nodePath("fetchApplicant", "payload", "score", "inputs", "score"),
                List.of(
                        new GraphDraft.DraftEdge("score-a", "data",
                                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                                new GraphDraft.Endpoint("eligibility", "inputs", "score")),
                        new GraphDraft.DraftEdge("score-b", "data",
                                new GraphDraft.Endpoint("fetchApplicant", "payload", "score"),
                                new GraphDraft.Endpoint("eligibility", "inputs", "score"))
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.duplicateConnection");
                    assertThat(diagnostic.target()).isEqualTo("/edges/1");
                });
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
    void acceptsContextPathBindingThroughTypedAdditionalPropertiesSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(dynamicAdditionalGraphInputSchema(Map.of("type", "integer")),
                Map.of(
                        "score", GraphDraft.Binding.contextPath("dynamicScore"),
                        "amount", GraphDraft.Binding.constant(1000)
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsContextPathBindingWhenAdditionalPropertiesSchemaTypeDoesNotMatch() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(dynamicAdditionalGraphInputSchema(Map.of("type", "string")),
                Map.of(
                        "score", GraphDraft.Binding.contextPath("dynamicScore"),
                        "amount", GraphDraft.Binding.constant(1000)
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
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
    void acceptsCanonicalizedInputBindingKind() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft.Binding amountBinding = new GraphDraft.Binding(
                " ContextPath ",
                null,
                "amount",
                "",
                "",
                "",
                "",
                "",
                Map.of()
        );
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "amount", Map.of("type", "number")
                ),
                List.of("amount")
        ), Map.of(
                "score", GraphDraft.Binding.constant(720),
                "amount", amountBinding
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(amountBinding.kind()).isEqualTo("contextPath");
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
    void rejectsExpressionBindingWhenStaticLiteralTypeDoesNotMatchTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(null, Map.of(
                "score", GraphDraft.Binding.expression("\"high\""),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("\"high\"").contains("integer");
                });
    }

    @Test
    void acceptsExpressionBindingWhenStaticLiteralMatchesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(null, Map.of(
                "score", GraphDraft.Binding.expression("701"),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
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
    void acceptsRootObjectBindingForRequiredPortFields() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = rootCustomerOrderDraft(customerOrderInputSchema(
                Map.of("id", Map.of("type", "string")),
                List.of("id")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsRootObjectBindingWhenStorageKeyIsPortName() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = rootCustomerOrderDraft(
                "customer",
                customerOrderInputSchema(
                        Map.of("id", Map.of("type", "string")),
                        List.of("id")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsRootObjectBindingFromNodeOutputPort() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.rootObjectPortLibrary()));
        GraphDraft draft = nodeRootCustomerOrderDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsRootObjectBindingWhenSourceObjectMissesRequiredTargetField() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.duplicateInputPathLibrary()));
        GraphDraft draft = rootCustomerOrderDraft(customerOrderInputSchema(
                Map.of("name", Map.of("type", "string")),
                List.of("name")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch")
                .doesNotContain("visual.input.required");
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
    void rejectsObjectTemplateBindingToScalarRequiredInput() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(null, Map.of(
                "score", new GraphDraft.Binding(
                        "objectTemplate",
                        null,
                        "",
                        "",
                        "",
                        "inputs",
                        "score",
                        "",
                        Map.of()),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("score").contains("integer");
                });
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
                    assertThat(diagnostic.message())
                            .contains("array<string>")
                            .contains("array<integer>")
                            .contains("at 'items'")
                            .contains("source type string cannot feed target type integer");
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
                    assertThat(diagnostic.message())
                            .contains("array<string>")
                            .contains("array<integer>")
                            .contains("at 'items'")
                            .contains("source type string cannot feed target type integer");
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
                        .contains("enum<LOW|HIGH>")
                        .contains("enum<APPROVE|REJECT>")
                        .contains("source enum value(s) [LOW, HIGH] are outside target enum [APPROVE, REJECT]"));
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
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target enum [APPROVE, REJECT] requires a finite source enum domain"));
    }

    @Test
    void acceptsObjectBindingWhenTargetRequiredFieldsArePresent() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                applicantProperties("integer", false),
                                List.of("score", "tier"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsObjectBindingWhenSourceDeclaresAdditionalFieldForStrictTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectCompatibilityLibrary(
                                applicantProperties("integer", true),
                                List.of("score", "tier"),
                                applicantProperties("integer", false),
                                List.of("score", "tier"))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("at 'segment'")
                        .contains("source object declares additional field 'segment'")
                        .contains("additionalProperties=false"));
    }

    @Test
    void rejectsObjectBindingWhenSourceAllowsDynamicAdditionalFieldsForStrictTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        objectCompatibilityLibraryWithApplicantSchemas(
                                applicantSchema(applicantProperties("integer", false),
                                        List.of("score", "tier"), true),
                                applicantSchema(applicantProperties("integer", false),
                                        List.of("score", "tier"), false))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source object allows undeclared additional fields")
                        .contains("additionalProperties=false"));
    }

    @Test
    void acceptsObjectBindingWhenAdditionalPropertySchemasAreCompatible() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        objectCompatibilityLibraryWithApplicantSchemas(
                                applicantSchema(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "integer")),
                                applicantSchema(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "number")))));
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
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("at 'tier'")
                        .contains("source object does not declare required field 'tier'"));
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
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("at 'tier'")
                        .contains("source object does not guarantee required field 'tier'"));
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
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("at 'score'")
                        .contains("source type string cannot feed target type integer"));
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
    void rejectsStructuredConfigExpressionWhenStaticLiteralTypeDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", Map.of("kind", "expression", "expr", "\"high\""),
                "mode", "strict"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/threshold/expr");
                    assertThat(diagnostic.message()).contains("\"high\"").contains("integer");
                });
    }

    @Test
    void acceptsStructuredConfigExpressionWhenStaticLiteralMatchesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", Map.of("kind", "expression", "expr", "700"),
                "mode", "strict"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsNestedConfigExpressionWhenNodeReferenceMatchesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedConfigPolicyLibrary()));
        GraphDraft draft = nestedConfigPolicyDraft("fetchApplicant.output.payload.score");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNestedConfigExpressionWhenNodeReferenceTypeDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLoanApplicantResourceAndLibrary(
                        VisualCatalogTestSupport.nestedConfigPolicyLibrary()));
        GraphDraft draft = nestedConfigPolicyDraft("fetchApplicant.output.payload.segment");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/1/config/limits/threshold/expr");
                    assertThat(diagnostic.message()).contains("segment").contains("string").contains("integer");
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
    void rejectsObjectTemplateConfigWhenTargetSchemaIsScalar() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.configurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", Map.of(
                        "kind", "objectTemplate",
                        "fields", Map.of()),
                "mode", "strict"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/threshold");
                    assertThat(diagnostic.message()).contains("threshold").contains("integer");
                });
    }

    @Test
    void rejectsNodeConfigWhenStandardJsonSchemaEnumDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(standardEnumConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "experimental"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.enumMismatch");
                    assertThat(diagnostic.message()).contains("strict").contains("relaxed");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/mode");
                });
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
        return typedEligibilityDraft(
                Map.of(
                        "score", scoreBinding,
                        "amount", GraphDraft.Binding.contextPath("amount")
                ),
                edges);
    }

    private static GraphDraft typedEligibilityDraft(Map<String, GraphDraft.Binding> eligibilityInputs,
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

    private static SchemaEnvelope dynamicAdditionalGraphInputSchema(Object additionalProperties) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", additionalProperties
        ));
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

    private static GraphDraft rootCustomerOrderDraft(SchemaEnvelope inputSchema) {
        return rootCustomerOrderDraft("", inputSchema);
    }

    private static GraphDraft rootCustomerOrderDraft(String customerInputKey, SchemaEnvelope inputSchema) {
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put(customerInputKey, new GraphDraft.Binding(
                "contextPath",
                null,
                "customer",
                "",
                "",
                "customer",
                "",
                "",
                Map.of()
        ));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        return new GraphDraft(
                "",
                "",
                0,
                "rootCustomerOrder",
                "",
                "",
                "",
                "",
                inputSchema,
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

    private static GraphDraft nodeRootCustomerOrderDraft() {
        Map<String, GraphDraft.Binding> inputs = new LinkedHashMap<>();
        inputs.put("customer", GraphDraft.Binding.nodePath(
                "customerFacts",
                "customer",
                "",
                "customer",
                ""));
        inputs.put("order.id", GraphDraft.Binding.contextPath("orderId", "order", "id"));
        return new GraphDraft(
                "",
                "",
                0,
                "nodeRootCustomerOrder",
                "",
                "",
                "",
                "",
                customerOrderInputSchema(
                        Map.of("id", Map.of("type", "string")),
                        List.of("id")),
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
                                inputs,
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("customer", "data",
                        new GraphDraft.Endpoint("customerFacts", "customer", ""),
                        new GraphDraft.Endpoint("merge", "customer", ""))),
                Map.of(),
                new GraphDraft.OutputSelection("merge", "")
        );
    }

    private static SchemaEnvelope customerOrderInputSchema(Map<String, Object> customerProperties,
                                                           List<String> customerRequired) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("customer", Map.of(
                "type", "object",
                "properties", customerProperties,
                "required", customerRequired,
                "additionalProperties", false
        ));
        properties.put("orderId", Map.of("type", "string"));
        return SchemaEnvelope.object(properties, List.of("customer", "orderId"));
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

    private static Map<String, Object> applicantSchema(Map<String, Object> properties,
                                                       List<String> required,
                                                       Object additionalProperties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", additionalProperties);
        return schema;
    }

    private static OperatorLibrary objectCompatibilityLibraryWithApplicantSchemas(
            Map<String, Object> sourceApplicantSchema,
            Map<String, Object> targetApplicantSchema) {
        Map<String, Object> sourceOutputProperties = new LinkedHashMap<>();
        sourceOutputProperties.put("applicant", sourceApplicantSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:applicantObjectProducer",
                "1.0.0",
                new OperatorDefinition.Display("Applicant object producer",
                        "Produces applicant facts as a nested object.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceOutputProperties, List.of()),
                                true,
                                "Applicant object output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskApplicantObjectProducer", Map.of()),
                List.of()
        );

        Map<String, Object> targetInputProperties = new LinkedHashMap<>();
        targetInputProperties.put("applicant", targetApplicantSchema);
        Map<String, Object> targetOutputProperties = new LinkedHashMap<>();
        targetOutputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:applicantObjectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Applicant object consumer",
                        "Consumes applicant facts as a nested object.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetInputProperties, List.of("applicant")),
                                true,
                                "Applicant object input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(targetOutputProperties, List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "true")
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-object-compatibility",
                "Object compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
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

    private static GraphDraft nestedConfigPolicyDraft(String thresholdExpression) {
        return new GraphDraft(
                "",
                "",
                0,
                "nestedConfigPolicy",
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
                                Map.of("applicantId", GraphDraft.Binding.constant("applicant-1")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "policy",
                                "risk:nestedConfigPolicy",
                                "",
                                Map.of(),
                                Map.of("limits", Map.of(
                                        "threshold", Map.of(
                                                "kind", "expression",
                                                "expr", thresholdExpression
                                        ),
                                        "mode", "strict"
                                )),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );
    }

    private static OperatorLibrary standardEnumConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("mode", Map.of("type", "string", "enum", List.of("strict", "relaxed")));

        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:configurablePolicy",
                "1.0.0",
                new OperatorDefinition.Display("Configurable policy",
                        "Evaluates policy behavior controlled by configSchema.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(configProperties, List.of("threshold", "mode")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("accepted", "true")
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-configurable-policy",
                "Configurable policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
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
