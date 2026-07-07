package com.leanowtech.bloge.gateway.visual.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for DSL import preview HTTP API.
 */
class DslImportControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void previewAcceptsInlineSchemaAndReturnsVisualDraft() throws Exception {
        OperatorLibrary emptyLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty-risk-policy",
                "Empty risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of()
        );
        DslImportService service = new DslImportService(
                VisualCatalogTestSupport.catalogWithLibrary(emptyLibrary),
                new OperatorLibraryValidator()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(service)).build();

        mockMvc.perform(post("/api/visual/dsl-imports/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "sourceId", "migrated-eligibility.bloge",
                                "dsl", """
                                        graph migratedEligibility {
                                          input {
                                            score: Int
                                            amount: Decimal
                                          }
                                          node eligibility : "risk:eligibility" {
                                            input {
                                              score = ctx.score
                                              amount = ctx.amount
                                            }
                                          }
                                        }
                                        """,
                                "catalogIds", List.of(),
                                "inlineLibraries", List.of(VisualCatalogTestSupport.eligibilityLibrary("integer"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(DslVisualProjection.SCHEMA_VERSION))
                .andExpect(jsonPath("$.draft.graphName").value("migratedEligibility"))
                .andExpect(jsonPath("$.draft.nodes[0].operatorRef").value("risk:eligibility"))
                .andExpect(jsonPath("$.coverage.missingOperatorCount").value(0))
                .andExpect(jsonPath("$.draft.visualLayout.import.schemaNeutral").value(true))
                .andExpect(jsonPath("$.sourceMap.nodes.eligibility.startLine").value(6));
    }
}
