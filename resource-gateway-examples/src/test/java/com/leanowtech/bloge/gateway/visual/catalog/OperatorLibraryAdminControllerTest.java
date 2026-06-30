package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for user-provided operator library admin APIs.
 */
class OperatorLibraryAdminControllerTest {

    private InMemoryOperatorLibraryRegistry registry;
    private InMemoryGraphDraftRepository drafts;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new InMemoryOperatorLibraryRegistry();
        drafts = new InMemoryGraphDraftRepository();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OperatorLibraryAdminController controller = new OperatorLibraryAdminController(
                registry,
                new OperatorLibraryValidator(),
                drafts
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void validateLibraryReturnsDiagnosticsWithoutStoring() throws Exception {
        OperatorLibrary invalid = invalidArrayLibrary();

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsInvalidLibrary() throws Exception {
        OperatorLibrary invalid = invalidArrayLibrary();

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.schema.arrayItemsMissing"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsDuplicateOperatorRefsWithStructuredDiagnostics() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "duplicate-risk",
                  "displayName": "Duplicate risk",
                  "operators": [
                    {
                      "operatorRef": "risk:eligibility",
                      "ports": {
                        "outputs": [{
                          "name": "output",
                          "schema": { "schema": { "type": "object" } },
                          "required": true
                        }]
                      }
                    },
                    {
                      "operatorRef": "risk:eligibility",
                      "ports": {
                        "outputs": [{
                          "name": "output",
                          "schema": { "schema": { "type": "object" } },
                          "required": true
                        }]
                      }
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.operator.ref.duplicate"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/1/operatorRef"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void validateRejectsUnsupportedLibrarySchemaVersion() throws Exception {
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v2",
                "future-risk",
                "Future risk",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.library.schemaVersion.unsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/schemaVersion"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsupportedOperatorSchemaVersion() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition futureOperator = new OperatorDefinition(
                "bloge.visualOperator.v2",
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "future-risk",
                "Future risk",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(futureOperator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.schemaVersion.unsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/schemaVersion"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsUnsupportedOperatorCapabilities() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                new OperatorDefinition.Capabilities("NETWORK_MAGIC", "MAYBE", false, false),
                base.policy(),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-capabilities",
                "Bad capabilities",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.capability.effectUnsupported"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/capabilities/effect"))
                .andExpect(jsonPath("$.diagnostics[1].code")
                        .value("visual.operator.capability.idempotencyUnsupported"))
                .andExpect(jsonPath("$.diagnostics[1].target").value("/operators/0/capabilities/idempotency"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createRejectsPolicyScopesThatMixWildcardAndConcreteValues() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                new OperatorDefinition.Policy(List.of("*", "demo-tenant"), List.of("local"), List.of("browser")),
                base.lowering(),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-policy-scope",
                "Bad policy scope",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code")
                        .value("visual.operator.policy.scopeWildcardMixed"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/policy/tenants"));

        assertThat(registry.all()).isEmpty();
    }

    @Test
    void createStoresCanonicalizedLoweringMode() throws Exception {
        OperatorDefinition base = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition operator = new OperatorDefinition(
                base.schemaVersion(),
                base.operatorRef(),
                base.operatorVersion(),
                base.display(),
                base.source(),
                base.ports(),
                base.configSchema(),
                base.capabilities(),
                base.policy(),
                new OperatorDefinition.Lowering(" Transform ", base.lowering().operatorRef(),
                        base.lowering().parameters()),
                base.diagnostics()
        );
        OperatorLibrary library = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "canonical-lowering",
                "Canonical lowering",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].lowering.mode").value("transform"));

        assertThat(registry.find("canonical-lowering"))
                .map(stored -> stored.operators().getFirst().lowering().mode())
                .contains("transform");
    }

    @Test
    void createStoresValidLibrary() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.libraryId").value("risk-policy"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:eligibility"));
    }

    @Test
    void createRejectsOperatorRefAlreadyOwnedByAnotherLibrary() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(original)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorRefOwned"))
                .andExpect(jsonPath("$.diagnostics[0].message").value("operatorRef 'risk:eligibility' already provided by library 'risk-policy'"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));
    }

    @Test
    void validateReportsOperatorRefAlreadyOwnedByAnotherLibrary() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary duplicate = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy-copy",
                "Copy",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
        registry.upsert(original);

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorRefOwned"))
                .andExpect(jsonPath("$.diagnostics[0].message").value("operatorRef 'risk:eligibility' already provided by library 'risk-policy'"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/operators/0/operatorRef"));

        assertThat(registry.find("risk-policy-copy")).isEmpty();
    }

    @Test
    void createStoresOperatorPolicyForCatalogGate() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer",
                new OperatorDefinition.Policy(List.of("demo-tenant"), List.of("local"), List.of("browser")));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));

