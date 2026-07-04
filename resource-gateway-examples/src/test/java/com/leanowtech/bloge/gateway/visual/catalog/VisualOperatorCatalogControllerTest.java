package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the public visual operator catalog API.
 */
class VisualOperatorCatalogControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    void listReturnsPagedCatalogWindowWithFullMatchFacets() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new WindowedCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators")
                        .param("search", "risk")
                        .param("itemLimit", "2")
                        .param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators.length()").value(2))
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:b"))
                .andExpect(jsonPath("$.operators[1].operatorRef").value("risk:c"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.unfilteredTotal").value(5))
                .andExpect(jsonPath("$.displayedCount").value(2))
                .andExpect(jsonPath("$.itemLimit").value(2))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.filter.search").value("risk"))
                .andExpect(jsonPath("$.facets.total").value(3))
                .andExpect(jsonPath("$.facets.tags.risk").value(3))
                .andExpect(jsonPath("$.runtimeBindingProjections.length()").value(2))
                .andExpect(jsonPath("$.runtimeBindingProjections[0].operatorRef").value("risk:b"))
                .andExpect(jsonPath("$.runtimeBindingProjections[1].operatorRef").value("risk:c"))
                .andExpect(jsonPath("$.runtimeBindingProjectionStateCounts['binding-required']").value(1))
                .andExpect(jsonPath("$.runtimeBindingProjectionStateCounts['binding-bound']").value(1))
                .andExpect(jsonPath("$.runtimeBindingProjectionStateCounts['adapter-active']").value(1))
                .andExpect(jsonPath("$.executablePromotionProjections.length()").value(2))
                .andExpect(jsonPath("$.executablePromotionProjections[0].operatorRef").value("risk:b"))
                .andExpect(jsonPath("$.executablePromotionProjections[1].operatorRef").value("risk:c"))
                .andExpect(jsonPath("$.executablePromotionStateCounts['binding-required']").value(1))
                .andExpect(jsonPath("$.executablePromotionStateCounts['activation-required']").value(1))
                .andExpect(jsonPath("$.executablePromotionStateCounts['executor-integration-required']")
                        .value(1));
    }

    @Test
    void listIncludesRuntimeBindingProjections() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new ProjectedBindingCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeBindingProjections[0].operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.runtimeBindingProjections[0].projectionState").value("binding-bound"))
                .andExpect(jsonPath("$.runtimeBindingProjections[0].activeBindingId")
                        .value("risk-eligibility-native-v1"))
                .andExpect(jsonPath("$.runtimeBindingProjections[0].implementationBindingRequired").value(false))
                .andExpect(jsonPath("$.runtimeBindingProjections[0].runtimeActivationRequired").value(true))
                .andExpect(jsonPath("$.runtimeBindingProjectionStateCounts['binding-bound']").value(1))
                .andExpect(jsonPath("$.executablePromotionProjections[0].operatorRef")
                        .value("risk:eligibility"))
                .andExpect(jsonPath("$.executablePromotionProjections[0].promotionState")
                        .value("activation-required"))
                .andExpect(jsonPath("$.executablePromotionProjections[0].requiredNextAction")
                        .value("ACTIVATE_RUNTIME_ADAPTER"))
                .andExpect(jsonPath("$.executablePromotionProjections[0].promotionReady").value(false))
                .andExpect(jsonPath("$.executablePromotionProjections[0].executableNow").value(false))
                .andExpect(jsonPath("$.executablePromotionStateCounts['activation-required']").value(1));
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
    void getCanIncludeRuntimeBindingAndPromotionProjections() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(
                new ProjectedBindingCatalog()
        )).build();

        mockMvc.perform(get("/api/visual/operators/risk:eligibility")
                        .param("includeProjections", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualOperatorDetail.v1"))
                .andExpect(jsonPath("$.operator.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.runtimeBindingProjection.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.runtimeBindingProjection.projectionState").value("binding-bound"))
                .andExpect(jsonPath("$.runtimeBindingProjection.activeBindingId")
                        .value("risk-eligibility-native-v1"))
                .andExpect(jsonPath("$.executablePromotionProjection.operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.executablePromotionProjection.promotionState")
                        .value("activation-required"))
                .andExpect(jsonPath("$.executablePromotionProjection.requiredNextAction")
                        .value("ACTIVATE_RUNTIME_ADAPTER"))
                .andExpect(jsonPath("$.filter.includeDeprecated").value(false));
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

    @Test
    void fitCandidatesReturnsSchemaCompatibleCatalogWindow() throws Exception {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(fitLibrary());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new VisualOperatorCatalogController(catalog)).build();
        GraphDraft draft = new GraphDraft(
                GraphDraft.SCHEMA_VERSION,
                "draft-fit",
                0,
                "fitGraph",
                "demo-tenant",
                "local",
                "browser",
                GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "profile",
                        "risk:profile",
                        "Profile",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(80, 120)
                )),
                List.of(),
                Map.of(),
                GraphDraft.OutputSelection.empty()
        );
        OperatorFitCandidatesRequest request = new OperatorFitCandidatesRequest(
                draft,
                new GraphDraft.Endpoint("profile", "output", ""),
                new OperatorCatalogQuery("", List.of(), false, false,
                        "demo-tenant", "local", "browser",
                        List.of("user-library"), List.of("fit-policy"), List.of(), List.of(), List.of()),
                "input",
                false,
                10,
                0
        );

        mockMvc.perform(post("/api/visual/operators/fit-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualOperatorFitCatalog.v1"))
                .andExpect(jsonPath("$.source.nodeId").value("profile"))
                .andExpect(jsonPath("$.sourceSchemaType").value("object"))
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.operators.length()").value(2))
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:design-eligibility"))
                .andExpect(jsonPath("$.operators[1].operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.fitCandidates[0].accepted").value(true))
                .andExpect(jsonPath("$.fitCandidates[0].acceptedTargetCount").value(1))
                .andExpect(jsonPath("$.fitCandidates[0].targets[0].targetSurface").value("input"))
                .andExpect(jsonPath("$.fitCandidates[0].targets[0].targetPort").value("inputs"))
                .andExpect(jsonPath("$.runtimeBindingProjections.length()").value(2))
                .andExpect(jsonPath("$.facets.runtimeReadinessStates['design-only']").value(1));
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

    private static final class ProjectedBindingCatalog implements VisualOperatorCatalog {
        private final OperatorDefinition operator = VisualCatalogTestSupport.designOnlyEligibilityOperator("integer");

        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            return List.of(operator);
        }

        @Override
        public List<OperatorRuntimeBindingProjection> runtimeBindingProjections(
                OperatorCatalogQuery query,
                List<OperatorDefinition> operators) {
            return List.of(new OperatorRuntimeBindingProjection(
                    OperatorRuntimeBindingProjection.SCHEMA_VERSION,
                    operator.operatorRef(),
                    operator.fingerprint(),
                    operator.runtimeReadiness().state(),
                    false,
                    false,
                    true,
                    "binding-bound",
                    "info",
                    "Runtime binding bound",
                    "An active implementation binding is present; EXECUTABLE promotion still waits for runtime adapter activation.",
                    "risk-eligibility-native-v1",
                    2,
                    "bound",
                    "native",
                    "com.acme.risk.RiskEligibilityOperator",
                    "risk-platform",
                    java.time.Instant.EPOCH,
                    List.of()
            ));
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return Optional.of(operator);
        }
    }

    private static final class WindowedCatalog implements VisualOperatorCatalog {
        private final List<OperatorDefinition> operators = List.of(
                taggedOperator("risk:a", "risk-policy", List.of("risk", "alpha")),
                taggedOperator("risk:b", "risk-policy", List.of("risk", "beta")),
                taggedOperator("risk:c", "risk-policy", List.of("risk", "gamma")),
                taggedOperator("fraud:a", "fraud-policy", List.of("fraud")),
                taggedOperator("fraud:b", "fraud-policy", List.of("fraud"))
        );

        @Override
        public List<OperatorDefinition> list(OperatorCatalogQuery query) {
            if (query.search().isBlank()) {
                return operators;
            }
            return operators.stream()
                    .filter(operator -> operator.operatorRef().contains(query.search()))
                    .toList();
        }

        @Override
        public Optional<OperatorDefinition> find(String operatorRef) {
            return operators.stream()
                    .filter(operator -> operator.operatorRef().equals(operatorRef))
                    .findFirst();
        }

        @Override
        public List<OperatorRuntimeBindingProjection> runtimeBindingProjections(
                OperatorCatalogQuery query,
                List<OperatorDefinition> operators) {
            return operators.stream()
                    .map(WindowedCatalog::projectionFor)
                    .toList();
        }

        private static OperatorRuntimeBindingProjection projectionFor(OperatorDefinition operator) {
            String state = switch (operator.operatorRef()) {
                case "risk:b" -> "binding-bound";
                case "risk:c" -> "adapter-active";
                default -> "binding-required";
            };
            return new OperatorRuntimeBindingProjection(
                    OperatorRuntimeBindingProjection.SCHEMA_VERSION,
                    operator.operatorRef(),
                    operator.fingerprint(),
                    operator.runtimeReadiness().state(),
                    false,
                    "binding-required".equals(state),
                    "binding-bound".equals(state),
                    state,
                    "info",
                    "Projection for " + operator.operatorRef(),
                    "Paged catalog projection fixture.",
                    "binding-required".equals(state) ? "" : operator.operatorRef() + "-binding",
                    "binding-required".equals(state) ? 0 : 1,
                    "binding-required".equals(state) ? "" : "bound",
                    "native",
                    "com.acme." + operator.operatorRef(),
                    "risk-platform",
                    java.time.Instant.EPOCH,
                    "adapter-active".equals(state) ? operator.operatorRef() + "-activation" : "",
                    "adapter-active".equals(state) ? 1 : 0,
                    "adapter-active".equals(state) ? "active" : "",
                    "adapter-active".equals(state) ? "healthy" : "",
                    "adapter-active".equals(state) ? "prod" : "",
                    java.time.Instant.EPOCH,
                    List.of()
            );
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

    private static OperatorDefinition taggedOperator(String operatorRef, String libraryId, List<String> tags) {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition.Source source = base.source();
        return new OperatorDefinition(
                base.schemaVersion(),
                operatorRef,
                base.operatorVersion(),
                new OperatorDefinition.Display(operatorRef, "Paged catalog fixture.", tags),
                new OperatorDefinition.Source(
                        source.kind(),
                        source.resourceId(),
                        source.method(),
                        source.urlTemplate(),
                        source.virtual(),
                        libraryId
                ),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
    }

    private static OperatorLibrary fitLibrary() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "fit-policy",
                "Fit policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(
                        profileSourceOperator(),
                        fitEligibilityOperator("risk:eligibility", "integer", "transform"),
                        fitEligibilityOperator("risk:design-eligibility", "integer", "design"),
                        fitEligibilityOperator("risk:string-eligibility", "string", "transform")
                )
        );
    }

    private static OperatorDefinition profileSourceOperator() {
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("score", Map.of("type", "integer"));
        outputProperties.put("amount", Map.of("type", "number"));
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:profile",
                "1.0.0",
                new OperatorDefinition.Display("Profile", "Source output fixture.", List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of("score", "amount")),
                                true,
                                "Profile output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("transform", "transform", Map.of(
                        "assignments", Map.of(
                                "score", "{{input.score}}",
                                "amount", "{{input.amount}}"
                        )
                )),
                List.of()
        );
    }

    private static OperatorDefinition fitEligibilityOperator(String operatorRef, String scoreType, String mode) {
        Map<String, Object> inputProperties = new LinkedHashMap<>();
        inputProperties.put("score", Map.of("type", scoreType));
        inputProperties.put("amount", Map.of("type", "number"));
        Map<String, Object> outputProperties = new LinkedHashMap<>();
        outputProperties.put("eligible", Map.of("type", "boolean"));
        return new OperatorDefinition(
                "bloge.visualOperator.v1",
                operatorRef,
                "1.0.0",
                new OperatorDefinition.Display(operatorRef, "Fit target fixture.", List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(new OperatorDefinition.Port("inputs",
                                SchemaEnvelope.object(inputProperties, List.of("score", "amount")),
                                true,
                                "Eligibility inputs.")),
                        List.of(new OperatorDefinition.Port("output",
                                SchemaEnvelope.object(outputProperties, List.of()),
                                true,
                                "Eligibility output."))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering(mode, "transform", Map.of(
                        "assignments", Map.of("eligible", "{{input.score}} >= 700")
                )),
                List.of()
        );
    }
}
