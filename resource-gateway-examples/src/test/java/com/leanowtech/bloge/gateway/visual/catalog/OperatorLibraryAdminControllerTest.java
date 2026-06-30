package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for user-provided operator library admin APIs.
 */
class OperatorLibraryAdminControllerTest {

    private InMemoryOperatorLibraryRegistry registry;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new InMemoryOperatorLibraryRegistry();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OperatorLibraryAdminController controller = new OperatorLibraryAdminController(
                registry,
                new OperatorLibraryValidator()
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
                .andExpect(jsonPath("$.diagnostics[0].code").value("visual.library.invalid"))
                .andExpect(jsonPath("$.diagnostics[0].message").value("operatorRef 'risk:eligibility' already provided by library 'risk-policy'"));
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
}
