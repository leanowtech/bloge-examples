package com.leanowtech.bloge.graphengine.server.rest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the static graph-engine console entry point.
 */
class GraphEngineConsoleControllerTest {

    @Test
    void forwardsConsoleRoutesToStaticUi() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GraphEngineConsoleController()).build();

        mockMvc.perform(get("/console/authoring"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/console/index.html"));
    }

    @Test
    void consoleAssetsArePackaged() {
        assertThat(new ClassPathResource("static/console/index.html").exists()).isTrue();
        assertThat(new ClassPathResource("static/console/app.js").exists()).isTrue();
        assertThat(new ClassPathResource("static/console/styles.css").exists()).isTrue();
    }

    @Test
    void consoleAssetsExposeVisualizationAndAuthoringApis() throws Exception {
        String index = new ClassPathResource("static/console/index.html")
                .getContentAsString(StandardCharsets.UTF_8);
        String app = new ClassPathResource("static/console/app.js")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(index).contains("Authoring", "Queues");
        assertThat(app)
                .contains("/api/v1/ai/validate")
                .contains("/api/v1/ai/generate")
                .contains("/diff/")
                .contains("/api/v1/remote-workers/register")
                .contains("/api/v1/remote-workers/items/");
    }

    @Test
    void webApiConfigurationImportsConsoleController() throws Exception {
        Class<?> webApiConfiguration = Class.forName(
                "com.leanowtech.bloge.graphengine.server.config.GraphEngineServerAutoConfiguration$WebApiConfiguration"
        );
        Import imported = webApiConfiguration.getAnnotation(Import.class);

        assertThat(Arrays.asList(imported.value())).contains(GraphEngineConsoleController.class);
    }
}
