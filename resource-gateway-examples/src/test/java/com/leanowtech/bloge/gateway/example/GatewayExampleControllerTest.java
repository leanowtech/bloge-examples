package com.leanowtech.bloge.gateway.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    void scenariosEndpointListsSixVisualExamples() throws Exception {
        mockMvc.perform(get("/api/gateway/examples/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].graphName").value("userDashboard"))
                .andExpect(jsonPath("$[0].run.pathTemplate").value("/api/gateway/dashboard/{userId}"))
                .andExpect(jsonPath("$[5].graphName").value("aiEnrichedSearch"))
                .andExpect(jsonPath("$[5].run.mode").value("stream"));
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
    void staticShowcaseAssetsArePackaged() {
        assertThat(new ClassPathResource("static/examples/gateway/index.html").exists()).isTrue();
        assertThat(new ClassPathResource("static/examples/gateway/app.js").exists()).isTrue();
        assertThat(new ClassPathResource("static/examples/gateway/styles.css").exists()).isTrue();
    }
}
