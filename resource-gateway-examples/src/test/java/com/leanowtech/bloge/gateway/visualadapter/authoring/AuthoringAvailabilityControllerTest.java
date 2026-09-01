package com.leanowtech.bloge.gateway.visualadapter.authoring;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public deployment-state contract for the static authoring workbench. */
class AuthoringAvailabilityControllerTest {
    @Test
    void reportsDisabledFeaturesWithoutDependingOnFeatureScopedBeans() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthoringAvailabilityController(false, false)).build();

        mvc.perform(get("/api/authoring/availability"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("bloge.authoringAvailability.v1"))
                .andExpect(jsonPath("$.apiResource").value(false))
                .andExpect(jsonPath("$.reusableFlow").value(false));
    }

    @Test
    void reportsEnabledFeaturesWithoutRequiringFeatureScopedBeans() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AuthoringAvailabilityController(true, true)).build();

        mvc.perform(get("/api/authoring/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiResource").value(true))
                .andExpect(jsonPath("$.reusableFlow").value(true));
    }
}
