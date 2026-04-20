package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.schema.SchemaCompatibility;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.GraphVersionDiagram;
import com.leanowtech.bloge.graphengine.model.GraphVersionStatus;
import com.leanowtech.bloge.graphengine.service.GraphVersionDiff;
import com.leanowtech.bloge.graphengine.service.MetadataDiff;
import com.leanowtech.bloge.graphengine.service.PublishVersionResult;
import com.leanowtech.bloge.graphengine.service.VersionSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphVersionControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphVersionController(graphEngineService, scopeResolver));
    }

    @Test
    void publishVersionSerializesCompatibilityTypeAndUsesSemanticVersionPath() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        GraphVersion version = version(definition, "ver-1", "1.0.0");
        GraphVersion published = publishedVersion(definition, "ver-1", "1.0.0");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of(version);
        graphEngineService.publishVersionResult = new PublishVersionResult(
                published,
                backwardCompatible("Schema warnings", "added optional field")
        );

        mockMvc.perform(post("/api/v1/graphs/approval-flow/versions/1.0.0/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.version").value("1.0.0"))
                .andExpect(jsonPath("$.compatibility.type").value("backward-compatible"))
                .andExpect(jsonPath("$.compatibility.warnings[0]").value("added optional field"));

        assertEquals("def-1", graphEngineService.queryVersionsQuery.definitionId());
        assertEquals("ver-1", graphEngineService.publishVersionId);
        assertEquals(1L, graphEngineService.publishExpectedRevision);
    }

    @Test
    void missingVersionPathReturns404() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of();

        mockMvc.perform(get("/api/v1/graphs/approval-flow/versions/9.9.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Version '9.9.9' not found for definition 'approval-flow'"));
    }

    @Test
    void getVersionDiagramUsesSemanticVersionLookup() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        GraphVersion version = version(definition, "ver-1", "1.0.0");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of(version);
        graphEngineService.versionDiagramResult = new GraphVersionDiagram(
                "ver-1",
                "1.0.0",
                "{\"nodes\":[{\"id\":\"approval\"}]}"
        );

        mockMvc.perform(get("/api/v1/graphs/approval-flow/versions/1.0.0/diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value("ver-1"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.visualLayout").value("{\"nodes\":[{\"id\":\"approval\"}]}"));

        assertEquals("def-1", graphEngineService.queryVersionsQuery.definitionId());
        assertEquals("ver-1", graphEngineService.versionDiagramVersionId);
    }

    @Test
    void deprecateVersionUsesSemanticVersionLookup() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        GraphVersion published = publishedVersion(definition, "ver-1", "1.0.0");
        GraphVersion deprecated = new GraphVersion(
                published.versionId(),
                published.definitionId(),
                published.version(),
                published.contentHash(),
                published.dslSource(),
                published.visualLayout(),
                published.metadata(),
                published.compiledArtifactRef(),
                published.migrationPolicy(),
                GraphVersionStatus.DEPRECATED,
                published.revision() + 1,
                published.publishedAt(),
                published.createdAt(),
                java.time.Instant.now()
        );
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of(published);
        graphEngineService.deprecateVersionResult = deprecated;

        mockMvc.perform(post("/api/v1/graphs/approval-flow/versions/1.0.0/deprecate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPRECATED"));

        assertEquals("ver-1", graphEngineService.deprecateVersionId);
        assertEquals(2L, graphEngineService.deprecateExpectedRevision);
    }

    @Test
    void diffVersionsReturnsStructuredDiffUsingSemanticVersionPaths() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        GraphVersion v1 = version(definition, "ver-1", "1.0.0");
        GraphVersion v2 = version(definition, "ver-2", "2.0.0");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of(v1, v2);
        graphEngineService.diffVersionsResult = new GraphVersionDiff(
                new VersionSummary(v1.versionId(), v1.version(), v1.status(), v1.contentHash(),
                        v1.metadata().executionMode(), v1.createdAt(), v1.publishedAt(),
                        "approval-flow:1.0.0", "approvalFlow", v1.compiledArtifactRef(), true, 0, 1),
                new VersionSummary(v2.versionId(), v2.version(), v2.status(), v2.contentHash(),
                        v2.metadata().executionMode(), v2.createdAt(), v2.publishedAt(),
                        "approval-flow:2.0.0", "approvalFlow", v2.compiledArtifactRef(), true, 0, 0),
                false,
                List.of("--- 1.0.0", "+++ 2.0.0", "@@ -1,1 +1,1 @@", "-graph approval-flow {}", "+graph approval-flow { echo -> done }"),
                new MetadataDiff(false, null, null,
                        List.of("echo"), List.of(), List.of(),
                        true, false,
                        new SchemaCompatibility.BackwardCompatible(
                                "Schema compatible with warnings",
                                List.of("schema.priority added an optional field")
                        ),
                        new SchemaCompatibility.FullyCompatible("Fully compatible"),
                        List.of(), List.of(),
                        List.of("Added operators: echo"))
        );

        mockMvc.perform(get("/api/v1/graphs/approval-flow/versions/1.0.0/diff/2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceEqual").value(false))
                .andExpect(jsonPath("$.left.version").value("1.0.0"))
                .andExpect(jsonPath("$.left.valid").value(true))
                .andExpect(jsonPath("$.left.warningCount").value(1))
                .andExpect(jsonPath("$.right.version").value("2.0.0"))
                .andExpect(jsonPath("$.unifiedDiff[0]").value("--- 1.0.0"))
                .andExpect(jsonPath("$.metadataDiff.inputCompatibility.type").value("backward-compatible"))
                .andExpect(jsonPath("$.metadataDiff.addedOperators[0]").value("echo"))
                .andExpect(jsonPath("$.metadataDiff.summary[0]").value("Added operators: echo"));

        assertEquals("ver-1", graphEngineService.diffLeftVersionId);
        assertEquals("ver-2", graphEngineService.diffRightVersionId);
    }

    @Test
    void diffVersionsWithIdenticalSourceReportsSourceEqual() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        GraphVersion v1 = version(definition, "ver-1", "1.0.0");
        GraphVersion v2 = version(definition, "ver-2", "2.0.0");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of(v1, v2);
        graphEngineService.diffVersionsResult = new GraphVersionDiff(
                new VersionSummary(v1.versionId(), v1.version(), v1.status(), v1.contentHash(),
                        v1.metadata().executionMode(), v1.createdAt(), v1.publishedAt(),
                        "approval-flow:1.0.0", "approvalFlow", v1.compiledArtifactRef(), true, 0, 0),
                new VersionSummary(v2.versionId(), v2.version(), v2.status(), v2.contentHash(),
                        v2.metadata().executionMode(), v2.createdAt(), v2.publishedAt(),
                        "approval-flow:2.0.0", "approvalFlow", v2.compiledArtifactRef(), true, 0, 0),
                true,
                List.of(),
                new MetadataDiff(false, null, null,
                        List.of(), List.of(), List.of(),
                        false, false,
                        new SchemaCompatibility.FullyCompatible("Fully compatible"),
                        new SchemaCompatibility.FullyCompatible("Fully compatible"),
                        List.of(), List.of(),
                        List.of("No metadata changes detected"))
        );

        mockMvc.perform(get("/api/v1/graphs/approval-flow/versions/1.0.0/diff/2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceEqual").value(true))
                .andExpect(jsonPath("$.unifiedDiff").isEmpty());
    }

    @Test
    void diffVersionsReturns404WhenLeftVersionNotFound() throws Exception {
        GraphDefinition definition = definition("def-1", "approval-flow");
        graphEngineService.getDefinitionByKeyResult = definition;
        graphEngineService.queryVersionsResult = List.of();

        mockMvc.perform(get("/api/v1/graphs/approval-flow/versions/1.0.0/diff/2.0.0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}
