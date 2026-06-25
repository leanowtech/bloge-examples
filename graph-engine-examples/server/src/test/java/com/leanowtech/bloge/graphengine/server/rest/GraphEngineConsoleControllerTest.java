package com.leanowtech.bloge.graphengine.server.rest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

        mockMvc.perform(get("/console/instances"))
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
    void webApiConfigurationImportsConsoleController() throws Exception {
        Class<?> webApiConfiguration = Class.forName(
                "com.leanowtech.bloge.graphengine.server.config.GraphEngineServerAutoConfiguration$WebApiConfiguration"
        );
        Import imported = webApiConfiguration.getAnnotation(Import.class);

        assertThat(Arrays.asList(imported.value())).contains(GraphEngineConsoleController.class);
    }
}
