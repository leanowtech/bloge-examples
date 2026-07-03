package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the public visual operator catalog API.
 */
class VisualOperatorCatalogControllerTest {

    @Test
    void listIncludesCatalogDiagnostics() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new DiagnosticCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators")
                        .param("tenantId", "demo-tenant")
                        .param("namespace", "local")
                        .param("environment", "browser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualOperatorCatalog.v1"))
                .andExpect(jsonPath("$.operators").isArray())
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.catalog.operatorHiddenMalformed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/libraries/risk-policy/operators/0"));
    }

    @Test
    void listPassesCatalogFacetFiltersToQuery() throws Exception {
        CapturingCatalog catalog = new CapturingCatalog();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(catalog)).build();

        mockMvc.perform(get("/api/visual/operators")
                        .param("sourceKind", "user-library")
                        .param("sourceKind", "resource-descriptor")
                        .param("operatorLibraryId", "risk-policy")
                        .param("operatorLibraryId", " fraud-policy ")
                        .param("loweringMode", "design")
                        .param("capability", "runtime-executable")
                        .param("capability", "requires_secret")
                        .param("runtimeReadiness", "governance_review")
                        .param("runtimeReadiness", "design-only"))
                .andExpect(status().isOk());

        OperatorCatalogQuery query = catalog.lastQuery.get();
        assertThat(query.sourceKinds()).containsExactly("user-library", "resource-descriptor");
        assertThat(query.operatorLibraryIds()).containsExactly("risk-policy", "fraud-policy");
        assertThat(query.loweringModes()).containsExactly("design");
        assertThat(query.capabilities()).containsExactly("runtime-executable", "requires-secret");
        assertThat(query.runtimeReadinessStates()).containsExactly("governance-review", "design-only");
    }

    @Test
    void listIncludesCatalogFacets() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new FacetedCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facets.total").value(2))
                .andExpect(jsonPath("$.facets.sourceKinds['user-library']").value(2))
                .andExpect(jsonPath("$.facets.operatorLibraryIds['risk-policy']").value(1))
                .andExpect(jsonPath("$.facets.loweringModes.transform").value(1))
                .andExpect(jsonPath("$.facets.loweringModes.design").value(1))
                .andExpect(jsonPath("$.facets.capabilities['runtime-executable']").value(1))
                .andExpect(jsonPath("$.facets.capabilities['design-only']").value(1))
                .andExpect(jsonPath("$.facets.runtimeReadinessStates['runtime-executable']").value(1))
                .andExpect(jsonPath("$.facets.runtimeReadinessStates['design-only']").value(1))
                .andExpect(jsonPath("$.operators[0].runtimeReadiness.state").value("RUNTIME_EXECUTABLE"))
                .andExpect(jsonPath("$.operators[0].runtimeReadiness.executable").value(true))
                .andExpect(jsonPath("$.operators[1].runtimeReadiness.state").value("DESIGN_ONLY"))
                .andExpect(jsonPath("$.operators[1].runtimeReadiness.artifactKinds[0]").value("DESIGN"));
    }

    @Test
    void getReturnsVisibleOperatorDefinitionAndPassesCatalogVisibilityFilters() throws Exception {
        OperatorDefinition operator = VisualCatalogTestSupport.catalogWithLoanApplicantResource()
                .find("resource:" + VisualCatalogTestSupport.RESOURCE_ID)
                .orElseThrow();
        CapturingCatalog catalog = new CapturingCatalog(List.of(operator));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(catalog)).build();

        mockMvc.perform(get("/api/visual/operators/resource:loan-applicant-service.getProfile")
                        .param("resourceOnly", "true")
                        .param("includeDeprecated", "true")
                        .param("tenantId", "demo-tenant")
                        .param("namespace", "local")
                        .param("environment", "browser")
                        .param("sourceKind", "resource-descriptor")
                        .param("loweringMode", "resource-descriptor")
                        .param("capability", "runtime-executable")
                        .param("capability", "requires_secret")
                        .param("runtimeReadiness", "governance_review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualOperator.v1"))
                .andExpect(jsonPath("$.operatorRef").value("resource:loan-applicant-service.getProfile"))
                .andExpect(jsonPath("$.runtimeReadiness").exists());

        OperatorCatalogQuery query = catalog.lastQuery.get();
        assertThat(query.search()).isEmpty();
        assertThat(query.tags()).isEmpty();
        assertThat(query.resourceOnly()).isTrue();
        assertThat(query.includeDeprecated()).isTrue();
        assertThat(query.tenantId()).isEqualTo("demo-tenant");
        assertThat(query.namespace()).isEqualTo("local");
        assertThat(query.environment()).isEqualTo("browser");
        assertThat(query.sourceKinds()).containsExactly("resource-descriptor");
        assertThat(query.operatorLibraryIds()).isEmpty();
        assertThat(query.loweringModes()).containsExactly("resource-descriptor");
        assertThat(query.capabilities()).containsExactly("runtime-executable", "requires-secret");
        assertThat(query.runtimeReadinessStates()).containsExactly("governance-review");
    }

    @Test
    void getReturnsNotFoundWhenOperatorIsNotVisible() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new CapturingCatalog(List.of(VisualCatalogTestSupport.eligibilityOperator("integer")))
        )).build();

        mockMvc.perform(get("/api/visual/operators/risk:missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDoesNotBypassDeprecatedVisibilityWithFind() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new DeprecatedOnlyCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators/risk:eligibility"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/visual/operators/risk:eligibility")
                        .param("includeDeprecated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operatorRef").value("risk:eligibility"));
    }

    private static final class DiagnosticCatalog implements VisualOperatorCatalog {
        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            return List.of();
        }

        @Override
        public List<VisualDiagnostic> diagnostics(OperatorCatalogQuery query) {
            return List.of(VisualDiagnostic.warning("visual.catalog.operatorHiddenMalformed",
                    "Operator library 'risk-policy' contains a null operator entry hidden from the visual catalog.",
                    "/libraries/risk-policy/operators/0"));
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return Optional.empty();
        }
    }

    private static final class CapturingCatalog implements VisualOperatorCatalog {
        private final AtomicReference<OperatorCatalogQuery> lastQuery = new AtomicReference<>();
        private final List<OperatorDefinition> operators;

        private CapturingCatalog() {
            this(List.of());
        }

        private CapturingCatalog(List<OperatorDefinition> operators) {
            this.operators = operators;
        }

        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            lastQuery.set(query);
            return operators;
        }

        @Override
        public List<VisualDiagnostic> diagnostics(OperatorCatalogQuery query) {
            return List.of();
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return Optional.empty();
        }
    }

    private static final class DeprecatedOnlyCatalog implements VisualOperatorCatalog {
        private final OperatorDefinition operator = VisualCatalogTestSupport.eligibilityOperator("integer");

        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            return query.includeDeprecated() ? List.of(operator) : List.of();
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return Optional.of(operator);
        }
    }

    private static final class FacetedCatalog implements VisualOperatorCatalog {
        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            return List.of(
                    ownedBy("risk-policy", VisualCatalogTestSupport.eligibilityOperator("integer")),
                    VisualCatalogTestSupport.designOnlyEligibilityOperator("integer")
            );
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return Optional.empty();
        }
    }

    private static OperatorDefinition ownedBy(String libraryId, OperatorDefinition operator) {
        OperatorDefinition.Source source = operator.source();
        return new OperatorDefinition(
                operator.schemaVersion(),
                operator.operatorRef(),
                operator.operatorVersion(),
                operator.fingerprint(),
                operator.display(),
                new OperatorDefinition.Source(
                        source.kind(),
                        source.resourceId(),
                        source.method(),
                        source.urlTemplate(),
                        source.virtual(),
                        libraryId
                ),
                operator.ports(),
                operator.configSchema(),
                operator.capabilities(),
                operator.policy(),
                operator.lowering(),
                operator.diagnostics(),
                operator.runtimeReadiness()
        );
    }
}
