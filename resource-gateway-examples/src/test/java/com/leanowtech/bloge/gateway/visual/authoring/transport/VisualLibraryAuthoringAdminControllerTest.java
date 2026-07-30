package com.leanowtech.bloge.gateway.visual.authoring.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringPreviewService;
import com.leanowtech.bloge.gateway.visual.authoring.compile.AuthoringCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.InMemoryOperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisualLibraryAuthoringAdminControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryOperatorLibraryRegistry registry = new InMemoryOperatorLibraryRegistry();
        AuthoringCompiler compiler = new AuthoringCompiler(mapper, new OperatorLibraryValidator());
        AuthoringPreviewService previewService =
                new AuthoringPreviewService(compiler, registry, mapper);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new VisualLibraryAuthoringAdminController(previewService, mapper)
        ).build();
    }

    @Test
    void previewsYamlFunctionOnlyLibraryWithoutPersistingIt() throws Exception {
        mockMvc.perform(post("/admin/visual-operator-library-authoring/preview")
                        .contentType("application/yaml")
                        .content("""
                                schemaVersion: bloge.visualLibraryAuthoring.v1
                                library: {id: support-functions, owner: support-team}
                                functions:
                                  support.normalize:
                                    signature: "(text: string) -> string"
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion")
                        .value("bloge.visualLibraryCompileResult.v1"))
                .andExpect(jsonPath("$.previewAuthority").value("SERVER_AUTHORITATIVE"))
                .andExpect(jsonPath("$.readiness.importable").value(true))
                .andExpect(jsonPath("$.canonicalLibrary.libraryId").value("support-functions"))
                .andExpect(jsonPath("$.canonicalLibrary.builtInFunctions.length()").value(1))
                .andExpect(jsonPath("$.diff.changed").value(true));
    }

    @Test
    void returnsStableProblemForMalformedAndOversizedSources() throws Exception {
        mockMvc.perform(post("/admin/visual-operator-library-authoring/preview")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("library: ["))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualAuthoringProblem.v1"))
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.PARSE_FAILED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        mockMvc.perform(post("/admin/visual-operator-library-authoring/preview")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(new byte[AuthoringCompiler.MAX_AUTHORING_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.DOCUMENT_LIMIT_EXCEEDED"));
    }

    @Test
    void parsesSignaturesAndAdvertisesExactStageZeroCapabilities() throws Exception {
        mockMvc.perform(post("/admin/visual-operator-library-authoring/signature/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"signature":"(value: string, fallback?: string) -> string"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.signature.normalized")
                        .value("(value: string, fallback?: string) -> string"));

        mockMvc.perform(get("/admin/visual-operator-library-authoring/catalogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authoringSchemaVersion")
                        .value("bloge.visualLibraryAuthoring.v1"))
                .andExpect(jsonPath("$.features.functionOnlyLibrary").value(true))
                .andExpect(jsonPath("$.features.statelessPreview").value(true))
                .andExpect(jsonPath("$.features.sampleInference").value(true))
                .andExpect(jsonPath("$.features.sampleInferenceApply").value(true))
                .andExpect(jsonPath("$.features.operatorTestDraftRunner").value(true))
                .andExpect(jsonPath("$.features.functionTestDraftRunner").value(true))
                .andExpect(jsonPath("$.features.governedFixturePersistence").value(false))
                .andExpect(jsonPath("$.features.isolatedFunctionTestWorker").value(false))
                .andExpect(jsonPath("$.limits.maximumSampleInferenceBytes").value(2097152))
                .andExpect(jsonPath("$.limits.maximumSampleInferenceApplyBytes").value(4194304))
                .andExpect(jsonPath("$.limits.maximumInferenceSamples").value(100))
                .andExpect(jsonPath("$.limits.maximumAuthoringTestSuiteBytes").value(262144))
                .andExpect(jsonPath("$.limits.maximumAuthoringTestResultBytes").value(524288))
                .andExpect(jsonPath("$.limits.maximumAuthoringTestCases").value(50))
                .andExpect(jsonPath("$.limits.maximumFunctionTestArguments").value(32))
                .andExpect(jsonPath("$.limits.functionTestTimeoutMillis").value(250))
                .andExpect(jsonPath("$.limits.maximumYamlAliases").value(20))
                .andExpect(jsonPath("$.archetypes.length()").value(9));
    }
}
