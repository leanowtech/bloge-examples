package com.leanowtech.bloge.gateway.visual;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared authoritative operator fixture for Contract and Scenario integration tests.
 */
public final class ScenarioOperatorTestSupport {

    /** Stable reference used by the shared operator fixture. */
    public static final String OPERATOR_REF = "risk:score";

    /** Business-facing reference for the shared virtual resource operator fixture. */
    public static final String RESOURCE_OPERATOR_REF = "resource:user-service.getProfile";

    /** Executable BLOGE operator used after resource-descriptor lowering. */
    public static final String RESOURCE_RUNTIME_REF = "httpResource";

    private ScenarioOperatorTestSupport() {
    }

    /**
     * @return one policy-visible, request-response operator with exact port schemas
     */
    public static OperatorDefinition operator() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                OPERATOR_REF,
                "2.1.0",
                new OperatorDefinition.Display(
                        "Risk score",
                        "Scores one applicant for a credit decision.",
                        List.of("risk", "credit")),
                OperatorDefinition.Source.builtIn("java"),
                new OperatorDefinition.Ports(
                        List.of(
                                new OperatorDefinition.Port(
                                        "applicantId",
                                        schema("string"),
                                        true,
                                        "Stable applicant identity."),
                                new OperatorDefinition.Port(
                                        "amount",
                                        schema("number"),
                                        false,
                                        "Requested credit amount.")),
                        List.of(
                                new OperatorDefinition.Port(
                                        "decision",
                                        schema("string"),
                                        true,
                                        "Normalized credit decision."),
                                new OperatorDefinition.Port(
                                        "score",
                                        schema("integer"),
                                        true,
                                        "Calculated risk score."))),
                SchemaEnvelope.object(Map.of(), List.of()),
                new OperatorDefinition.Capabilities(
                        "READ", "REQUEST_KEY", false, true, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering(
                        "native", OPERATOR_REF, Map.of()),
                List.of());
    }

    /**
     * @return one virtual business operator that deterministically lowers to httpResource
     */
    public static OperatorDefinition resourceOperator() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                RESOURCE_OPERATOR_REF,
                "1.4.0",
                new OperatorDefinition.Display(
                        "User profile",
                        "Loads one governed customer profile resource.",
                        List.of("resource", "customer")),
                new OperatorDefinition.Source(
                        "resource-descriptor",
                        "user-service.getProfile",
                        "GET",
                        "/users/{userId}",
                        true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port(
                                "params",
                                new SchemaEnvelope(
                                        SchemaEnvelope.JSON_SCHEMA,
                                        "2020-12",
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "userId", Map.of("type", "string")),
                                                "required", List.of("userId"),
                                                "additionalProperties", false)),
                                true,
                                "Resource parameters.")),
                        List.of(new OperatorDefinition.Port(
                                "payload",
                                new SchemaEnvelope(
                                        SchemaEnvelope.JSON_SCHEMA,
                                        "2020-12",
                                        Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "name", Map.of("type", "string")),
                                                "additionalProperties", false)),
                                true,
                                "Descriptor-projected payload."))),
                SchemaEnvelope.object(Map.of(), List.of()),
                new OperatorDefinition.Capabilities(
                        "READ_EXTERNAL", "IDEMPOTENT", false, false, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering(
                        "resource-descriptor",
                        RESOURCE_RUNTIME_REF,
                        Map.of("resourceId", "user-service.getProfile")),
                List.of());
    }

    /**
     * @param operator catalog operator
     * @return deterministic in-memory catalog exposing only the supplied operator
     */
    public static VisualOperatorCatalog catalog(OperatorDefinition operator) {
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return List.of(operator);
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return operator.operatorRef().equals(operatorRef)
                        ? Optional.of(operator)
                        : Optional.empty();
            }
        };
    }

    /** @return empty deterministic catalog */
    public static VisualOperatorCatalog emptyCatalog() {
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return List.of();
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return Optional.empty();
            }
        };
    }

    private static SchemaEnvelope schema(String type) {
        return new SchemaEnvelope(
                SchemaEnvelope.JSON_SCHEMA,
                "2020-12",
                Map.of("type", type));
    }
}
