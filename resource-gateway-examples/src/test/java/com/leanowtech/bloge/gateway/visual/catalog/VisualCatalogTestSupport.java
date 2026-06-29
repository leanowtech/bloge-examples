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
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(eligibilityOperator(scoreType))
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
