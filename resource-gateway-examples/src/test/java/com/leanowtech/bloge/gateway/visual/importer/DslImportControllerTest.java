package com.leanowtech.bloge.gateway.visual.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;

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
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(emptyLibrary);
        DslImportService service = new DslImportService(
                catalog,
                new OperatorLibraryValidator()
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                new InMemoryGraphDraftRepository(),
                new GraphDraftValidator(catalog),
                catalog
        )).build();

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

    @Test
    void rewriteGateAllowsSupportedTransformDsl() throws Exception {
        OperatorLibrary emptyLibrary = new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1",
                "empty-risk-policy",
                "Empty risk policy operators",
                "1.0.0",
                "risk-team",
                "ACTIVE",
                List.of()
        );
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(emptyLibrary);
        DslImportService service = new DslImportService(catalog, new OperatorLibraryValidator());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                new InMemoryGraphDraftRepository(),
                new GraphDraftValidator(catalog),
                catalog
        )).build();

        mockMvc.perform(post("/api/visual/dsl-imports/rewrite-gate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "sourceId", "transform-only.bloge",
                                "dsl", """
                                        graph transformOnly {
                                          input {
                                            score: Int
                                          }
                                          output {
                                            score: Int
                                          }
                                          transform response {
                                            score = ctx.score
                                          }
                                        }
                                        """
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(DslRewriteGateResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.sourceId").value("transform-only.bloge"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.decision").value("ALLOW_REWRITE"))
                .andExpect(jsonPath("$.roundTrip.status").value("SUPPORTED"))
                .andExpect(jsonPath("$.generatedDsl").isNotEmpty());
    }

    @Test
    void rewriteGateBlocksSemanticDrift() throws Exception {
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer")
        );
        DslImportService service = new DslImportService(catalog, new OperatorLibraryValidator());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                new InMemoryGraphDraftRepository(),
                new GraphDraftValidator(catalog),
                catalog
        )).build();

        mockMvc.perform(post("/api/visual/dsl-imports/rewrite-gate")
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
                                          transform response {
                                            eligible = eligibility.output.eligible
                                          }
                                        }
                                        """,
                                "catalogIds", List.of("risk-policy")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.decision").value("BLOCK_SEMANTIC_DRIFT"))
                .andExpect(jsonPath("$.roundTrip.status").value("DRIFT"))
                .andExpect(jsonPath("$.generatedDsl").isNotEmpty());
    }

    @Test
    void commitReprojectsDslAndStoresGovernedDraftWithSourceMap() throws Exception {
        var catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer")
        );
        GraphDraftRepository repository = new InMemoryGraphDraftRepository();
        DslImportService service = new DslImportService(catalog, new OperatorLibraryValidator());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                repository,
                new GraphDraftValidator(catalog),
                catalog
        )).build();

        mockMvc.perform(post("/api/visual/dsl-imports/commit")
                        .param("actor", "author")
                        .param("changeSource", "legacy-dsl-import")
                        .param("changeSummary", "Commit migrated DSL")
                        .param("reason", "migration demo")
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
                                "catalogIds", List.of("risk-policy"),
                                "mode", "commit"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualGraphDraftImportResult.v1"))
                .andExpect(jsonPath("$.imported").value(true))
                .andExpect(jsonPath("$.draft.draftId").isNotEmpty())
                .andExpect(jsonPath("$.draft.revision").value(1))
                .andExpect(jsonPath("$.draft.graphName").value("migratedEligibility"))
                .andExpect(jsonPath("$.draft.visualLayout.import.schemaNeutral").value(true))
                .andExpect(jsonPath("$.draft.visualLayout.import.sourceMap.nodes.eligibility.startLine").value(6))
                .andExpect(jsonPath("$.draft.revisionMetadata.createdBy").value("author"))
                .andExpect(jsonPath("$.draft.revisionMetadata.updatedBy").value("author"))
                .andExpect(jsonPath("$.draft.revisionMetadata.changeSource").value("legacy-dsl-import"))
                .andExpect(jsonPath("$.draft.revisionMetadata.reason").value("migration demo"));

        org.assertj.core.api.Assertions.assertThat(repository.all()).hasSize(1);
    }
}