        mockMvc.perform(get("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].policy.tenants[0]").value("demo-tenant"))
                .andExpect(jsonPath("$.operators[0].policy.namespaces[0]").value("local"))
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));
    }

    @Test
    void createAcceptsLegacyPoliciesAlias() throws Exception {
        String libraryJson = """
                {
                  "libraryId": "legacy-policy",
                  "operators": [{
                    "operatorRef": "risk:legacyPolicy",
                    "policies": {
                      "allowedTenants": ["demo-tenant"],
                      "allowedNamespaces": ["local"],
                      "allowedEnvironments": ["browser"],
                      "requiredPermissions": ["legacy.permission"]
                    },
                    "ports": {
                      "outputs": [{
                        "name": "output",
                        "schema": { "schema": { "type": "object" } },
                        "required": true
                      }]
                    }
                  }]
                }
                """;

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(libraryJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operators[0].policy.tenants[0]").value("demo-tenant"))
                .andExpect(jsonPath("$.operators[0].policy.namespaces[0]").value("local"))
                .andExpect(jsonPath("$.operators[0].policy.environments[0]").value("browser"));
    }

    @Test
    void deleteRejectsLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(library);
    }

    @Test
    void deleteForceBypassesStoredDraftReferenceGuard() throws Exception {
        OperatorLibrary library = VisualCatalogTestSupport.eligibilityLibrary("integer");
        registry.upsert(library);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(delete("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true"))
                .andExpect(status().isNoContent());

        assertThat(registry.find("risk-policy")).isEmpty();
    }

    @Test
    void updateRejectsRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' cannot be replaced without force=true because draft 'draft-1@1' node 'eligibility' still uses operatorRef 'risk:eligibility'."));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateRejectsDisablingLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary disabled = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].message").value(
                        "Operator library 'risk-policy' cannot be replaced without force=true because draft 'draft-1@1' node 'eligibility' still uses operatorRef 'risk:eligibility'."));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void createRejectsReimportRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateReportsReimportRemovingOperatorRefReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateReportsDisablingLibraryReferencedByStoredDraft() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary disabled = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED);
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.inUse"))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateForceBypassesStoredDraftReferenceImpact() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer");
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty());

        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingUsedOperatorRefWithDifferentFingerprint() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        String originalFingerprint = original.operators().get(0).fingerprint();
        String replacementFingerprint = replacement.operators().get(0).fingerprint();
        registry.upsert(original);
        drafts.save(draftUsingOperator("risk:eligibility", originalFingerprint));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintDrift"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("saved fingerprint '" + originalFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value(org.hamcrest.Matchers.containsString("'" + replacementFingerprint + "'")))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(replacementFingerprint).isNotEqualTo(originalFingerprint);
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void validateWarnsWhenReplacingUsedOperatorRefWithoutSavedFingerprint() throws Exception {
        OperatorLibrary original = VisualCatalogTestSupport.eligibilityLibrary("integer");
        OperatorLibrary replacement = VisualCatalogTestSupport.eligibilityLibrary("string");
        registry.upsert(original);
        drafts.save(draftUsingOperatorWithoutFingerprint("risk:eligibility"));

        mockMvc.perform(post("/admin/visual-operator-libraries/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics[0].level").value("WARNING"))
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.operatorFingerprintSnapshotMissing"))
                .andExpect(jsonPath("$.diagnostics[0].message")
                        .value("Operator library 'risk-policy' changes operatorRef 'risk:eligibility' used by draft 'draft-1@1' node 'eligibility', but the draft has no saved operator fingerprint; review and resave the draft before execution."))
                .andExpect(jsonPath("$.diagnostics[0].target").value("/drafts/draft-1/nodes/0/operatorRef"));

        assertThat(replacement.operators().get(0).fingerprint()).isNotEqualTo(original.operators().get(0).fingerprint());
        assertThat(registry.find("risk-policy")).contains(original);
    }

    @Test
    void updateForceBypassesRemovedOperatorRefGuard() throws Exception {
        OperatorLibrary replacement = libraryWithScoreFactsOnly();
        registry.upsert(VisualCatalogTestSupport.multiOutputEligibilityLibrary("integer"));
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(replacement)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operators[0].operatorRef").value("risk:scoreFacts"));

        assertThat(registry.find("risk-policy")).contains(replacement);
    }

    @Test
    void updateForceBypassesDisabledLibraryReferenceGuard() throws Exception {
        OperatorLibrary disabled = eligibilityLibraryWithStatus(OperatorLibrary.STATUS_DISABLED);
        registry.upsert(VisualCatalogTestSupport.eligibilityLibrary("integer"));
        drafts.save(draftUsingOperator("risk:eligibility"));

        mockMvc.perform(put("/admin/visual-operator-libraries/risk-policy")
                        .param("force", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        assertThat(registry.find("risk-policy")).contains(disabled);
    }

    private static OperatorLibrary invalidArrayLibrary() {
        OperatorDefinition operator = new OperatorDefinition(
                "bloge.visualOperator.v1",
                "risk:badArray",
                "1.0.0",
                new OperatorDefinition.Display("Bad array", "Missing array item schema.", List.of("test")),
                new OperatorDefinition.Source("user-library", "", "", "", true),
                new OperatorDefinition.Ports(
                        List.of(),
                        List.of(new OperatorDefinition.Port(
                                "output",
                                new SchemaEnvelope("json-schema", "2020-12", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "items", Map.of("type", "array")
                                        )
                                )),
                                true,
                                "Output."
                        ))
                ),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:badArray", Map.of()),
                List.of()
        );
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "bad-array",
                "Bad array",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(operator)
        );
    }

    private static GraphDraft draftUsingOperator(String operatorRef) {
        return draftUsingOperator(operatorRef, "fingerprint");
    }

    private static GraphDraft draftUsingOperatorWithoutFingerprint(String operatorRef) {
        return draftUsingOperator(operatorRef, Map.of());
    }

    private static GraphDraft draftUsingOperator(String operatorRef, String fingerprint) {
        return draftUsingOperator(operatorRef, Map.of("eligibility", fingerprint));
    }

    private static OperatorLibrary eligibilityLibraryWithStatus(String status) {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                status,
                List.of(VisualCatalogTestSupport.eligibilityOperator("integer"))
        );
    }

    private static GraphDraft draftUsingOperator(String operatorRef, Map<String, String> fingerprints) {
        return new GraphDraft(
                "bloge.visualGraphDraft.v1",
                "draft-1",
                0,
                "libraryImpact",
                "demo-tenant",
                "local",
                "browser",
                "DRAFT",
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        operatorRef,
                        "Eligibility",
                        Map.of(),
                        Map.of(),
                        new GraphDraft.Position(0, 0)
                )),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                fingerprints
        );
    }

    private static OperatorLibrary libraryWithScoreFactsOnly() {
        return new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "risk-policy",
                "Risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of(VisualCatalogTestSupport.scoreFactsOperator())
        );
    }
}
