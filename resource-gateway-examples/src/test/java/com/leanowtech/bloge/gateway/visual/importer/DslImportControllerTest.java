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
    void rewriteGateBlocksDeclaredTerminalOutputThatCannotBePreserved() throws Exception {
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
                                "sourceId", "transform-with-terminal-output.bloge",
                                "dsl", """
                                        graph transformWithTerminalOutput {
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
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.decision").value("BLOCK_SEMANTIC_DRIFT"))
                .andExpect(jsonPath("$.roundTrip.status").value("DRIFT"))
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
    void batchReportAggregatesSchemaNeutralMigrationReadiness() throws Exception {
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

        mockMvc.perform(post("/api/visual/dsl-imports/batch-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "includeDrafts", true,
                                "sources", List.of(
                                        Map.of(
                                                "sourceId", "supported-transform.bloge",
                                                "dsl", """
                                                        graph supportedTransform {
                                                          input {
                                                            score: Int
                                                          }
                                                          transform response {
                                                            score = ctx.score
                                                          }
                                                        }
                                                        """
                                        ),
                                        Map.of(
                                                "sourceId", "missing-operator.bloge",
                                                "dsl", """
                                                        graph missingOperator {
                                                          node eligibility : "risk:missing" {
                                                          }
                                                        }
                                                        """
                                        ),
                                        Map.of(
                                                "sourceId", "broken.bloge",
                                                "dsl", "graph {"
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(DslImportBatchReport.SCHEMA_VERSION))
                .andExpect(jsonPath("$.summary.sourceCount").value(3))
                .andExpect(jsonPath("$.summary.renderableSourceCount").value(2))
                .andExpect(jsonPath("$.summary.fullyProjectedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.repairableSourceCount").value(1))
                .andExpect(jsonPath("$.summary.blockedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.rewriteAllowedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.rewriteBlockedSourceCount").value(2))
                .andExpect(jsonPath("$.summary.totalMissingOperatorCount").value(1))
                .andExpect(jsonPath("$.summary.roundTripStatusCounts.SUPPORTED").value(1))
                .andExpect(jsonPath("$.summary.rewriteDecisionCounts.ALLOW_REWRITE").value(1))
                .andExpect(jsonPath("$.summary.rewriteDecisionCounts.BLOCK_IMPORT_DIAGNOSTICS").value(1))
                .andExpect(jsonPath("$.summary.rewriteDecisionCounts.BLOCK_INCOMPLETE_EVIDENCE").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("supported-transform.bloge"))
                .andExpect(jsonPath("$.items[0].renderable").value(true))
                .andExpect(jsonPath("$.items[0].fullyProjected").value(true))
                .andExpect(jsonPath("$.items[0].rewriteAllowed").value(true))
                .andExpect(jsonPath("$.items[0].roundTrip.status").value("SUPPORTED"))
                .andExpect(jsonPath("$.items[0].draft.graphName").value("supportedTransform"))
                .andExpect(jsonPath("$.items[1].sourceId").value("missing-operator.bloge"))
                .andExpect(jsonPath("$.items[1].renderable").value(true))
                .andExpect(jsonPath("$.items[1].fullyProjected").value(false))
                .andExpect(jsonPath("$.items[1].needsRepair").value(true))
                .andExpect(jsonPath("$.items[1].coverage.missingOperatorCount").value(1))
                .andExpect(jsonPath("$.items[1].rewriteDecision").value("BLOCK_INCOMPLETE_EVIDENCE"))
                .andExpect(jsonPath("$.items[1].diagnosticLevelCounts.WARNING").value(1))
                .andExpect(jsonPath("$.items[1].draft.visualLayout.import.projectionMode").value("topology-only"))
                .andExpect(jsonPath("$.items[1].draft.visualLayout.import.missingOperatorRefs[0]")
                        .value("risk:missing"))
                .andExpect(jsonPath("$.items[2].sourceId").value("broken.bloge"))
                .andExpect(jsonPath("$.items[2].renderable").value(false))
                .andExpect(jsonPath("$.items[2].rewriteDecision").value("BLOCK_IMPORT_DIAGNOSTICS"));
    }

    @Test
    void batchCommitStoresRenderableAndRepairableDraftsButSkipsBrokenSources() throws Exception {
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
        GraphDraftRepository repository = new InMemoryGraphDraftRepository();
        DslImportService service = new DslImportService(catalog, new OperatorLibraryValidator());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                repository,
                new GraphDraftValidator(catalog),
                catalog
        )).build();

        mockMvc.perform(post("/api/visual/dsl-imports/batch-commit")
                        .param("actor", "migration-bot")
                        .param("changeSource", "legacy-dsl-batch-import")
                        .param("reason", "batch migration demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "sources", List.of(
                                        Map.of(
                                                "sourceId", "supported-transform.bloge",
                                                "dsl", """
                                                        graph supportedTransform {
                                                          input {
                                                            score: Int
                                                          }
                                                          transform response {
                                                            score = ctx.score
                                                          }
                                                        }
                                                        """
                                        ),
                                        Map.of(
                                                "sourceId", "missing-operator.bloge",
                                                "dsl", """
                                                        graph missingOperator {
                                                          node eligibility : "risk:missing" {
                                                          }
                                                        }
                                                        """
                                        ),
                                        Map.of(
                                                "sourceId", "broken.bloge",
                                                "dsl", "graph {"
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(DslImportBatchCommitResult.SCHEMA_VERSION))
                .andExpect(jsonPath("$.commitPolicy").value("renderable"))
                .andExpect(jsonPath("$.summary.sourceCount").value(3))
                .andExpect(jsonPath("$.summary.committedSourceCount").value(2))
                .andExpect(jsonPath("$.summary.skippedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.failedSourceCount").value(0))
                .andExpect(jsonPath("$.summary.reportSummary.repairableSourceCount").value(1))
                .andExpect(jsonPath("$.summary.commitDecisionCounts.COMMITTED_RENDERABLE").value(2))
                .andExpect(jsonPath("$.summary.commitDecisionCounts.SKIP_NOT_RENDERABLE").value(1))
                .andExpect(jsonPath("$.items[0].sourceId").value("supported-transform.bloge"))
                .andExpect(jsonPath("$.items[0].committed").value(true))
                .andExpect(jsonPath("$.items[0].commitDecision").value("COMMITTED_RENDERABLE"))
                .andExpect(jsonPath("$.items[0].importResult.imported").value(true))
                .andExpect(jsonPath("$.items[0].importResult.draft.draftId").isNotEmpty())
                .andExpect(jsonPath("$.items[1].sourceId").value("missing-operator.bloge"))
                .andExpect(jsonPath("$.items[1].committed").value(true))
                .andExpect(jsonPath("$.items[1].reportItem.needsRepair").value(true))
                .andExpect(jsonPath("$.items[1].importResult.imported").value(true))
                .andExpect(jsonPath("$.items[2].sourceId").value("broken.bloge"))
                .andExpect(jsonPath("$.items[2].committed").value(false))
                .andExpect(jsonPath("$.items[2].commitDecision").value("SKIP_NOT_RENDERABLE"))
                .andExpect(jsonPath("$.items[2].importResult.imported").value(false));

        org.assertj.core.api.Assertions.assertThat(repository.all()).hasSize(2);
    }

    @Test
    void batchCommitRewriteAllowedPolicyOnlyStoresRoundTripSafeDrafts() throws Exception {
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
        GraphDraftRepository repository = new InMemoryGraphDraftRepository();
        DslImportService service = new DslImportService(catalog, new OperatorLibraryValidator());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DslImportController(
                service,
                repository,
                new GraphDraftValidator(catalog),
                catalog
        )).build();

        mockMvc.perform(post("/api/visual/dsl-imports/batch-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OBJECT_MAPPER.writeValueAsString(Map.of(
                                "commitPolicy", "rewrite-allowed",
                                "sources", List.of(
                                        Map.of(
                                                "sourceId", "supported-transform.bloge",
                                                "dsl", """
                                                        graph supportedTransform {
                                                          input {
                                                            score: Int
                                                          }
                                                          transform response {
                                                            score = ctx.score
                                                          }
                                                        }
                                                        """
                                        ),
                                        Map.of(
                                                "sourceId", "missing-operator.bloge",
                                                "dsl", """
                                                        graph missingOperator {
                                                          node eligibility : "risk:missing" {
                                                          }
                                                        }
                                                        """
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitPolicy").value("rewrite-allowed"))
                .andExpect(jsonPath("$.summary.sourceCount").value(2))
                .andExpect(jsonPath("$.summary.committedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.skippedSourceCount").value(1))
                .andExpect(jsonPath("$.summary.commitDecisionCounts.COMMITTED_REWRITE_ALLOWED").value(1))
                .andExpect(jsonPath("$.summary.commitDecisionCounts.SKIP_REWRITE_NOT_ALLOWED").value(1))
                .andExpect(jsonPath("$.items[0].committed").value(true))
                .andExpect(jsonPath("$.items[0].commitDecision").value("COMMITTED_REWRITE_ALLOWED"))
                .andExpect(jsonPath("$.items[1].committed").value(false))
                .andExpect(jsonPath("$.items[1].commitDecision").value("SKIP_REWRITE_NOT_ALLOWED"));

        org.assertj.core.api.Assertions.assertThat(repository.all()).hasSize(1);
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
