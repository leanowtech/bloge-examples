package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the visual operator catalog.
 */
class DefaultVisualOperatorCatalogTest {

    @Test
    void projectsResourceDescriptorAsSchemaAwareVirtualOperator() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();

        OperatorDefinition operator = catalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)
                .orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("resource-descriptor");
        assertThat(operator.lowering().operatorRef()).isEqualTo("httpResource");
        assertThat(operator.ports().inputs().getFirst().schema().required()).containsExactly("applicantId");
        assertThat(operator.ports().outputs().getFirst().schema().properties()).containsKeys("score", "segment");
    }

    @Test
    void catalogOperatorsExposeSchemasAcceptedBySharedVisualSchemaGate() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();

        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (OperatorDefinition operator : catalog.list(OperatorCatalogQuery.all())) {
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                    operator.configSchema(),
                    "/operators/" + operator.operatorRef() + "/configSchema"));
            for (OperatorDefinition.Port port : operator.ports().inputs()) {
                diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                        port.schema(),
                        "/operators/" + operator.operatorRef() + "/ports/inputs/" + port.name() + "/schema"));
            }
            for (OperatorDefinition.Port port : operator.ports().outputs()) {
                diagnostics.addAll(VisualSchemaValidator.validateEnvelope(
                        port.schema(),
                        "/operators/" + operator.operatorRef() + "/ports/outputs/" + port.name() + "/schema"));
            }
        }

        assertThat(diagnostics)
                .filteredOn(VisualDiagnostic::error)
                .isEmpty();
    }

    @Test
    void includesUserProvidedOperatorLibraryDefinitions() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));

        OperatorDefinition operator = catalog.find("risk:eligibility").orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("user-library");
        assertThat(operator.lowering().mode()).isEqualTo("transform");
        assertThat(operator.ports().inputs().getFirst().schema().required())
                .containsExactly("score", "amount");
    }

    @Test
    void includesJavaOperatorsFromRuntimeRegistry() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("normalizeText", new NormalizeTextOperator());
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                OperatorLibraryRegistry.empty(),
                new JavaOperatorInventoryProjector(registry)
        );

        OperatorDefinition operator = catalog.find("normalizeText").orElseThrow();

        assertThat(operator.source().kind()).isEqualTo("java-operator");
        assertThat(operator.lowering().mode()).isEqualTo("native");
        assertThat(operator.lowering().operatorRef()).isEqualTo("normalizeText");
        assertThat(operator.ports().inputs().getFirst().schema().properties()).containsKey("raw");
        assertThat(operator.ports().outputs().getFirst().schema().properties()).containsKey("value");
    }

    @Test
    void computesStableFingerprintFromSchemaAndLoweringMetadata() {
        OperatorDefinition integerScore = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition sameDefinition = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition stringScore = VisualCatalogTestSupport.eligibilityOperator("string");

        assertThat(integerScore.fingerprint()).startsWith("sha256:");
        assertThat(integerScore.fingerprint()).hasSize(71);
        assertThat(integerScore.fingerprint()).isEqualTo(sameDefinition.fingerprint());
        assertThat(integerScore.fingerprint()).isNotEqualTo(stringScore.fingerprint());
    }

    @Test
    void ignoresUserSuppliedFingerprintAndRecomputesServerSide() {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");

        OperatorDefinition forged = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                "sha256:forged",
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.lowering(),
                base.diagnostics()
        );

        assertThat(forged.fingerprint()).isEqualTo(base.fingerprint());
    }

    @Test
    void filtersOperatorsByAuthoringScopePolicy() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer",
                        new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("prod"))));

        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "demo-tenant", "local", "prod")))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, false,
                "demo-tenant", "local", "browser")))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:eligibility");
    }

    @Test
    void filtersUserLibraryOperatorsByLifecycleStatus() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library("active-policy", "ACTIVE", VisualCatalogTestSupport.eligibilityOperator("integer")));
        libraries.upsert(library("deprecated-policy", "DEPRECATED", VisualCatalogTestSupport.numericPassOperator()));
        libraries.upsert(library("disabled-policy", "DISABLED", VisualCatalogTestSupport.scoreFactsOperator()));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility")
                .doesNotContain("risk:numericPass", "risk:scoreFacts");
        assertThat(catalog.list(new OperatorCatalogQuery("", List.of(), false, true)))
                .extracting(OperatorDefinition::operatorRef)
                .contains("risk:eligibility", "risk:numericPass")
                .doesNotContain("risk:scoreFacts");
        assertThat(catalog.find("risk:scoreFacts")).isEmpty();
    }

    @Test
    void filtersResourceBackedOperatorsByContractLifecycleStatus() {
        DefaultVisualOperatorCatalog deprecatedCatalog = catalogWithResourceContractStatus("DEPRECATED");
        DefaultVisualOperatorCatalog disabledCatalog = catalogWithResourceContractStatus("DISABLED");

        assertThat(deprecatedCatalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(deprecatedCatalog.list(new OperatorCatalogQuery("", List.of(), true, true)))
                .extracting(OperatorDefinition::operatorRef)
                .contains("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(deprecatedCatalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)).isPresent();

        assertThat(disabledCatalog.list(new OperatorCatalogQuery("", List.of(), true, true)))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("resource:" + VisualCatalogTestSupport.RESOURCE_ID);
        assertThat(disabledCatalog.find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)).isEmpty();
    }

    @Test
    void resolvesDeprecatedLibraryOperatorsForStoredDrafts() {
        InMemoryOperatorLibraryRegistry libraries = new InMemoryOperatorLibraryRegistry();
        libraries.upsert(library("deprecated-policy", "DEPRECATED", VisualCatalogTestSupport.numericPassOperator()));
        DefaultVisualOperatorCatalog catalog = new DefaultVisualOperatorCatalog(
                VisualCatalogTestSupport.emptyResourceRegistry(),
                new InMemoryResourceDesignContractRegistry(),
                new ResourceVirtualOperatorProjector(),
                libraries
        );

        assertThat(catalog.list(OperatorCatalogQuery.all()))
                .extracting(OperatorDefinition::operatorRef)
                .doesNotContain("risk:numericPass");
        assertThat(catalog.find("risk:numericPass")).isPresent();
    }

    private static OperatorLibrary library(String libraryId, String status, OperatorDefinition operator) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                libraryId,
                libraryId,
                "1.0.0",
                "risk-team",
                status,
                List.of(operator)
        );
    }

    private static DefaultVisualOperatorCatalog catalogWithResourceContractStatus(String status) {
        ResourceDescriptor descriptor = VisualCatalogTestSupport.loanApplicantDescriptor();
        InMemoryResourceDesignContractRegistry contracts = new InMemoryResourceDesignContractRegistry();
        contracts.upsert(new ResourceDesignContract(
                "contract:" + VisualCatalogTestSupport.RESOURCE_ID,
                VisualCatalogTestSupport.RESOURCE_ID,
                "Loan applicant profile",
                "Reads applicant facts.",
                List.of("loan", "applicant"),
                SchemaEnvelope.object(Map.of(
                        "applicantId", Map.of("type", "string")
                ), List.of("applicantId")),
                SchemaEnvelope.object(Map.of(
                        "score", Map.of("type", "integer")
                ), List.of()),
                Map.of(),
                status
        ));
        return new DefaultVisualOperatorCatalog(
                new SingleResourceRegistry(descriptor),
                contracts,
                new ResourceVirtualOperatorProjector()
        );
    }

    private record NormalizeInput(String raw) {
    }

    private record NormalizeOutput(String value) {
    }

    private static final class NormalizeTextOperator implements Operator<NormalizeInput, NormalizeOutput> {
        @Override
        public NormalizeOutput execute(NormalizeInput input, OperatorContext ctx) {
            return new NormalizeOutput(input.raw().trim().toLowerCase(java.util.Locale.ROOT));
        }
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
