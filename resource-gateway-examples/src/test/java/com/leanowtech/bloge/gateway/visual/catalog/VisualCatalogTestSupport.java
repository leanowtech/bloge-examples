package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test fixtures for visual catalog tests.
 */
public final class VisualCatalogTestSupport {

    public static final String RESOURCE_ID = "loan-applicant-service.getProfile";

    private VisualCatalogTestSupport() {
    }

    public static DefaultVisualOperatorCatalog catalogWithLoanApplicantResource() {
        ResourceDescriptor descriptor = loanApplicantDescriptor();
        ResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(new ResourceDesignContract(
                "contract:" + RESOURCE_ID,
                RESOURCE_ID,
                "Loan applicant profile",
                "Reads applicant facts.",
                List.of("loan", "applicant"),
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "segment", Map.of("type", "string")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        ));
        return new DefaultVisualOperatorCatalog(
                new SingleResourceRegistry(descriptor),
                contracts,
                new ResourceVirtualOperatorProjector()
        );
    }

    public static DefaultVisualOperatorCatalog catalogWithLibrary(OperatorLibrary library) {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library);
        return new DefaultVisualOperatorCatalog(
                emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );
    }

    public static DefaultVisualOperatorCatalog catalogWithLoanApplicantResourceAndLibrary(OperatorLibrary library) {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library);
        ResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(new ResourceDesignContract(
                "contract:" + RESOURCE_ID,
                RESOURCE_ID,
                "Loan applicant profile",
                "Reads applicant facts.",
                List.of("loan", "applicant"),
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer"),
                        "segment", Map.of("type", "string")
                ), List.of()),
                Map.of(),
                "ACTIVE"
        ));
        return new DefaultVisualOperatorCatalog(
                new SingleResourceRegistry(loanApplicantDescriptor()),
                contracts,
                new ResourceVirtualOperatorProjector(),
                libraries
        );
    }

    public static ResourceRegistry emptyResourceRegistry() {
        return new ResourceRegistry() {
            @Override
            public ResourceDescriptor resolve(String resourceId) {
                throw new com.leanowtech.bloge.gateway.exception.ResourceNotFoundException(resourceId);
            }

            @Override
            public boolean contains(String resourceId) {
                return false;
            }

            @Override
            public Collection<ResourceDescriptor> all() {
                return List.of();
            }
        };
    }

    public static OperatorDefinition eligibilityOperator(String scoreType) {
        return eligibilityOperator(scoreType, OperatorDefinition.Policy.unrestricted());
    }

    public static OperatorDefinition eligibilityOperator(String scoreType, OperatorDefinition.Policy policy) {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("score", Map.of("type", scoreType));
        inputProperties.put("amount", Map.of("type", "number"));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("eligible", Map.of("type", "boolean"));
        outputProperties.put("ruleId", Map.of("type", "string"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:eligibility",
                "1.0.0",
                new OperatorDefinition.Display("Eligibility", "Evaluates a reusable eligibility predicate.",
                        List.of("risk", "policy")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("score", "amount")),
                                true,
                                "Eligibility inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Eligibility result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                policy,
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "eligible", "{{input.score}} >= 700 && {{input.amount}} <= 300000",
                                "ruleId", "\"ELIGIBILITY_V1\""
                        )
                )),
                List.of()
        );
    }

    public static OperatorLibrary eligibilityLibrary(String scoreType) {
        return eligibilityLibrary(scoreType, OperatorDefinition.Policy.unrestricted());
    }

    public static OperatorLibrary eligibilityLibrary(String scoreType, OperatorDefinition.Policy policy) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(eligibilityOperator(scoreType, policy))
        );
    }

    public static OperatorDefinition scoreFactsOperator() {
        Map<String, Object> summaryProperties = new LinkedHashMap<>();
        summaryProperties.put("band", Map.of("type", "string"));

        Map<String, Object> factProperties = new LinkedHashMap<>();
        factProperties.put("score", Map.of("type", "integer"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreFacts",
                "1.0.0",
                new OperatorDefinition.Display("Score facts", "Produces named output ports for score facts.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(
                                new OperatorDefinition.Port("summary",
                                        SchemaEnvelope.object(summaryProperties, List.of()),
                                        true,
                                        "Human readable score summary."),
                                new OperatorDefinition.Port("facts",
                                        SchemaEnvelope.object(factProperties, List.of()),
                                        true,
                                        "Machine readable score facts.")
                        )
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskScoreFacts", Map.of()),
                List.of()
        );
    }

    public static OperatorLibrary multiOutputEligibilityLibrary(String scoreType) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(scoreFactsOperator(), eligibilityOperator(scoreType))
        );
    }

    public static OperatorDefinition typeRouteOperator() {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("value", Map.of("type", "string"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:typeRoute",
                "1.0.0",
                new OperatorDefinition.Display("Type route",
                        "Routes graph execution by a scalar business type.",
                        List.of("risk", "route")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("value")),
                                true,
                                "Route selector input.")),
                        List.of()
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("branch", "branch", Map.of(
                        "expression", "{{input.value}}"
                )),
                List.of()
        );
    }

    public static OperatorLibrary routeLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-routes",
                "Risk route operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(typeRouteOperator(), scoreFactsOperator())
        );
    }

    public static OperatorDefinition numericPassOperator() {
        Map<String, Object> valueProperties = new LinkedHashMap<>();
        valueProperties.put("value", Map.of("type", "integer"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:numericPass",
                "1.0.0",
                new OperatorDefinition.Display("Numeric pass",
                        "Passes a numeric value through for dependency tests.",
                        List.of("risk", "test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(valueProperties, List.of("value")),
                                true,
                                "Numeric input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(valueProperties, List.of()),
                                true,
                                "Numeric output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "value", "{{input.value}}"
                        )
                )),
                List.of()
        );
    }

    public static OperatorLibrary numericPassLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-numeric-pass",
                "Numeric pass operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(numericPassOperator())
        );
    }

    public static OperatorDefinition customerOrderMergeOperator() {
        Map<String, Object> idProperties = new LinkedHashMap<>();
        idProperties.put("id", Map.of("type", "string"));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("customerId", Map.of("type", "string"));
        outputProperties.put("orderId", Map.of("type", "string"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerOrderMerge",
                "1.0.0",
                new OperatorDefinition.Display("Customer order merge",
                        "Consumes duplicate field names through separate input ports.",
                        List.of("risk", "merge")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(
                                new OperatorDefinition.Port("customer",
                                        SchemaEnvelope.object(idProperties, List.of("id")),
                                        true,
                                        "Customer facts."),
                                new OperatorDefinition.Port("order",
                                        SchemaEnvelope.object(idProperties, List.of("id")),
                                        true,
                                        "Order facts.")
                        ),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Merged ids."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "customerId", "{{input.customer.id}}",
                                "orderId", "{{input.order.id}}"
                        )
                )),
                List.of()
        );
    }

    public static OperatorLibrary duplicateInputPathLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-duplicate-inputs",
                "Duplicate input path operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(customerOrderMergeOperator())
        );
    }

    public static OperatorLibrary dynamicUnevaluatedDuplicateInputLibrary() {
        SchemaEnvelope dynamicIntegerPort = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "unevaluatedProperties", Map.of("type", "integer")
        ));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicMapMerge",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic map merge",
                        "Consumes duplicate dynamic field names through separate input ports.",
                        List.of("risk", "map")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(
                                new OperatorDefinition.Port("primary", dynamicIntegerPort, true,
                                        "Primary dynamic facts."),
                                new OperatorDefinition.Port("secondary", dynamicIntegerPort, true,
                                        "Secondary dynamic facts.")
                        ),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "primaryScore", Map.of("type", "integer"),
                                        "secondaryScore", Map.of("type", "integer")
                                ), List.of()),
                                true,
                                "Merged dynamic scores."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "primaryScore", "{{input.primary.dynamicScore}}",
                                "secondaryScore", "{{input.secondary.dynamicScore}}"
                        )
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-dynamic-map-merge",
                "Dynamic map merge operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    public static OperatorLibrary mixedDeclaredDynamicInputLibrary() {
        SchemaEnvelope mixedIntegerPort = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("fixedScore", Map.of("type", "integer")),
                "required", List.of("fixedScore"),
                "unevaluatedProperties", Map.of("type", "integer")
        ));
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:mixedDynamicScorer",
                "1.0.0",
                new OperatorDefinition.Display("Mixed dynamic scorer",
                        "Consumes declared and dynamic score fields on one input port.",
                        List.of("risk", "map")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("facts", mixedIntegerPort, true,
                                "Declared and dynamic facts.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of(
                                        "fixedScore", Map.of("type", "integer"),
                                        "dynamicScore", Map.of("type", "integer")
                                ), List.of()),
                                true,
                                "Selected scores."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "fixedScore", "{{input.fixedScore}}",
                                "dynamicScore", "{{input.dynamicScore}}"
                        )
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-mixed-dynamic-scorer",
                "Mixed dynamic scorer operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    public static OperatorLibrary dynamicUnevaluatedOutputLibrary() {
        SchemaEnvelope dynamicIntegerPort = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object",
                "properties", Map.of(),
                "unevaluatedProperties", Map.of("type", "integer")
        ));
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:dynamicFacts",
                "1.0.0",
                new OperatorDefinition.Display("Dynamic facts",
                        "Produces map-style dynamic facts.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("facts", dynamicIntegerPort, true,
                                "Dynamic fact map."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDynamicFacts", Map.of()),
                List.of()
        );
        OperatorDefinition sink = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreSink",
                "1.0.0",
                new OperatorDefinition.Display("Score sink",
                        "Consumes a single score from dynamic facts.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
                                true,
                                "Score input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("acceptedScore", Map.of("type", "integer")), List.of()),
                                true,
                                "Accepted score."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "acceptedScore", "{{input.score}}"
                        )
                )),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-dynamic-facts",
                "Dynamic fact operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, sink)
        );
    }

    public static OperatorLibrary unsafePathLibrary() {
        Map<String, Object> sourceOutputProperties = new LinkedHashMap<>();
        sourceOutputProperties.put("bad-field", Map.of("type", "integer"));
        sourceOutputProperties.put("safeScore", Map.of("type", "integer"));

        OperatorDefinition source = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsafeFacts",
                "1.0.0",
                new OperatorDefinition.Display("Unsafe facts",
                        "Produces output paths used to verify DSL-safe draft path validation.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("facts",
                                SchemaEnvelope.object(sourceOutputProperties, List.of()),
                                true,
                                "Unsafe path facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskUnsafeFacts", Map.of()),
                List.of()
        );

        Map<String, Object> sinkInputProperties = new LinkedHashMap<>();
        sinkInputProperties.put("score", Map.of("type", "integer"));
        sinkInputProperties.put("bad-target", Map.of("type", "integer"));

        OperatorDefinition sink = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreSink",
                "1.0.0",
                new OperatorDefinition.Display("Score sink",
                        "Consumes score paths used to verify DSL-safe draft path validation.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(sinkInputProperties, List.of()),
                                true,
                                "Score inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("acceptedScore", Map.of("type", "integer")),
                                        List.of()),
                                true,
                                "Accepted score."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("acceptedScore", "{{input.score}}")
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-unsafe-paths",
                "Unsafe path operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(source, sink)
        );
    }

    public static OperatorLibrary unsafeOutputPortLibrary() {
        OperatorDefinition source = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:unsafePortFacts",
                "1.0.0",
                new OperatorDefinition.Display("Unsafe port facts",
                        "Produces an output port name used to verify DSL-safe draft port validation.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("graph",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Unsafe output port facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskUnsafePortFacts", Map.of()),
                List.of()
        );

        OperatorDefinition sink = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreSink",
                "1.0.0",
                new OperatorDefinition.Display("Score sink",
                        "Consumes score paths used to verify DSL-safe draft port validation.",
                        List.of("risk", "facts")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of()),
                                true,
                                "Score inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("acceptedScore", Map.of("type", "integer")),
                                        List.of()),
                                true,
                                "Accepted score."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of("acceptedScore", "{{input.score}}")
                )),
                List.of()
        );

        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-unsafe-output-ports",
                "Unsafe output port operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(source, sink)
        );
    }

    public static OperatorDefinition customerFactsOperator() {
        Map<String, Object> customerProperties = new LinkedHashMap<>();
        customerProperties.put("id", Map.of("type", "string"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerFacts",
                "1.0.0",
                new OperatorDefinition.Display("Customer facts",
                        "Produces a customer object as a named root output port.",
                        List.of("risk", "customer")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("customer",
                                SchemaEnvelope.object(customerProperties, List.of("id")),
                                true,
                                "Customer facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskCustomerFacts", Map.of()),
                List.of()
        );
    }

    public static OperatorLibrary rootObjectPortLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-root-object-ports",
                "Root object port operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(customerFactsOperator(), customerOrderMergeOperator())
        );
    }

    public static OperatorDefinition nestedApplicantEligibilityOperator() {
        Map<String, Object> applicantProperties = new LinkedHashMap<>();
        applicantProperties.put("score", Map.of("type", "integer"));

        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("applicant", Map.of(
                "type", "object",
                "properties", applicantProperties,
                "required", List.of("score"),
                "additionalProperties", false
        ));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("eligible", Map.of("type", "boolean"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nestedApplicantEligibility",
                "1.0.0",
                new OperatorDefinition.Display("Nested applicant eligibility",
                        "Evaluates nested applicant facts.",
                        List.of("risk", "nested")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("applicant")),
                                true,
                                "Nested applicant inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Eligibility result."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "eligible", "{{input.applicant.score}} >= 700"
                        )
                )),
                List.of()
        );
    }

    public static OperatorLibrary nestedApplicantEligibilityLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-nested-inputs",
                "Nested input operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(nestedApplicantEligibilityOperator())
        );
    }

    public static OperatorDefinition listFactsOperator(String itemType) {
        return listFactsOperator(Map.of("type", itemType));
    }

    public static OperatorDefinition listFactsOperator(Map<String, Object> itemsSchema) {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("items", Map.of(
                "type", "array",
                "items", itemsSchema
        ));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:listFacts",
                "1.0.0",
                new OperatorDefinition.Display("List facts",
                        "Produces typed list facts.",
                        List.of("risk", "list")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "List facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskListFacts", Map.of()),
                List.of()
        );
    }

    public static OperatorDefinition listConsumerOperator(String itemType) {
        return listConsumerOperator(Map.of("type", itemType));
    }

    public static OperatorDefinition listConsumerOperator(Map<String, Object> itemsSchema) {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("items", Map.of(
                "type", "array",
                "items", itemsSchema
        ));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:listConsumer",
                "1.0.0",
                new OperatorDefinition.Display("List consumer",
                        "Consumes typed list facts.",
                        List.of("risk", "list")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("items")),
                                true,
                                "List inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
    }

    public static OperatorLibrary listCompatibilityLibrary(String sourceItemType, String targetItemType) {
        return listCompatibilityLibrary(
                Map.of("type", "array", "items", Map.of("type", sourceItemType)),
                Map.of("type", "array", "items", Map.of("type", targetItemType))
        );
    }

    public static OperatorLibrary listItemBoundsCompatibilityLibrary(int sourceMinItems,
                                                                     int sourceMaxItems,
                                                                     int targetMinItems,
                                                                     int targetMaxItems) {
        return listCompatibilityLibrary(
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "minItems", sourceMinItems,
                        "maxItems", sourceMaxItems),
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "minItems", targetMinItems,
                        "maxItems", targetMaxItems)
        );
    }

    public static OperatorLibrary listUniqueItemsCompatibilityLibrary(boolean sourceUniqueItems,
                                                                      boolean targetUniqueItems) {
        return listCompatibilityLibrary(
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "uniqueItems", sourceUniqueItems),
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "integer"),
                        "uniqueItems", targetUniqueItems)
        );
    }

    public static OperatorLibrary listContainsCompatibilityLibrary(int sourceMinContains,
                                                                   int targetMinContains) {
        return listCompatibilityLibrary(
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "contains", Map.of("type", "string", "const", "primary"),
                        "minContains", sourceMinContains),
                Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "contains", Map.of("type", "string", "const", "primary"),
                        "minContains", targetMinContains)
        );
    }

    public static OperatorLibrary listPrefixItemsCompatibilityLibrary(Object sourcePrefixItems,
                                                                      Object targetPrefixItems) {
        return listCompatibilityLibrary(
                listPrefixItemsSchema(sourcePrefixItems),
                listPrefixItemsSchema(targetPrefixItems)
        );
    }

    private static Map<String, Object> listPrefixItemsSchema(Object prefixItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", "string"));
        if (prefixItems != null) {
            schema.put("prefixItems", prefixItems);
        }
        return schema;
    }

    public static OperatorLibrary objectPropertyBoundsCompatibilityLibrary(int sourceMinProperties,
                                                                           int sourceMaxProperties,
                                                                           int targetMinProperties,
                                                                           int targetMaxProperties) {
        Map<String, Object> sourcePayloadSchema = new LinkedHashMap<>();
        sourcePayloadSchema.put("type", "object");
        sourcePayloadSchema.put("properties", Map.of(
                "status", Map.of("type", "string"),
                "region", Map.of("type", "string"),
                "channel", Map.of("type", "string")
        ));
        sourcePayloadSchema.put("minProperties", sourceMinProperties);
        sourcePayloadSchema.put("maxProperties", sourceMaxProperties);

        Map<String, Object> targetPayloadSchema = new LinkedHashMap<>();
        targetPayloadSchema.put("type", "object");
        targetPayloadSchema.put("properties", Map.of(
                "status", Map.of("type", "string"),
                "region", Map.of("type", "string"),
                "channel", Map.of("type", "string")
        ));
        targetPayloadSchema.put("minProperties", targetMinProperties);
        targetPayloadSchema.put("maxProperties", targetMaxProperties);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("payload", sourcePayloadSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectFacts",
                "1.0.0",
                new OperatorDefinition.Display("Object facts",
                        "Produces object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "Object facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("payload", targetPayloadSchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Object consumer",
                        "Consumes object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("payload")),
                                true,
                                "Object inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-object-compatibility",
                "Object compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary objectPropertyNamesCompatibilityLibrary(Object sourcePropertyNames,
                                                                          Object sourceAdditionalProperties,
                                                                          Object targetPropertyNames,
                                                                          Object targetAdditionalProperties) {
        Map<String, Object> sourcePayloadSchema = objectPropertyNamesPayloadSchema(
                sourcePropertyNames, sourceAdditionalProperties);
        Map<String, Object> targetPayloadSchema = objectPropertyNamesPayloadSchema(
                targetPropertyNames, targetAdditionalProperties);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("payload", sourcePayloadSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectFacts",
                "1.0.0",
                new OperatorDefinition.Display("Object facts",
                        "Produces object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "Object facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("payload", targetPayloadSchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Object consumer",
                        "Consumes object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("payload")),
                                true,
                                "Object inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-object-property-names-compatibility",
                "Object property name compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static Map<String, Object> objectPropertyNamesPayloadSchema(Object propertyNames,
                                                                        Object additionalProperties) {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("properties", Map.of(
                "label.status", Map.of("type", "string"),
                "label.region", Map.of("type", "string")
        ));
        payloadSchema.put("additionalProperties", additionalProperties);
        if (propertyNames != null) {
            payloadSchema.put("propertyNames", propertyNames);
        }
        return payloadSchema;
    }

    public static OperatorLibrary objectPatternPropertiesCompatibilityLibrary(Object sourcePatternProperties,
                                                                              Object sourceAdditionalProperties,
                                                                              Object targetPatternProperties,
                                                                              Object targetAdditionalProperties) {
        Map<String, Object> sourcePayloadSchema = objectPatternPropertiesPayloadSchema(
                sourcePatternProperties, sourceAdditionalProperties);
        Map<String, Object> targetPayloadSchema = objectPatternPropertiesPayloadSchema(
                targetPatternProperties, targetAdditionalProperties);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("payload", sourcePayloadSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectFacts",
                "1.0.0",
                new OperatorDefinition.Display("Object facts",
                        "Produces object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "Object facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("payload", targetPayloadSchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Object consumer",
                        "Consumes object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("payload")),
                                true,
                                "Object inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-object-pattern-properties-compatibility",
                "Object pattern property compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static Map<String, Object> objectPatternPropertiesPayloadSchema(Object patternProperties,
                                                                            Object additionalProperties) {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("properties", Map.of(
                "metric.score", Map.of("type", "integer"),
                "metric.rank", Map.of("type", "integer")
        ));
        payloadSchema.put("additionalProperties", additionalProperties);
        if (patternProperties != null) {
            payloadSchema.put("patternProperties", patternProperties);
        }
        return payloadSchema;
    }

    public static OperatorLibrary objectDependentRequiredCompatibilityLibrary(Object sourceDependentRequired,
                                                                              List<String> sourceRequired,
                                                                              Object targetDependentRequired,
                                                                              List<String> targetRequired) {
        Map<String, Object> sourcePayloadSchema = objectDependentRequiredPayloadSchema(
                sourceDependentRequired, sourceRequired);
        Map<String, Object> targetPayloadSchema = objectDependentRequiredPayloadSchema(
                targetDependentRequired, targetRequired);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("payload", sourcePayloadSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectFacts",
                "1.0.0",
                new OperatorDefinition.Display("Object facts",
                        "Produces object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "Object facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("payload", targetPayloadSchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Object consumer",
                        "Consumes object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("payload")),
                                true,
                                "Object inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-object-dependent-required-compatibility",
                "Object dependent-required compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static Map<String, Object> objectDependentRequiredPayloadSchema(Object dependentRequired,
                                                                            List<String> required) {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("properties", Map.of(
                "cardNumber", Map.of("type", "string"),
                "billingZip", Map.of("type", "string"),
                "method", Map.of("type", "string")
        ));
        payloadSchema.put("additionalProperties", false);
        if (dependentRequired != null) {
            payloadSchema.put("dependentRequired", dependentRequired);
        }
        if (required != null && !required.isEmpty()) {
            payloadSchema.put("required", required);
        }
        return payloadSchema;
    }

    public static OperatorLibrary objectDependentSchemasCompatibilityLibrary(Object sourceDependentSchemas,
                                                                             Object targetDependentSchemas) {
        Map<String, Object> sourcePayloadSchema = objectDependentSchemasPayloadSchema(sourceDependentSchemas);
        Map<String, Object> targetPayloadSchema = objectDependentSchemasPayloadSchema(targetDependentSchemas);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("payload", sourcePayloadSchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectFacts",
                "1.0.0",
                new OperatorDefinition.Display("Object facts",
                        "Produces object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "Object facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskObjectFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("payload", targetPayloadSchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:objectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Object consumer",
                        "Consumes object facts.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("payload")),
                                true,
                                "Object inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-object-dependent-schemas-compatibility",
                "Object dependent-schema compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static Map<String, Object> objectDependentSchemasPayloadSchema(Object dependentSchemas) {
        Map<String, Object> payloadSchema = new LinkedHashMap<>();
        payloadSchema.put("type", "object");
        payloadSchema.put("properties", Map.of(
                "cardNumber", Map.of("type", "string"),
                "billingZip", Map.of("type", "string"),
                "method", Map.of("type", "string")
        ));
        payloadSchema.put("additionalProperties", false);
        if (dependentSchemas != null) {
            payloadSchema.put("dependentSchemas", dependentSchemas);
        }
        return payloadSchema;
    }

    private static OperatorLibrary listCompatibilityLibrary(Map<String, Object> sourceArraySchema,
                                                            Map<String, Object> targetArraySchema) {
        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("items", sourceArraySchema);
        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:listFacts",
                "1.0.0",
                new OperatorDefinition.Display("List facts",
                        "Produces typed list facts.",
                        List.of("risk", "list")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(sourceProperties, List.of()),
                                true,
                                "List facts."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskListFacts", Map.of()),
                List.of()
        );

        Map<String, Object> targetProperties = new LinkedHashMap<>();
        targetProperties.put("items", targetArraySchema);
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));
        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:listConsumer",
                "1.0.0",
                new OperatorDefinition.Display("List consumer",
                        "Consumes typed list facts.",
                        List.of("risk", "list")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(targetProperties, List.of("items")),
                                true,
                                "List inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
                "risk-list-compatibility",
                "List compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorDefinition enumDecisionProducerOperator(List<String> values) {
        Map<String, Object> decisionSchema = new LinkedHashMap<>();
        decisionSchema.put("type", "string");
        if (values != null && !values.isEmpty()) {
            decisionSchema.put("enum", values);
        }

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("decision", decisionSchema);

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:decisionProducer",
                "1.0.0",
                new OperatorDefinition.Display("Decision producer",
                        "Produces a constrained business decision.",
                        List.of("risk", "enum")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Decision output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDecisionProducer", Map.of()),
                List.of()
        );
    }

    public static OperatorDefinition enumDecisionConsumerOperator(List<String> acceptedValues) {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("decision", Map.of(
                "type", "enum",
                "values", acceptedValues
        ));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:decisionConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Decision consumer",
                        "Consumes a constrained business decision.",
                        List.of("risk", "enum")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("decision")),
                                true,
                                "Decision input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
    }

    public static OperatorLibrary enumCompatibilityLibrary(List<String> sourceValues,
                                                           List<String> targetValues) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-enum-compatibility",
                "Enum compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(enumDecisionProducerOperator(sourceValues), enumDecisionConsumerOperator(targetValues))
        );
    }

    public static OperatorLibrary constCompatibilityLibrary(String sourceValue, String targetValue) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("decision", Map.of("const", sourceValue));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:decisionProducer",
                "1.0.0",
                new OperatorDefinition.Display("Decision producer",
                        "Produces a fixed business decision.",
                        List.of("risk", "const")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(producerOutputProperties, List.of()),
                                true,
                                "Decision output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskDecisionProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerInputProperties = new LinkedHashMap<>();
        consumerInputProperties.put("decision", Map.of(
                "type", "string",
                "const", targetValue
        ));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:decisionConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Decision consumer",
                        "Consumes a fixed business decision.",
                        List.of("risk", "const")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(consumerInputProperties, List.of("decision")),
                                true,
                                "Decision input.")),
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
                "risk-const-compatibility",
                "Const compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary numericBoundsCompatibilityLibrary(int sourceMinimum,
                                                                    int sourceMaximum,
                                                                    int targetMinimum,
                                                                    int targetMaximum) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("score", Map.of(
                "type", "integer",
                "minimum", sourceMinimum,
                "maximum", sourceMaximum
        ));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreProducer",
                "1.0.0",
                new OperatorDefinition.Display("Score producer",
                        "Produces a bounded risk score.",
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
        consumerInputProperties.put("score", Map.of(
                "type", "integer",
                "minimum", targetMinimum,
                "maximum", targetMaximum
        ));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes a bounded risk score.",
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
                "risk-numeric-bounds-compatibility",
                "Numeric bounds compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary numericMultipleOfCompatibilityLibrary(int sourceMultipleOf,
                                                                        int targetMultipleOf) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("score", Map.of(
                "type", "integer",
                "multipleOf", sourceMultipleOf
        ));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreProducer",
                "1.0.0",
                new OperatorDefinition.Display("Score producer",
                        "Produces a stepped risk score.",
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
        consumerInputProperties.put("score", Map.of(
                "type", "integer",
                "multipleOf", targetMultipleOf
        ));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:scoreConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Score consumer",
                        "Consumes a stepped risk score.",
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
                "risk-numeric-multiple-compatibility",
                "Numeric multiple compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary stringLengthCompatibilityLibrary(int sourceMinLength,
                                                                   int sourceMaxLength,
                                                                   int targetMinLength,
                                                                   int targetMaxLength) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("customerId", Map.of(
                "type", "string",
                "minLength", sourceMinLength,
                "maxLength", sourceMaxLength
        ));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdProducer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id producer",
                        "Produces a bounded customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(producerOutputProperties, List.of()),
                                true,
                                "Customer id output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskCustomerIdProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerInputProperties = new LinkedHashMap<>();
        consumerInputProperties.put("customerId", Map.of(
                "type", "string",
                "minLength", targetMinLength,
                "maxLength", targetMaxLength
        ));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id consumer",
                        "Consumes a bounded customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(consumerInputProperties, List.of("customerId")),
                                true,
                                "Customer id input.")),
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
                "risk-string-length-compatibility",
                "String length compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary stringPatternCompatibilityLibrary(String sourcePattern,
                                                                    String targetPattern) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("customerId", customerIdPatternSchema(sourcePattern));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdProducer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id producer",
                        "Produces a patterned customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(producerOutputProperties, List.of()),
                                true,
                                "Customer id output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskCustomerIdProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerInputProperties = new LinkedHashMap<>();
        consumerInputProperties.put("customerId", customerIdPatternSchema(targetPattern));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id consumer",
                        "Consumes a patterned customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(consumerInputProperties, List.of("customerId")),
                                true,
                                "Customer id input.")),
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
                "risk-string-pattern-compatibility",
                "String pattern compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    public static OperatorLibrary stringFormatCompatibilityLibrary(String sourceFormat,
                                                                   String targetFormat) {
        Map<String, Object> producerOutputProperties = new LinkedHashMap<>();
        producerOutputProperties.put("customerId", customerIdFormatSchema(sourceFormat));

        OperatorDefinition producer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdProducer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id producer",
                        "Produces a formatted customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", false),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(producerOutputProperties, List.of()),
                                true,
                                "Customer id output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskCustomerIdProducer", Map.of()),
                List.of()
        );

        Map<String, Object> consumerInputProperties = new LinkedHashMap<>();
        consumerInputProperties.put("customerId", customerIdFormatSchema(targetFormat));
        Map<String, Object> consumerOutputProperties = new LinkedHashMap<>();
        consumerOutputProperties.put("accepted", Map.of("type", "boolean"));

        OperatorDefinition consumer = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:customerIdConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Customer id consumer",
                        "Consumes a formatted customer id.",
                        List.of("risk", "string")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(consumerInputProperties, List.of("customerId")),
                                true,
                                "Customer id input.")),
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
                "risk-string-format-compatibility",
                "String format compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(producer, consumer)
        );
    }

    private static Map<String, Object> customerIdPatternSchema(String pattern) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        if (pattern != null) {
            schema.put("pattern", pattern);
        }
        return schema;
    }

    private static Map<String, Object> customerIdFormatSchema(String format) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        if (format != null) {
            schema.put("format", format);
        }
        return schema;
    }

    public static OperatorDefinition applicantObjectProducerOperator(Map<String, Object> applicantProperties,
                                                                     List<String> required) {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("applicant", Map.of(
                "type", "object",
                "properties", applicantProperties,
                "required", required,
                "additionalProperties", false
        ));

        return new OperatorDefinition(
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
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Applicant object output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskApplicantObjectProducer", Map.of()),
                List.of()
        );
    }

    public static OperatorDefinition applicantObjectConsumerOperator(Map<String, Object> applicantProperties,
                                                                     List<String> required) {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("applicant", Map.of(
                "type", "object",
                "properties", applicantProperties,
                "required", required,
                "additionalProperties", false
        ));

        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:applicantObjectConsumer",
                "1.0.0",
                new OperatorDefinition.Display("Applicant object consumer",
                        "Consumes applicant facts as a nested object.",
                        List.of("risk", "object")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("applicant")),
                                true,
                                "Applicant object input.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
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
    }

    public static OperatorLibrary objectCompatibilityLibrary(Map<String, Object> sourceProperties,
                                                             List<String> sourceRequired,
                                                             Map<String, Object> targetProperties,
                                                             List<String> targetRequired) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-object-compatibility",
                "Object compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(
                        applicantObjectProducerOperator(sourceProperties, sourceRequired),
                        applicantObjectConsumerOperator(targetProperties, targetRequired)
                )
        );
    }

    public static OperatorDefinition configurablePolicyOperator() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("threshold", Map.of("type", "integer"));
        configProperties.put("mode", Map.of(
                "type", "enum",
                "values", List.of("strict", "relaxed")
        ));
        configProperties.put("enabled", Map.of("type", "boolean"));

        return new OperatorDefinition(
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
                        "assignments", Map.of(
                                "accepted", "true"
                        )
                )),
                List.of()
        );
    }

    public static OperatorLibrary configurablePolicyLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-configurable-policy",
                "Configurable policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(configurablePolicyOperator())
        );
    }

    public static OperatorLibrary nativeConfigCollisionLibrary() {
        OperatorDefinition facts = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:configFacts",
                "1.0.0",
                new OperatorDefinition.Display("Config facts",
                        "Produces a scalar value that can be wired into an operator input.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("threshold", Map.of("type", "integer")),
                                        List.of("threshold")),
                                true,
                                "Config facts output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskConfigFacts", Map.of()),
                List.of()
        );
        OperatorDefinition policy = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nativeConfigPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Native config policy",
                        "Native operator with a config input and business configSchema.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(Map.of("config", Map.of("type", "integer")),
                                        List.of("config")),
                                true,
                                "Input property that lowers to the native config field.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(Map.of("accepted", Map.of("type", "boolean")), List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(Map.of("limit", Map.of("type", "integer")), List.of("limit")),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "riskNativeConfigPolicy", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-native-config-collision",
                "Native config collision operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(facts, policy)
        );
    }

    public static OperatorLibrary nestedConfigPolicyLibrary() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("accepted", Map.of("type", "boolean"));

        Map<String, Object> limitsProperties = new LinkedHashMap<>();
        limitsProperties.put("threshold", Map.of("type", "integer"));
        limitsProperties.put("mode", Map.of(
                "type", "enum",
                "values", List.of("strict", "relaxed")
        ));

        Map<String, Object> configProperties = new LinkedHashMap<>();
        configProperties.put("limits", Map.of(
                "type", "object",
                "properties", limitsProperties,
                "required", List.of("threshold", "mode"),
                "additionalProperties", false
        ));

        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:nestedConfigPolicy",
                "1.0.0",
                new OperatorDefinition.Display("Nested config policy",
                        "Evaluates policy behavior controlled by nested configSchema.",
                        List.of("risk", "config")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Policy output."))
                ),
                SchemaEnvelope.object(configProperties, List.of("limits")),
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
                "risk-nested-config-policy",
                "Nested config policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    public static ResourceDescriptor loanApplicantDescriptor() {
        return new ResourceDescriptor(
                RESOURCE_ID,
                "https://example.test/api/loan-applicants/{applicantId}",
                "GET",
                Map.of("Accept", "application/json"),
                null,
                Duration.ofSeconds(3),
                new ParameterMapping(Map.of("applicantId", "ctx.params.applicantId"), Map.of(), null),
                new ResponseProtocol.HttpStatus(),
                "data"
        );
    }

    private record SingleResourceRegistry(ResourceDescriptor descriptor) implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (descriptor.resourceId().equals(resourceId)) {
                return descriptor;
            }
            throw new com.leanowtech.bloge.gateway.exception.ResourceNotFoundException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptor.resourceId().equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of(descriptor);
        }
    }
}
