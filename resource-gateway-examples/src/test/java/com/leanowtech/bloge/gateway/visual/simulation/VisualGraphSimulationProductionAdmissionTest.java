package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisualGraphSimulationProductionAdmissionTest {

    @Test
    void directServiceRejectsBeforeReadingBusinessPayload() {
        VisualGraphSimulationService service = service(true, "prod");

        assertThatThrownBy(() -> service.simulate(null,
                Map.of("secretBusinessPayload", "must-not-leak"), "", Map.of()))
                .isInstanceOf(VisualSimulationProductionAdmissionException.class)
                .satisfies(error -> {
                    assertThat(VisualSimulationProductionAdmissionException.CODE)
                            .isEqualTo("RG.PRODUCTION.VISUAL_SIMULATION_FORBIDDEN");
                    VisualSimulationProductionAdmissionException failure =
                            (VisualSimulationProductionAdmissionException) error;
                    assertThat(failure.getMessage())
                            .isEqualTo(VisualSimulationProductionAdmissionException.TITLE);
                    assertThat(failure.getMessage()).doesNotContain("must-not-leak");
                });
    }

    @Test
    void directControllerMockMvcBypassReturnsForbiddenProblem() throws Exception {
        VisualGraphSimulationService service = service(true, "production");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new VisualGraphSimulationController(service)).build();

        String response = mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secretBusinessPayload\":\"must-not-leak\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.code")
                        .value(VisualSimulationProductionAdmissionException.CODE))
                .andExpect(jsonPath("$.status").value(403))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("must-not-leak");
    }

    @ParameterizedTest(name = "profile={0}, environment={1} -> {2}")
    @MethodSource("deploymentEvidenceMatrix")
    void deploymentEvidenceUsesEitherProductionSignal(String profile,
                                                       String environment,
                                                       boolean rejects) {
        VisualGraphSimulationService service = service("production".equals(profile), environment);

        if (rejects) {
            assertThatThrownBy(() -> service.simulate(null, Map.of(), ""))
                    .isInstanceOf(VisualSimulationProductionAdmissionException.class)
                    .hasMessage(VisualSimulationProductionAdmissionException.TITLE);
        } else {
            assertThat(service.simulate(null, Map.of(), "").errors())
                    .contains("Graph draft is required.");
        }
    }

    @Test
    void visualDefaultPolicyRejectsProductionSimulation() {
        VisualGraphSimulationService service = service(VisualProductionAdmissionPolicy.productionDefault());

        assertThatThrownBy(() -> service.simulate(null,
                Map.of("secretBusinessPayload", "must-not-leak"), ""))
                .isInstanceOf(VisualSimulationProductionAdmissionException.class)
                .hasMessage(VisualSimulationProductionAdmissionException.TITLE);
    }

    private static Stream<Arguments> deploymentEvidenceMatrix() {
        return Stream.of(
                Arguments.of("production", "test", true),
                Arguments.of("test", "prod", true),
                Arguments.of("test", "staging", false),
                Arguments.of("staging", "production", true));
    }

    private static VisualGraphSimulationService service(boolean productionProfileActive,
                                                        String environment) {
        return service(VisualProductionAdmissionPolicy.fromEvidence(
                productionProfileActive, environment));
    }

    private static VisualGraphSimulationService service(
            VisualProductionAdmissionPolicy deploymentPolicy) {
        return new VisualGraphSimulationService(
                mock(GraphDraftValidator.class), mock(VisualOperatorCatalog.class),
                new JsonSchemaSampleGenerator(), mock(VisualDslRunnerFactory.class),
                VisualGraphSimulationService.DEFAULT_SIMULATION_RUN_TIMEOUT,
                deploymentPolicy);
    }
}
