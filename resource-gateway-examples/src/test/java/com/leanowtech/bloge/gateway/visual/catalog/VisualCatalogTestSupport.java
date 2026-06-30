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
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("items", Map.of(
                "type", "array",
                "items", Map.of("type", itemType)
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
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("items", Map.of(
                "type", "array",
                "items", Map.of("type", itemType)
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
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-list-compatibility",
                "List compatibility operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(listFactsOperator(sourceItemType), listConsumerOperator(targetItemType))
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
