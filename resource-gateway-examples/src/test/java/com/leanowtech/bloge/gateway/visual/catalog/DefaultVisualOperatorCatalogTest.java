package com.leanowtech.bloge.gateway.visual.catalog;

import org.junit.jupiter.api.Test;

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
}
