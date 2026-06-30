package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.resource.InMemoryResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
