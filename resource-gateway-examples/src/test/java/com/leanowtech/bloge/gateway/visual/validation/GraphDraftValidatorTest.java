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
    void rejectsUnsupportedDraftStatus() {
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
        GraphDraft lockedDraft = new GraphDraft(
                validDraft.schemaVersion(),
                validDraft.draftId(),
                validDraft.revision(),
                validDraft.graphName(),
                validDraft.tenantId(),
                validDraft.namespace(),
                validDraft.environment(),
                "locked",
                validDraft.inputSchema(),
                validDraft.nodes(),
                validDraft.edges(),
                validDraft.visualLayout(),
                validDraft.output(),
                validDraft.operatorFingerprints(),
                validDraft.revisionMetadata()
        );

        VisualValidationResult result = validator.validate(lockedDraft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.draft.status.unsupported");
                    assertThat(diagnostic.target()).isEqualTo("/status");
                    assertThat(diagnostic.message()).contains("LOCKED");
                });
    }

    @Test
    void rejectsDraftIdentifiersThatCannotRenderAsDslIdentifiers() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "graph",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "node",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.constant(720),
                                "amount", GraphDraft.Binding.constant(1000)
                        ),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("node", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().endsWith(".invalid"))
                .extracting("code", "target")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("visual.graph.name.invalid", "/graphName"),
                        org.assertj.core.groups.Tuple.tuple("visual.node.id.invalid", "/nodes/0/id")
                );
    }

    @Test
    void rejectsGraphInputSchemaAndBindingsThatCannotRenderAsDslPathSegments() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        SchemaEnvelope inputSchema = graphInputSchema(Map.of(
                "customer-id", Map.of("type", "integer"),
                "amount", Map.of("type", "number")
        ), List.of("customer-id", "amount"));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.contextPath("customer-id"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code", "target")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("visual.inputSchema.dslField.invalid",
                                "/inputSchema/schema/properties/customer-id"),
                        org.assertj.core.groups.Tuple.tuple("visual.binding.pathSegment.invalid",
                                "/nodes/0/inputs/score/path")
                );
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
    void acceptsGraphInputSchemaWithStringPattern() {
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
                                "customerCode", Map.of("type", "string", "pattern", "^[A-Z]{2}\\d{4}$")
                        ),
                        "required", List.of("score", "amount"),
                        "additionalProperties", false
                ));
        GraphDraft draft = contextEligibilityDraft(inputSchema, Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
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
                                "score", Map.of("type", "integer"),
                                "amount", Map.of("type", "number"),
                                "customerCode", Map.of("type", "string", "pattern", "^[A-Z]+$"),
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
                        "visual.schema.compositionUnsupported",
                        "visual.schema.refUnsupported"
                );
        assertThat(result.diagnostics())
                .extracting("target")
                .contains(
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
    void acceptsExplicitDependencyEdgeWithoutDataBinding() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge("prepare-before-publish", "dependsOn",
                new GraphDraft.Endpoint("prepareFacts", "", ""),
                new GraphDraft.Endpoint("publishFacts", "", ""));
        GraphDraft draft = scoreFactsDependencyDraft(List.of(edge), "publishFacts");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(edge.kind()).isEqualTo("dependency");
    }

    @Test
    void warnsAboutNodesThatDoNotReachSelectedOutput() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = scoreFactsDependencyDraft(List.of(), "publishFacts");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.level()).isEqualTo("WARNING");
                    assertThat(diagnostic.code()).isEqualTo("visual.graph.unreachableNode");
                    assertThat(diagnostic.message()).contains("prepareFacts").contains("publishFacts");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0");
                });
    }

    @Test
    void treatsDependencyEdgesAsOutputReachability() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = scoreFactsDependencyDraft(List.of(
                new GraphDraft.DraftEdge("prepare-before-publish", "dependency",
                        new GraphDraft.Endpoint("prepareFacts", "", ""),
                        new GraphDraft.Endpoint("publishFacts", "", ""))
        ), "publishFacts");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics())
                .noneSatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.graph.unreachableNode"));
    }

    @Test
    void rejectsDependencyEdgeTargetingNonNodeDslBlock() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "unsupportedDependencyTarget",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "prepareFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "mapResult",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of("result", "prepareFacts.output.facts")),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("prepare-before-map", "dependency",
                        new GraphDraft.Endpoint("prepareFacts", "", ""),
                        new GraphDraft.Endpoint("mapResult", "", ""))),
                Map.of(),
                new GraphDraft.OutputSelection("mapResult", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.dependencyTargetUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/target/nodeId");
                });
    }

    @Test
    void rejectsCyclesCreatedByDependencyEdges() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = scoreFactsDependencyDraft(List.of(
                new GraphDraft.DraftEdge("prepare-before-publish", "dependency",
                        new GraphDraft.Endpoint("prepareFacts", "", ""),
                        new GraphDraft.Endpoint("publishFacts", "", "")),
                new GraphDraft.DraftEdge("publish-before-prepare", "dependency",
                        new GraphDraft.Endpoint("publishFacts", "", ""),
                        new GraphDraft.Endpoint("prepareFacts", "", ""))
        ), "publishFacts");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo("visual.edge.cycle"));
    }

    @Test
    void acceptsRouteEdgesFromBranchLoweredOperator() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge("route-physical", "branch",
                new GraphDraft.Endpoint("routeByType", "route", ""),
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                "physical");
        GraphDraft draft = routeDraft(List.of(edge));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(edge.kind()).isEqualTo("route");
        assertThat(edge.source().port()).isEmpty();
        assertThat(edge.condition()).isEqualTo("physical");
    }

    @Test
    void rejectsRouteConditionThatDoesNotMatchSelectorType() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routeDraft(List.of(new GraphDraft.DraftEdge("route-true", "route",
                new GraphDraft.Endpoint("routeByType", "", ""),
                new GraphDraft.Endpoint("physicalFacts", "", ""),
                "true")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.routeConditionTypeMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/condition");
                });
    }

    @Test
    void rejectsRouteConditionOutsideSelectorEnumDomain() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(selectorRouteLibrary(
                        Map.of("type", "string", "enum", List.of("physical", "digital")))));
        GraphDraft draft = routeDraft(selectorInputSchema(
                Map.of("type", "string", "enum", List.of("physical", "digital"))),
                List.of(new GraphDraft.DraftEdge("route-virtual", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "virtual")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.routeConditionTypeMismatch");
                    assertThat(diagnostic.message()).contains("virtual").contains("routeByType");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/condition");
                });
    }

    @Test
    void acceptsBooleanAndNullRouteConditionsForNullableSelector() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(selectorRouteLibrary(
                        Map.of("type", List.of("boolean", "null")))));
        GraphDraft draft = routeDraft(selectorInputSchema(Map.of("type", List.of("boolean", "null"))),
                List.of(
                        new GraphDraft.DraftEdge("route-true", "route",
                                new GraphDraft.Endpoint("routeByType", "", ""),
                                new GraphDraft.Endpoint("physicalFacts", "", ""),
                                "true"),
                        new GraphDraft.DraftEdge("route-null", "route",
                                new GraphDraft.Endpoint("routeByType", "", ""),
                                new GraphDraft.Endpoint("genericFacts", "", ""),
                                "null")
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics())
                .noneSatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.routeConditionTypeMismatch"));
    }

    @Test
    void rejectsNumericRouteConditionOutsideSelectorBounds() {
        Map<String, Object> selectorSchema = Map.of("type", "integer", "minimum", 1, "maximum", 3);
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(selectorRouteLibrary(selectorSchema)));
        GraphDraft draft = routeDraft(selectorInputSchema(selectorSchema),
                List.of(new GraphDraft.DraftEdge("route-five", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "5")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.routeConditionTypeMismatch"));
    }

    @Test
    void treatsRouteEdgeSourceAsOutputReachable() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routeDraft(List.of(
                new GraphDraft.DraftEdge("route-physical", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "physical"),
                new GraphDraft.DraftEdge("route-generic", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("genericFacts", "", ""),
                        "otherwise")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics())
                .noneSatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.graph.unreachableNode");
                    assertThat(diagnostic.message()).contains("routeByType");
                });
    }

    @Test
    void rejectsRouteEdgesFromNonBranchOperator() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routeDraft(List.of(new GraphDraft.DraftEdge("bad-route", "route",
                new GraphDraft.Endpoint("physicalFacts", "route", ""),
                new GraphDraft.Endpoint("genericFacts", "route", ""),
                "otherwise")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.routeSourceUnsupported");
                    assertThat(diagnostic.target()).isEqualTo("/edges/0/source/nodeId");
                });
    }

    @Test
    void rejectsDuplicateRouteConditionsOnSameBranchNode() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routeDraft(List.of(
                new GraphDraft.DraftEdge("route-physical", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "physical"),
                new GraphDraft.DraftEdge("route-physical-again", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("genericFacts", "", ""),
                        "physical")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.edge.routeConditionDuplicate"));
    }

    @Test
    void rejectsSemanticallyDuplicateQuotedRouteConditionsOnSameBranchNode() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft draft = routeDraft(List.of(
                new GraphDraft.DraftEdge("route-physical", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "physical"),
                new GraphDraft.DraftEdge("route-physical-quoted", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("genericFacts", "", ""),
                        "\"physical\"")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.edge.routeConditionDuplicate");
                    assertThat(diagnostic.target()).isEqualTo("/edges/1/condition");
                });
    }

    @Test
    void rejectsOutputSelectionOnControlOnlyBranchNode() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(VisualCatalogTestSupport.routeLibrary()));
        GraphDraft base = routeDraft(List.of(
                new GraphDraft.DraftEdge("route-physical", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("physicalFacts", "", ""),
                        "physical"),
                new GraphDraft.DraftEdge("route-generic", "route",
                        new GraphDraft.Endpoint("routeByType", "", ""),
                        new GraphDraft.Endpoint("genericFacts", "", ""),
                        "otherwise")
        ));
        GraphDraft draft = new GraphDraft(
                base.schemaVersion(),
                base.draftId(),
                base.revision(),
                base.graphName(),
                base.tenantId(),
                base.namespace(),
                base.environment(),
                base.status(),
                base.inputSchema(),
                base.nodes(),
                base.edges(),
                base.visualLayout(),
                new GraphDraft.OutputSelection("routeByType", ""),
                base.operatorFingerprints(),
                base.revisionMetadata()
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.output.unselectableNode");
                    assertThat(diagnostic.target()).isEqualTo("/output/nodeId");
                });
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
    void rejectsTransformAssignmentKeysThatCannotRenderAsDslFields() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "invalidTransformAssignmentKey",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "mapResult",
                        "bloge:transform",
                        "",
                        Map.of(),
                        Map.of("assignments", Map.of(
                                "customer-id", "ctx.customerId",
                                "mode", "\"strict\""
                        )),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("mapResult", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "visual.transform.assignmentKey.invalid".equals(diagnostic.code()))
                .extracting("target")
                .containsExactlyInAnyOrder(
                        "/nodes/0/config/assignments/customer-id",
                        "/nodes/0/config/assignments/mode"
                );
    }

    @Test
    void rejectsDecisionTableKeysThatCannotRenderAsDslFields() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "invalidDecisionTableKeys",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "decision",
                        "bloge:decisionTable",
                        "",
                        Map.of(),
                        Map.of(
                                "inputs", Map.of("mode", "score"),
                                "rules", List.of(Map.of(
                                        "conditions", "score: score >= 700",
                                        "output", Map.of(
                                                "customer-id", "C-1",
                                                "otherwise", false,
                                                "details", Map.of("risk-band", "low")
                                        )
                                ))
                        ),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("decision", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> diagnostic.code().startsWith("visual.decisionTable."))
                .extracting("target")
                .containsExactlyInAnyOrder(
                        "/nodes/0/config/inputs/mode",
                        "/nodes/0/config/rules/0/output/customer-id",
                        "/nodes/0/config/rules/0/output/otherwise",
                        "/nodes/0/config/rules/0/output/details/risk-band"
                );
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
    void rejectsContextPathBindingWhenDynamicPropertyNameViolatesGraphInputSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(
                dynamicAdditionalGraphInputSchema(Map.of("type", "integer"), Map.of("pattern", "^risk[A-Z].*")),
                Map.of(
                        "score", GraphDraft.Binding.contextPath("badScore"),
                        "amount", GraphDraft.Binding.constant(1000)
                ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownContextPath");
                    assertThat(diagnostic.message()).contains("ctx.badScore");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/score");
                });
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
    void rejectsDraftWhenOperatorFingerprintSnapshotReferencesDeletedNode() {
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        GraphDraftValidator validator = new GraphDraftValidator(catalog);
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "score", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("score", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount")
        )).withOperatorFingerprints(Map.of(
                "eligibility", catalog.find("risk:eligibility").orElseThrow().fingerprint(),
                "deletedNode", "sha256:deleted-definition"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.operator.fingerprintUnknownNode");
                    assertThat(diagnostic.message()).contains("deletedNode");
                    assertThat(diagnostic.target()).isEqualTo("/operatorFingerprints/deletedNode");
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
    void acceptsConstantNullBindingWhenTargetTypeIsNullable() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        scoreTypeCompatibilityLibrary("integer", List.of("integer", "null"))));
        GraphDraft draft = scoreConstantDraft(GraphDraft.Binding.constant(null));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsConstantBindingWhenStringValueViolatesTargetPattern() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringPatternCompatibilityLibrary("^[A-Z]{2}\\d{4}$",
                                "^[A-Z]{2}\\d{4}$")));
        GraphDraft draft = stringPatternConstantDraft("bad-code");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("Constant").contains("customerId").contains("string");
                });
    }

    @Test
    void rejectsConstantBindingWhenStringValueViolatesTargetFormat() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringFormatCompatibilityLibrary("email", "email")));
        GraphDraft draft = stringPatternConstantDraft("not-an-email");

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("Constant").contains("customerId").contains("string");
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
    void rejectsExpressionBindingWhenContextReferenceUsesDslUnsafePathSegment() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "customer", Map.of("type", "integer"),
                        "amount", Map.of("type", "number")
                ),
                List.of("customer", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.customer-id"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.expression.pathSegment.invalid");
                    assertThat(diagnostic.message()).contains("ctx.customer-id").contains("customer-id");
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
    void rejectsExpressionBindingWhenPureContextArrayIndexReferenceTypeDoesNotMatchTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "scores", Map.of("type", "array", "items", Map.of("type", "string")),
                        "amount", Map.of("type", "number")
                ),
                List.of("scores", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.scores[0]"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("ctx.scores[0]").contains("string")
                            .contains("integer");
                });
    }

    @Test
    void rejectsExpressionBindingWhenContextArrayIndexReferencePathDoesNotExist() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(graphInputSchema(
                Map.of(
                        "scores", Map.of("type", "array", "items", Map.of("type", "integer")),
                        "amount", Map.of("type", "number")
                ),
                List.of("scores", "amount")
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx.scores[0].value + 1"),
                "amount", GraphDraft.Binding.contextPath("amount")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownContextPath");
                    assertThat(diagnostic.message()).contains("ctx.scores.0.value");
                });
    }

    @Test
    void acceptsExpressionBindingWhenPureContextRootArrayIndexReferenceMatchesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.eligibilityLibrary("integer")));
        GraphDraft draft = contextEligibilityDraft(new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of("type", "array", "items", Map.of("type", "integer"))
        ), Map.of(
                "score", GraphDraft.Binding.expression("ctx[0]"),
                "amount", GraphDraft.Binding.constant(1000)
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
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
    void rejectsExpressionBindingWhenNodeReferenceUsesDslUnsafePathSegment() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = multiOutputEligibilityDraft(
                GraphDraft.Binding.expression("scoreFacts.output.facts.score-id"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.expression.pathSegment.invalid");
                    assertThat(diagnostic.message()).contains("scoreFacts.output.facts.score-id").contains("score-id");
                });
    }

    @Test
    void rejectsExpressionBindingWhenNodeReferenceIndexesNonArrayOutput() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer")));
        GraphDraft draft = multiOutputEligibilityDraft(
                GraphDraft.Binding.expression("scoreFacts.output.facts.score[0]"));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.unknownOutputPath");
                    assertThat(diagnostic.message()).contains("scoreFacts").contains("score.0");
                });
    }

    @Test
    void acceptsExpressionBindingWhenPureNodeRootArrayIndexReferenceMatchesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(rootArrayFactsLibrary()));
        GraphDraft draft = rootArrayFactsEligibilityDraft(
                GraphDraft.Binding.expression("rootFacts.output[0].score"),
                new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("rootFacts", "output", "0.score"),
                        new GraphDraft.Endpoint("eligibility", "inputs", "score")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
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
    void acceptsCompoundExpressionBindingWithCompactDecimalSubtraction() {
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
                "score", GraphDraft.Binding.expression("ctx.score-1.5"),
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
    void rejectsObjectTemplateFieldsThatCannotRenderAsDslObjectFields() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(nativeDynamicObjectPolicyLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "dynamicPayloadPolicy",
                "",
                "",
                "",
                "",
                graphInputSchema(Map.of(
                        "riskMode", Map.of("type", "string"),
                        "customerId", Map.of("type", "string")
                ), List.of("riskMode", "customerId")),
                List.of(new GraphDraft.DraftNode(
                        "policy",
                        "risk:dynamicObjectPolicy",
                        "",
                        Map.of("payload", new GraphDraft.Binding(
                                "objectTemplate",
                                null,
                                "",
                                "",
                                "",
                                "payload",
                                "",
                                "",
                                Map.of(
                                        "mode", GraphDraft.Binding.contextPath("riskMode"),
                                        "customer-id", GraphDraft.Binding.contextPath("customerId")
                                )
                        )),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("policy", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .filteredOn(diagnostic -> "visual.binding.objectTemplateField.invalid".equals(diagnostic.code()))
                .extracting("target")
                .containsExactlyInAnyOrder(
                        "/nodes/0/inputs/payload/fields/mode",
                        "/nodes/0/inputs/payload/fields/customer-id"
                );
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
    void acceptsContextPathBindingWithArrayIndexSegment() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericBoundsCompatibilityLibrary(0, 1000, 0, 1000)));
        SchemaEnvelope inputSchema = SchemaEnvelope.object(Map.of(
                "scores", Map.of("type", "array", "items", Map.of(
                        "type", "integer",
                        "minimum", 0,
                        "maximum", 1000))
        ), List.of("scores"));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "arrayContextBinding",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(new GraphDraft.DraftNode(
                        "scoreConsumer",
                        "risk:scoreConsumer",
                        "",
                        Map.of("score", GraphDraft.Binding.contextPath("scores.0")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreConsumer", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
        assertThat(result.diagnostics())
                .noneSatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.binding.pathSegment.invalid"));
    }

    @Test
    void acceptsNodePathBindingWithArrayIndexSegment() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayItemScoreLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "arrayNodeBinding",
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
                                "scoreConsumer",
                                "risk:scoreConsumer",
                                "",
                                Map.of("score", GraphDraft.Binding.nodePath(
                                        "listFacts",
                                        "output",
                                        "items.0",
                                        "inputs",
                                        "score")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items.0"),
                        new GraphDraft.Endpoint("scoreConsumer", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("scoreConsumer", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
        assertThat(result.diagnostics())
                .noneSatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.binding.pathSegment.invalid"));
    }

    @Test
    void acceptsExpressionNodeReferenceWithArrayIndexSegmentAndMatchingDataEdge() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayItemScoreLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "arrayExpressionBinding",
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
                                "scoreConsumer",
                                "risk:scoreConsumer",
                                "",
                                Map.of("score", GraphDraft.Binding.expression("listFacts.output.items[0]")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items.0"),
                        new GraphDraft.Endpoint("scoreConsumer", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("scoreConsumer", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).as(result.diagnostics().toString()).isTrue();
    }

    @Test
    void acceptsNodePathBindingWhenArrayItemBoundsFitTargetBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listItemBoundsCompatibilityLibrary(2, 3, 1, 4)));
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
    void rejectsNodePathBindingWhenArrayItemBoundsAreWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listItemBoundsCompatibilityLibrary(0, 5, 1, 4)));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source minItems 0 is weaker than target minItems 1"));
    }

    @Test
    void acceptsNodePathBindingWhenArrayPrefixItemsAreGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listPrefixItemsCompatibilityLibrary(
                                integerStringPrefixItems(),
                                integerStringPrefixItems())));
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
    void rejectsNodePathBindingWhenArrayPrefixItemsAreNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listPrefixItemsCompatibilityLibrary(
                                null,
                                integerStringPrefixItems())));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("prefixItems/0")
                        .contains("source type string cannot feed target type integer"));
    }

    @Test
    void rejectsConstantBindingWhenArrayPrefixItemViolatesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listPrefixItemsCompatibilityLibrary(
                                null,
                                integerStringPrefixItems())));
        GraphDraft draft = listConstantDraft(GraphDraft.Binding.constant(List.of("bad", "route")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("items").contains("array");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/items");
                });
    }

    @Test
    void acceptsNodePathBindingWhenArrayUniqueItemsFitTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listUniqueItemsCompatibilityLibrary(true, true)));
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
    void rejectsNodePathBindingWhenArrayUniqueItemsAreNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listUniqueItemsCompatibilityLibrary(false, true)));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires uniqueItems=true but source does not guarantee uniqueness"));
    }

    @Test
    void acceptsNodePathBindingWhenArrayContainsFitsTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listContainsCompatibilityLibrary(2, 1)));
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
    void rejectsNodePathBindingWhenArrayContainsIsWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listContainsCompatibilityLibrary(1, 2)));
        GraphDraft draft = listCompatibilityDraft(
                GraphDraft.Binding.nodePath("listFacts", "output", "items",
                        "inputs", "items"),
                new GraphDraft.DraftEdge("items", "data",
                        new GraphDraft.Endpoint("listFacts", "output", "items"),
                        new GraphDraft.Endpoint("listConsumer", "inputs", "items")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source minContains 1 is weaker than target minContains 2"));
    }

    @Test
    void rejectsConstantBindingWhenArrayContainsConstraintIsNotSatisfied() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.listContainsCompatibilityLibrary(1, 1)));
        GraphDraft draft = listConstantDraft(GraphDraft.Binding.constant(List.of("secondary")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("items").contains("array");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/items");
                });
    }

    @Test
    void acceptsNodePathBindingWhenObjectPropertyBoundsFitTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyBoundsCompatibilityLibrary(2, 3, 1, 4)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenObjectPropertyBoundsAreWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyBoundsCompatibilityLibrary(1, 5, 2, 4)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source minProperties 1 is weaker than target minProperties 2"));
    }

    @Test
    void rejectsConstantBindingWhenObjectPropertyCountViolatesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyBoundsCompatibilityLibrary(2, 3, 2, 4)));
        GraphDraft draft = objectConstantDraft(GraphDraft.Binding.constant(Map.of("status", "open")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("payload").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/payload");
                });
    }

    @Test
    void acceptsNodePathBindingWhenObjectPropertyNamesFitTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyNamesCompatibilityLibrary(
                                null,
                                false,
                                Map.of("pattern", "^label\\.[a-z]+$"),
                                true)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenObjectPropertyNamesAreNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyNamesCompatibilityLibrary(
                                null,
                                true,
                                Map.of("pattern", "^label\\.[a-z]+$"),
                                true)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires propertyNames"));
    }

    @Test
    void rejectsConstantBindingWhenObjectPropertyNameViolatesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPropertyNamesCompatibilityLibrary(
                                null,
                                true,
                                Map.of("pattern", "^label\\.[a-z]+$"),
                                true)));
        GraphDraft draft = objectConstantDraft(GraphDraft.Binding.constant(Map.of("bad", "open")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("payload").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/payload");
                });
    }

    @Test
    void acceptsNodePathBindingWhenObjectPatternPropertiesFitTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPatternPropertiesCompatibilityLibrary(
                                null,
                                false,
                                Map.of("^metric\\.", Map.of("type", "integer")),
                                true)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenObjectPatternPropertiesAreNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPatternPropertiesCompatibilityLibrary(
                                null,
                                true,
                                Map.of("^metric\\.", Map.of("type", "integer")),
                                true)));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires patternProperties"));
    }

    @Test
    void rejectsConstantBindingWhenObjectPatternPropertyValueViolatesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectPatternPropertiesCompatibilityLibrary(
                                null,
                                true,
                                Map.of("^metric\\.", Map.of("type", "integer")),
                                true)));
        GraphDraft draft = objectConstantDraft(GraphDraft.Binding.constant(Map.of("metric.score", "high")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("payload").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/payload");
                });
    }

    @Test
    void acceptsNodePathBindingWhenObjectDependentRequiredIsGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentRequiredCompatibilityLibrary(
                                Map.of("cardNumber", List.of("billingZip")),
                                List.of(),
                                Map.of("cardNumber", List.of("billingZip")),
                                List.of())));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenObjectDependentRequiredIsNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentRequiredCompatibilityLibrary(
                                null,
                                List.of(),
                                Map.of("cardNumber", List.of("billingZip")),
                                List.of())));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires dependentRequired"));
    }

    @Test
    void rejectsConstantBindingWhenObjectDependentRequiredDependencyIsMissing() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentRequiredCompatibilityLibrary(
                                null,
                                List.of(),
                                Map.of("cardNumber", List.of("billingZip")),
                                List.of())));
        GraphDraft draft = objectConstantDraft(GraphDraft.Binding.constant(Map.of(
                "cardNumber", "4111111111111111"
        )));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("payload").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/payload");
                });
    }

    @Test
    void acceptsNodePathBindingWhenObjectDependentSchemasIsGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentSchemasCompatibilityLibrary(
                                cardNumberRequiresBillingZipDependentSchemas(),
                                cardNumberRequiresBillingZipDependentSchemas())));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenObjectDependentSchemasIsNotGuaranteed() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentSchemasCompatibilityLibrary(
                                null,
                                cardNumberRequiresBillingZipDependentSchemas())));
        GraphDraft draft = objectCompatibilityDraft(
                GraphDraft.Binding.nodePath("objectFacts", "output", "payload",
                        "inputs", "payload"),
                new GraphDraft.DraftEdge("payload", "data",
                        new GraphDraft.Endpoint("objectFacts", "output", "payload"),
                        new GraphDraft.Endpoint("objectConsumer", "inputs", "payload")));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires dependentSchemas"));
    }

    @Test
    void rejectsConstantBindingWhenObjectDependentSchemasDependencyIsMissing() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.objectDependentSchemasCompatibilityLibrary(
                                null,
                                cardNumberRequiresBillingZipDependentSchemas())));
        GraphDraft draft = objectConstantDraft(GraphDraft.Binding.constant(Map.of(
                "cardNumber", "4111111111111111"
        )));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("payload").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/payload");
                });
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
    void acceptsConstBindingWhenSourceConstMatchesTargetConst() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.constCompatibilityLibrary("APPROVE", "APPROVE")));
        GraphDraft draft = enumCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsConstBindingWhenSourceConstDiffersFromTargetConst() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.constCompatibilityLibrary("REJECT", "APPROVE")));
        GraphDraft draft = enumCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("enum<REJECT>")
                        .contains("enum<APPROVE>")
                        .contains("source enum value(s) [REJECT] are outside target enum [APPROVE]"));
    }

    @Test
    void acceptsNumericBindingWhenSourceBoundsFitTargetBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericBoundsCompatibilityLibrary(300, 850, 0, 900)));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNodePathBindingWhenNullableSourceFeedsNonNullableTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        scoreTypeCompatibilityLibrary(List.of("integer", "null"), "integer")));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source may produce null")
                        .contains("target integer does not allow null"));
    }

    @Test
    void acceptsNodePathBindingWhenNullableSourceFeedsNullableTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        scoreTypeCompatibilityLibrary(List.of("integer", "null"), List.of("integer", "null"))));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsNodePathBindingThroughLocalDefinitionsReferences() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        scoreDefinitionsCompatibilityLibrary("integer", "integer")));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsNodePathBindingThroughLocalDefinitionObjectAllOf() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        scoreAllOfCompatibilityLibrary("integer", "integer")));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNumericBindingWhenSourceLowerBoundIsWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericBoundsCompatibilityLibrary(0, 850, 300, 900)));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source lower bound value >= 0 is weaker than target lower bound value >= 300"));
    }

    @Test
    void acceptsNumericBindingWhenSourceMultipleOfFitsTargetMultipleOf() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericMultipleOfCompatibilityLibrary(10, 5)));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsNumericBindingWhenSourceMultipleOfIsWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.numericMultipleOfCompatibilityLibrary(5, 10)));
        GraphDraft draft = numericBoundsCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source multipleOf 5 is weaker than target multipleOf 10"));
    }

    @Test
    void acceptsStringBindingWhenSourceLengthBoundsFitTargetBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringLengthCompatibilityLibrary(8, 12, 6, 16)));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsStringBindingWhenSourceMinLengthIsWeakerThanTarget() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringLengthCompatibilityLibrary(4, 12, 8, 16)));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source minLength 4 is weaker than target minLength 8"));
    }

    @Test
    void acceptsStringBindingWhenSourcePatternMatchesTargetPattern() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringPatternCompatibilityLibrary("^[A-Z]{2}\\d{4}$",
                                "^[A-Z]{2}\\d{4}$")));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsStringBindingWhenSourcePatternCannotProveTargetPattern() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringPatternCompatibilityLibrary("^[A-Z]+$",
                                "^[A-Z]{2}\\d{4}$")));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source pattern '^[A-Z]+$' cannot be proven compatible"));
    }

    @Test
    void acceptsStringBindingWhenSourceFormatMatchesTargetFormat() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringFormatCompatibilityLibrary("email", "email")));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsStringBindingWhenSourceFormatCannotFeedTargetFormat() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.stringFormatCompatibilityLibrary(null, "email")));
        GraphDraft draft = stringLengthCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("target requires format 'email' but source has no format"));
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
    void acceptsObjectBindingWhenUnevaluatedPropertySchemasAreCompatible() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        objectCompatibilityLibraryWithApplicantSchemas(
                                applicantSchemaWithUnevaluatedProperties(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "string")),
                                applicantSchemaWithUnevaluatedProperties(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "string")))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsObjectBindingWhenSourceAllowsUnconstrainedUnevaluatedProperties() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        objectCompatibilityLibraryWithApplicantSchemas(
                                applicantSchemaWithoutResidualProperties(applicantProperties("integer", false),
                                        List.of("score", "tier")),
                                applicantSchemaWithUnevaluatedProperties(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "string")))));
        GraphDraft draft = objectCompatibilityDraft();

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .extracting("code")
                .contains("visual.binding.typeMismatch", "visual.edge.typeMismatch");
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("source object allows unconstrained additional fields")
                        .contains("unevaluatedProperties"));
    }

    @Test
    void rejectsConstantBindingWhenObjectUnevaluatedPropertyViolatesTargetSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        objectCompatibilityLibraryWithApplicantSchemas(
                                applicantSchemaWithUnevaluatedProperties(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "string")),
                                applicantSchemaWithUnevaluatedProperties(applicantProperties("integer", false),
                                        List.of("score", "tier"), Map.of("type", "string")))));
        GraphDraft draft = applicantConstantDraft(GraphDraft.Binding.constant(Map.of(
                "score", 720,
                "tier", "gold",
                "segment", 7
        )));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.binding.typeMismatch");
                    assertThat(diagnostic.message()).contains("applicant").contains("object");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/inputs/applicant");
                });
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
    void rejectsServiceManagedConfigForPublicationBackedOperators() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(publicationPolicyLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "publishedPolicyComposition",
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
                List.of(new GraphDraft.DraftNode(
                        "publishedEligibility",
                        "publication:pub-eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")
                        ),
                        Map.of("publicationId", "evil-publication", "outputNode", "tamperedOutput"),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("publishedEligibility", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.serviceManaged");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/publicationId");
                    assertThat(diagnostic.message()).contains("publicationId").contains("service-managed");
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.serviceManaged");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/outputNode");
                    assertThat(diagnostic.message()).contains("outputNode").contains("service-managed");
                });
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
    void rejectsNodeConfigWhenConstDoesNotMatchConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(standardConstConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "relaxed"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.enumMismatch");
                    assertThat(diagnostic.message()).contains("strict");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/mode");
                });
    }

    @Test
    void rejectsNodeConfigWhenNumericValueViolatesConfigSchemaBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(numericBoundsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 950,
                "mode", "strict"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("threshold").contains("integer");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/threshold");
                });
    }

    @Test
    void rejectsNodeConfigWhenNumericValueViolatesConfigSchemaMultipleOf() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(numericMultipleOfConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 705,
                "mode", "strict"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("threshold").contains("multipleOf");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/threshold");
                });
    }

    @Test
    void rejectsNodeConfigWhenStringValueViolatesConfigSchemaLength() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(stringLengthConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "qa"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("mode").contains("string length");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/mode");
                });
    }

    @Test
    void rejectsNodeConfigWhenStringValueViolatesConfigSchemaPattern() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(stringPatternConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "mode", "qa"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("mode").contains("string pattern");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/mode");
                });
    }

    @Test
    void rejectsNodeConfigWhenStringValueViolatesConfigSchemaFormat() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(stringFormatConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "callbackUri", "not a uri"
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("callbackUri").contains("string format");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/callbackUri");
                });
    }

    @Test
    void rejectsNodeConfigWhenArrayValueViolatesConfigSchemaItemBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayItemBoundsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "channels", List.of("web")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("channels").contains("array item count");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/channels");
                });
    }

    @Test
    void rejectsNodeConfigWhenArrayValueViolatesConfigSchemaUniqueItems() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayUniqueItemsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "channels", List.of("web", "web")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("channels").contains("uniqueItems");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/channels");
                });
    }

    @Test
    void rejectsNodeConfigWhenArrayValueViolatesConfigSchemaContains() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayContainsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "channels", List.of("secondary")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("channels").contains("contains");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/channels");
                });
    }

    @Test
    void rejectsNodeConfigWhenArrayPrefixItemViolatesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(arrayPrefixItemsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "channels", List.of("bad", "route")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.typeMismatch");
                    assertThat(diagnostic.message()).contains("channels/0").contains("integer");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/channels/0");
                });
    }

    @Test
    void rejectsNodeConfigWhenObjectValueViolatesConfigSchemaPropertyBounds() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectPropertyBoundsConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("mode", "auto")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("routing").contains("object property count");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/routing");
                });
    }

    @Test
    void rejectsNodeConfigWhenObjectPropertyNameViolatesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectPropertyNamesConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("bad", "auto")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("routing").contains("propertyNames");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/routing");
                });
    }

    @Test
    void rejectsNodeConfigWhenObjectPatternPropertyValueViolatesConfigSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectPatternPropertiesConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("route.timeout", "slow")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("routing").contains("patternProperties");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/routing");
                });
    }

    @Test
    void rejectsNodeConfigWhenObjectDependentRequiredDependencyIsMissing() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectDependentRequiredConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("cardNumber", "4111111111111111")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("routing").contains("dependentRequired");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/routing");
                });
    }

    @Test
    void rejectsNodeConfigWhenObjectDependentSchemasDependencyIsMissing() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectDependentSchemasConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("cardNumber", "4111111111111111")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.constraintMismatch");
                    assertThat(diagnostic.message()).contains("routing").contains("dependentSchemas");
                    assertThat(diagnostic.target()).isEqualTo("/nodes/0/config/routing");
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
    void rejectsNodeConfigWhenObjectUnevaluatedPropertiesAreForbidden() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(objectUnevaluatedPropertiesConfigurablePolicyLibrary()));
        GraphDraft draft = configurablePolicyDraft(Map.of(
                "threshold", 700,
                "routing", Map.of("mode", "auto", "shadow", "yes")
        ));

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.config.unknown");
                    assertThat(diagnostic.target()).contains("routing").contains("shadow");
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
    void acceptsEdgeWhenSourcePathResolvesThroughDynamicOutputSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.dynamicUnevaluatedOutputLibrary()));
        GraphDraft draft = dynamicOutputFactsDraft(
                Map.of("score", GraphDraft.Binding.nodePath(
                        "riskDynamicFacts", "facts", "dynamicScore", "inputs", "score")),
                List.of(new GraphDraft.DraftEdge("dynamic-score", "data",
                        new GraphDraft.Endpoint("riskDynamicFacts", "facts", "dynamicScore"),
                        new GraphDraft.Endpoint("riskScoreSink", "inputs", "score"))),
                new GraphDraft.OutputSelection("riskScoreSink", "")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).noneMatch(diagnostic -> diagnostic.error());
    }

    @Test
    void acceptsGraphOutputSelectionThroughDynamicOutputSchema() {
        GraphDraftValidator validator = new GraphDraftValidator(
                VisualCatalogTestSupport.catalogWithLibrary(
                        VisualCatalogTestSupport.dynamicUnevaluatedOutputLibrary()));
        GraphDraft draft = new GraphDraft(
                "",
                "",
                0,
                "dynamicFactsOutput",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "riskDynamicFacts",
                        "risk:dynamicFacts",
                        "",
                        Map.of(),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("riskDynamicFacts", "facts.dynamicScore")
        );

        VisualValidationResult result = validator.validate(draft);

        assertThat(result.valid()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(result.diagnostics()).noneMatch(diagnostic -> diagnostic.error());
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

    private static OperatorLibrary rootArrayFactsLibrary() {
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
                                                "required", List.of("score")
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
                "risk-root-array",
                "Risk root array operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(rootArrayFacts, VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
    }

    private static GraphDraft rootArrayFactsEligibilityDraft(GraphDraft.Binding scoreBinding,
                                                            GraphDraft.DraftEdge edge) {
        return new GraphDraft(
                "",
                "",
                0,
                "rootArrayEligibility",
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
                                "eligibility",
                                "risk:eligibility",
                                "",
                                Map.of(
                                        "score", scoreBinding,
                                        "amount", GraphDraft.Binding.constant(1000)
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

    private static SchemaEnvelope dynamicAdditionalGraphInputSchema(Object additionalProperties,
                                                                    Object propertyNames) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", additionalProperties,
                "propertyNames", propertyNames
        ));
    }

    private static GraphDraft dynamicOutputFactsDraft(Map<String, GraphDraft.Binding> sinkInputs,
                                                      List<GraphDraft.DraftEdge> edges,
                                                      GraphDraft.OutputSelection output) {
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
                                sinkInputs,
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                output
        );
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

    private static GraphDraft listConstantDraft(GraphDraft.Binding itemsBinding) {
        return new GraphDraft(
                "",
                "",
                0,
                "listConstant",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "listConsumer",
                        "risk:listConsumer",
                        "",
                        Map.of("items", itemsBinding),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("listConsumer", "")
        );
    }

    private static OperatorLibrary arrayItemScoreLibrary() {
        OperatorDefinition scoreConsumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes one score.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")),
                                        List.of("score")),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
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
                "risk-array-item-score",
                "Array item score operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.listFactsOperator("integer"), scoreConsumer)
        );
    }

    private static GraphDraft objectCompatibilityDraft(GraphDraft.Binding payloadBinding,
                                                       GraphDraft.DraftEdge edge) {
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
                                "objectFacts",
                                "risk:objectFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "objectConsumer",
                                "risk:objectConsumer",
                                "",
                                Map.of("payload", payloadBinding),
                                Map.of(),
                                null
                        )
                ),
                List.of(edge),
                Map.of(),
                new GraphDraft.OutputSelection("objectConsumer", "")
        );
    }

    private static GraphDraft objectConstantDraft(GraphDraft.Binding payloadBinding) {
        return new GraphDraft(
                "",
                "",
                0,
                "objectConstant",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "objectConsumer",
                        "risk:objectConsumer",
                        "",
                        Map.of("payload", payloadBinding),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("objectConsumer", "")
        );
    }

    private static GraphDraft applicantConstantDraft(GraphDraft.Binding applicantBinding) {
        return new GraphDraft(
                "",
                "",
                0,
                "applicantConstant",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "applicantConsumer",
                        "risk:applicantObjectConsumer",
                        "",
                        Map.of("applicant", applicantBinding),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("applicantConsumer", "")
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

    private static GraphDraft numericBoundsCompatibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "numericBoundsCompatibility",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "scoreProducer",
                                "risk:scoreProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "scoreConsumer",
                                "risk:scoreConsumer",
                                "",
                                Map.of("score", GraphDraft.Binding.nodePath(
                                        "scoreProducer",
                                        "output",
                                        "score",
                                        "inputs",
                                        "score")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("score", "data",
                        new GraphDraft.Endpoint("scoreProducer", "output", "score"),
                        new GraphDraft.Endpoint("scoreConsumer", "inputs", "score"))),
                Map.of(),
                new GraphDraft.OutputSelection("scoreConsumer", "")
        );
    }

    private static GraphDraft scoreConstantDraft(GraphDraft.Binding scoreBinding) {
        return new GraphDraft(
                "",
                "",
                0,
                "scoreConstant",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "scoreConsumer",
                        "risk:scoreConsumer",
                        "",
                        Map.of("score", scoreBinding),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("scoreConsumer", "")
        );
    }

    private static GraphDraft scoreFactsDependencyDraft(List<GraphDraft.DraftEdge> edges, String outputNodeId) {
        return new GraphDraft(
                "",
                "",
                0,
                "scoreFactsDependency",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "prepareFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "publishFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection(outputNodeId, "")
        );
    }

    private static GraphDraft routeDraft(List<GraphDraft.DraftEdge> edges) {
        return routeDraft(selectorInputSchema(Map.of("type", "string")), edges);
    }

    private static GraphDraft routeDraft(SchemaEnvelope inputSchema, List<GraphDraft.DraftEdge> edges) {
        return new GraphDraft(
                "",
                "",
                0,
                "typeRoute",
                "",
                "",
                "",
                "",
                inputSchema,
                List.of(
                        new GraphDraft.DraftNode(
                                "routeByType",
                                "risk:typeRoute",
                                "",
                                Map.of("value", GraphDraft.Binding.contextPath("productType")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "physicalFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "genericFacts",
                                "risk:scoreFacts",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                edges,
                Map.of(),
                new GraphDraft.OutputSelection("physicalFacts", "")
        );
    }

    private static SchemaEnvelope selectorInputSchema(Map<String, Object> selectorSchema) {
        return SchemaEnvelope.object(Map.of("productType", selectorSchema), List.of("productType"));
    }

    private static OperatorLibrary selectorRouteLibrary(Map<String, Object> selectorSchema) {
        OperatorDefinition base = VisualCatalogTestSupport.typeRouteOperator();
        OperatorDefinition route = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("value", selectorSchema), List.of("value")),
                                true,
                                "Route selector input.")),
                        List.of()
                ),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-routes-domain",
                "Risk route domain operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(route, VisualCatalogTestSupport.scoreFactsOperator())
        );
    }

    private static OperatorLibrary scoreTypeCompatibilityLibrary(Object sourceType, Object targetType) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("score", Map.of("type", sourceType));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreProducer",
                "1.0.0",
                new OperatorDefinition.Display("Score producer",
                        "Produces a typed risk score.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(producerOutputProperties, List.of()),
                                true,
                                "Score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskScoreProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerInputProperties = new LinkedHashMap<>();
        consumerInputProperties.put("score", Map.of("type", targetType));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes a typed risk score.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(consumerInputProperties, List.of("score")),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(consumerOutputProperties, List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "accepted", "true"
                        )
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-type-compatibility",
                "Score type compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static OperatorLibrary scoreDefinitionsCompatibilityLibrary(Object sourceType, Object targetType) {
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreProducer",
                "1.0.0",
                new OperatorDefinition.Display("Score producer",
                        "Produces referenced risk score facts.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                scoreReferenceEnvelope(sourceType),
                                true,
                                "Score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskScoreProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes referenced risk score facts.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                scoreReferenceEnvelope(targetType),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(consumerOutputProperties, List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "accepted", "true"
                        )
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-defs-compatibility",
                "Score definitions compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static OperatorLibrary scoreAllOfCompatibilityLibrary(Object sourceType, Object targetType) {
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreProducer",
                "1.0.0",
                new OperatorDefinition.Display("Score producer",
                        "Produces composed risk score facts.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                scoreAllOfEnvelope(sourceType),
                                true,
                                "Score output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskScoreProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes composed risk score facts.",
                        List.of("risk", "numeric")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                scoreAllOfEnvelope(targetType),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(consumerOutputProperties, List.of()),
                                true,
                                "Consumer output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "accepted", "true"
                        )
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-score-allof-compatibility",
                "Score allOf compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static SchemaEnvelope scoreReferenceEnvelope(Object scoreType) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(
                        "score", Map.of("$ref", "#/$defs/Score")
                ),
                "required", List.of("score"),
                "$defs", Map.of(
                        "Score", Map.of("type", scoreType)
                )
        ));
    }

    private static SchemaEnvelope scoreAllOfEnvelope(Object scoreType) {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "allOf", List.of(
                        Map.of("$ref", "#/$defs/BaseScorePort"),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "score", Map.of("type", scoreType)
                                ),
                                "required", List.of("score"),
                                "additionalProperties", false)
                ),
                "$defs", Map.of(
                        "BaseScorePort", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "source", Map.of("type", "string")
                                ))
                )
        ));
    }

    private static GraphDraft stringLengthCompatibilityDraft() {
        return new GraphDraft(
                "",
                "",
                0,
                "stringLengthCompatibility",
                "",
                "",
                "",
                "",
                null,
                List.of(
                        new GraphDraft.DraftNode(
                                "customerIdProducer",
                                "risk:customerIdProducer",
                                "",
                                Map.of(),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "customerIdConsumer",
                                "risk:customerIdConsumer",
                                "",
                                Map.of("customerId", GraphDraft.Binding.nodePath(
                                        "customerIdProducer",
                                        "output",
                                        "customerId",
                                        "inputs",
                                        "customerId")),
                                Map.of(),
                                null
                        )
                ),
                List.of(new GraphDraft.DraftEdge("customerId", "data",
                        new GraphDraft.Endpoint("customerIdProducer", "output", "customerId"),
                        new GraphDraft.Endpoint("customerIdConsumer", "inputs", "customerId"))),
                Map.of(),
                new GraphDraft.OutputSelection("customerIdConsumer", "")
        );
    }

    private static GraphDraft stringPatternConstantDraft(String customerId) {
        return new GraphDraft(
                "",
                "",
                0,
                "stringPatternConstant",
                "",
                "",
                "",
                "",
                null,
                List.of(new GraphDraft.DraftNode(
                        "customerIdConsumer",
                        "risk:customerIdConsumer",
                        "",
                        Map.of("customerId", GraphDraft.Binding.constant(customerId)),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("customerIdConsumer", "")
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

    private static Map<String, Object> applicantSchemaWithoutResidualProperties(Map<String, Object> properties,
                                                                                List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> applicantSchemaWithUnevaluatedProperties(Map<String, Object> properties,
                                                                                List<String> required,
                                                                                Object unevaluatedProperties) {
        Map<String, Object> schema = applicantSchemaWithoutResidualProperties(properties, required);
        schema.put("unevaluatedProperties", unevaluatedProperties);
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

    private static OperatorLibrary nativeDynamicObjectPolicyLibrary() {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("additionalProperties", true);
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicObjectPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic object policy",
                        "Accepts dynamic payload fields.",
                        List.of("risk", "dynamic")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("payload",
                                new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", payloadSchema),
                                true,
                                "Dynamic payload.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("decision", Map.of("type", "string")), List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDynamicObjectPolicy", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-dynamic-object-policy",
                "Risk dynamic object policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
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

    private static OperatorLibrary standardConstConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("mode", Map.of("type", "string", "const", "strict"));

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

    private static OperatorLibrary numericBoundsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of(
                "type", "integer",
                "minimum", 300,
                "maximum", 900
        ));
        configProperties.put("mode", Map.of("type", "string"));

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

    private static OperatorLibrary numericMultipleOfConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of(
                "type", "integer",
                "multipleOf", 10
        ));
        configProperties.put("mode", Map.of("type", "string"));

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

    private static OperatorLibrary stringLengthConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("mode", Map.of(
                "type", "string",
                "minLength", 4,
                "maxLength", 12
        ));

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

    private static OperatorLibrary stringPatternConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("mode", Map.of(
                "type", "string",
                "pattern", "^[a-z]{4,12}$"
        ));

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

    private static OperatorLibrary stringFormatConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("callbackUri", Map.of(
                "type", "string",
                "format", "uri"
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "callbackUri")),
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

    private static OperatorLibrary arrayItemBoundsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("channels", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "minItems", 2,
                "maxItems", 4
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "channels")),
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

    private static OperatorLibrary arrayUniqueItemsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("channels", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "uniqueItems", true
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "channels")),
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

    private static OperatorLibrary arrayContainsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("channels", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "contains", Map.of("type", "string", "const", "primary"),
                "minContains", 1
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "channels")),
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

    private static OperatorLibrary arrayPrefixItemsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("channels", Map.of(
                "type", "array",
                "prefixItems", integerStringPrefixItems(),
                "items", Map.of("type", "string")
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "channels")),
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

    private static OperatorLibrary objectPropertyBoundsConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "properties", Map.of(
                        "mode", Map.of("type", "string"),
                        "region", Map.of("type", "string"),
                        "channel", Map.of("type", "string")
                ),
                "minProperties", 2,
                "maxProperties", 3
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static OperatorLibrary objectPropertyNamesConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "additionalProperties", Map.of("type", "string"),
                "propertyNames", Map.of("pattern", "^route\\.[a-z]+$")
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static OperatorLibrary objectPatternPropertiesConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "additionalProperties", false,
                "patternProperties", Map.of("^route\\.", Map.of("type", "integer"))
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static OperatorLibrary objectUnevaluatedPropertiesConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "properties", Map.of("mode", Map.of("type", "string")),
                "unevaluatedProperties", false
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static OperatorLibrary objectDependentRequiredConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "properties", Map.of(
                        "cardNumber", Map.of("type", "string"),
                        "billingZip", Map.of("type", "string"),
                        "method", Map.of("type", "string")
                ),
                "additionalProperties", false,
                "dependentRequired", Map.of("cardNumber", List.of("billingZip"))
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static OperatorLibrary objectDependentSchemasConfigurablePolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("routing", Map.of(
                "type", "object",
                "properties", Map.of(
                        "cardNumber", Map.of("type", "string"),
                        "billingZip", Map.of("type", "string"),
                        "method", Map.of("type", "string")
                ),
                "additionalProperties", false,
                "dependentSchemas", cardNumberRequiresBillingZipDependentSchemas()
        ));

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
                SchemaEnvelope.object(configProperties, List.of("threshold", "routing")),
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

    private static Map<String, Object> cardNumberRequiresBillingZipDependentSchemas() {
        return Map.of(
                "cardNumber", Map.of(
                        "properties", Map.of(
                                "billingZip", Map.of("type", "string")),
                        "required", List.of("billingZip"))
        );
    }

    private static List<Map<String, Object>> integerStringPrefixItems() {
        return List.of(
                Map.of("type", "integer"),
                Map.of("type", "string")
        );
    }

    private static OperatorLibrary publicationPolicyLibrary() {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("score", Map.of("type", "integer"));
        inputProperties.put("amount", Map.of("type", "number"));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "publication:pub-eligibility",
                "7",
                new OperatorDefinition.Display("Published eligibility",
                        "Invokes a frozen published visual graph.",
                        List.of("publication", "subgraph")),
                new OperatorDefinition.Source("visual-publication", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("score", "amount")),
                                true,
                                "Published graph inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("eligible", Map.of("type", "boolean")), List.of()),
                                true,
                                "Published graph output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "visualPublication",
                        Map.of("publicationId", "pub-eligibility")),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "published-visual-graphs",
                "Published visual graphs",
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
