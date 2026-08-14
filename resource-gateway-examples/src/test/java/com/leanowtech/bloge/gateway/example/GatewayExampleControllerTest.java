package com.leanowtech.bloge.gateway.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web contract tests for {@link GatewayExampleController}.
 */
class GatewayExampleControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new GatewayExampleController(new GatewayExampleCatalog())
        ).build();
    }

    @Test
    void scenariosEndpointListsSevenVisualExamples() throws Exception {
        mockMvc.perform(get("/api/gateway/examples/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].graphName").value("userDashboard"))
                .andExpect(jsonPath("$[0].run.pathTemplate").value("/api/gateway/dashboard/{userId}"))
                .andExpect(jsonPath("$[0].inputSchema.schema.properties.userId.type").value("string"))
                .andExpect(jsonPath("$[0].outputSchema.schema.properties.profile.type").value("object"))
                .andExpect(jsonPath("$[1].graphName").value("loanDecisionPolicy"))
                .andExpect(jsonPath("$[1].decisionTable.hitPolicy").value("unique"))
                .andExpect(jsonPath("$[1].inputSchema.schema.properties.requestedAmount.type").value("number"))
                .andExpect(jsonPath("$[1].samplePresets.length()").value(4))
                .andExpect(jsonPath("$[1].samplePresets[0].expected.ruleId").value("R1"))
                .andExpect(jsonPath("$[2].inputSchema.schema.properties.productId.type").value("string"))
                .andExpect(jsonPath("$[2].outputSchema.schema.properties.productType.type").value("string"))
                .andExpect(jsonPath("$[6].graphName").value("aiEnrichedSearch"))
                .andExpect(jsonPath("$[6].run.mode").value("stream"));
    }

    @Test
    void scenarioEndpointReturnsOneScenario() throws Exception {
        mockMvc.perform(get("/api/gateway/examples/scenarios/creditScore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphName").value("creditScore"))
                .andExpect(jsonPath("$.pattern").value("Provider degradation"))
                .andExpect(jsonPath("$.sampleInput.userId").value("u1"));
    }

    @Test
    void diagramEndpointReturnsVisualLayout() throws Exception {
        mockMvc.perform(get("/api/gateway/examples/scenarios/productDetail/diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.visualLayout.v1"))
                .andExpect(jsonPath("$.rootId").value("productDetail"))
                .andExpect(jsonPath("$.nodes[0].id").value("fetchProduct"))
                .andExpect(jsonPath("$.edges[0].source").value("fetchProduct"));
    }

    @Test
    void unknownScenarioReturns404() throws Exception {
        mockMvc.perform(get("/api/gateway/examples/scenarios/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pageControllerForwardsCleanUrlToStaticShowcase() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/examples/gateway"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/examples/gateway/index.html"));
    }

    @Test
    void pageControllerMakesBusinessMirrorTheDefaultProductWorkspace() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/business-mirror/"));
        pageMvc.perform(get("/business-mirror"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/business-mirror/"));
        pageMvc.perform(get("/business-mirror/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/business-mirror/index.html"));
    }

    @Test
    void pageControllerForwardsCleanUrlToReactAuthorCanvas() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/author"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/author/index.html"));
    }

    @Test
    void pageControllerForwardsCleanUrlToLibraryWorkbench() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/libraries"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/libraries/index.html"));
    }

    @Test
    void pageControllerForwardsCleanUrlToScenarioRehearsalWorkbench() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/rehearsals"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/rehearsals/index.html"));
    }

    @Test
    void pageControllerForwardsCleanUrlToReactShowcase() throws Exception {
        MockMvc pageMvc = MockMvcBuilders.standaloneSetup(new GatewayExamplePageController()).build();

        pageMvc.perform(get("/showcase"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/showcase/index.html"));
    }

    @Test
    void staticShowcaseAssetsArePackaged() {
        assertThat(new ClassPathResource("static/examples/gateway/index.html").exists()).isTrue();
        assertThat(new ClassPathResource("static/examples/gateway/app.js").exists()).isTrue();
        assertThat(new ClassPathResource("static/examples/gateway/styles.css").exists()).isTrue();
    }
}
