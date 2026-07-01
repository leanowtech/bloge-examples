package com.leanowtech.bloge.gateway.visual.catalog;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

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
}
