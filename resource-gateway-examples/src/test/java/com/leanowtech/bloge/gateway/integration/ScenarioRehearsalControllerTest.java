package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CaseHandlingAssertion;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRegistryService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioPack;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompileRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalExecutionRequest;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalResult;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScenarioRehearsalControllerTest {
    private static final String SHA_A = "sha256:" + "a".repeat(64);

    @Test
    void authenticatesEveryRouteWithItsDedicatedOperation() {
        ScenarioArtifactRegistryService artifacts =
                mock(ScenarioArtifactRegistryService.class);
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ScenarioArtifactRequestDecoder decoder =
                mock(ScenarioArtifactRequestDecoder.class);
        IntegrationRequestContext identity =
                mock(IntegrationRequestContext.class);
        HttpHeaders headers = new HttpHeaders();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        CaseHandlingAssertion assertion = mock(CaseHandlingAssertion.class);
        MirrorSessionCheckpointBundle checkpoint =
                mock(MirrorSessionCheckpointBundle.class);
        ScenarioCase scenarioCase = mock(ScenarioCase.class);
        ScenarioPack pack = mock(ScenarioPack.class);
        CompiledScenarioRehearsalPlan plan =
                mock(CompiledScenarioRehearsalPlan.class);
        ScenarioRehearsalResult result =
                mock(ScenarioRehearsalResult.class);
        ScenarioRehearsalCompileRequest command =
                new ScenarioRehearsalCompileRequest("", 1, SHA_A);
        ScenarioRehearsalExecutionRequest execution =
                new ScenarioRehearsalExecutionRequest(
                        "", "request-1",
                        new MirrorArtifactRef(
                                "COMPILED_REHEARSAL_PLAN",
                                "refund-pack@compiled-v1", 1, SHA_A));
        when(assertion.schemaVersion()).thenReturn(
                CaseHandlingAssertion.SCHEMA_VERSION);
        when(checkpoint.schemaVersion()).thenReturn(
                MirrorSessionCheckpointBundle.SCHEMA_VERSION);
        when(scenarioCase.schemaVersion()).thenReturn(
                ScenarioCase.SCHEMA_VERSION);
        when(pack.schemaVersion()).thenReturn(ScenarioPack.SCHEMA_VERSION);
        when(plan.schemaVersion()).thenReturn(
                CompiledScenarioRehearsalPlan.SCHEMA_VERSION);
        when(result.schemaVersion()).thenReturn(
                ScenarioRehearsalResult.SCHEMA_VERSION);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_WRITE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_PLAN_COMPILE))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_PLAN_READ))
                .thenReturn(identity);
        when(authenticator.authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_EXECUTE))
                .thenReturn(identity);
        when(decoder.decodeAssertion(raw, identity)).thenReturn(assertion);
        when(decoder.decodeCheckpoint(raw, identity)).thenReturn(checkpoint);
        when(decoder.decodeCase(raw, identity)).thenReturn(scenarioCase);
        when(decoder.decodePack(raw, identity)).thenReturn(pack);
        when(decoder.decodeCompileRequest(raw, identity)).thenReturn(command);
        when(decoder.decodeExecutionRequest(raw, identity))
                .thenReturn(execution);
        when(artifacts.register(assertion, identity)).thenReturn(assertion);
        when(artifacts.register(checkpoint, identity)).thenReturn(checkpoint);
        when(artifacts.register(scenarioCase, identity))
                .thenReturn(scenarioCase);
        when(artifacts.register(pack, identity)).thenReturn(pack);
        when(artifacts.findPack("refund-pack", 1, SHA_A, identity))
                .thenReturn(pack);
        when(rehearsals.compile("refund-pack", 1, SHA_A, identity))
                .thenReturn(plan);
        when(rehearsals.find("refund-pack@compiled-v1", 1, SHA_A, identity))
                .thenReturn(plan);
        when(runtime.execute(execution, identity)).thenReturn(result);
        ScenarioRehearsalController controller =
                new ScenarioRehearsalController(
                        artifacts, rehearsals, runtime,
                        authenticator, decoder);

        assertThat(controller.registerAssertion(raw, headers).payload())
                .isSameAs(assertion);
        assertThat(controller.registerCheckpoint(raw, headers).payload())
                .isSameAs(checkpoint);
        assertThat(controller.registerCase(raw, headers).payload())
                .isSameAs(scenarioCase);
        assertThat(controller.registerPack(raw, headers).payload())
                .isSameAs(pack);
        assertThat(controller.findPack(
                "refund-pack", 1, SHA_A, headers).payload()).isSameAs(pack);
        assertThat(controller.compile(
                "refund-pack", raw, headers).payload()).isSameAs(plan);
        assertThat(controller.findCompiledPlan(
                "refund-pack@compiled-v1", 1, SHA_A, headers).payload())
                .isSameAs(plan);
        assertThat(controller.execute(raw, headers).payload())
                .isSameAs(result);
        verify(authenticator, times(4)).authenticate(
                headers, IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_WRITE);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_SCENARIO_ARTIFACT_READ);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_PLAN_COMPILE);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_PLAN_READ);
        verify(authenticator).authenticate(
                headers, IntegrationOperation.MIRROR_REHEARSAL_EXECUTE);
    }

    @Test
    void authenticationFailureHappensBeforeScenarioPayloadDecoding()
            throws Exception {
        ScenarioArtifactRegistryService artifacts =
                mock(ScenarioArtifactRegistryService.class);
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        ScenarioArtifactRequestDecoder decoder =
                mock(ScenarioArtifactRequestDecoder.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_REHEARSAL_PLAN_COMPILE)))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.unauthorized(
                                "RG.INTEGRATION.AUTHENTICATION_REQUIRED",
                                "Authentication is required.",
                                "corr-1",
                                Map.of())));
        MockMvc mvc = mvc(new ScenarioRehearsalController(
                artifacts, rehearsals, runtime, authenticator, decoder));

        mvc.perform(post(
                        "/api/mirror/scenarios/packs/refund-pack/compiled-plans")
                        .contentType(APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(decoder, artifacts, rehearsals, runtime);
    }

    @Test
    void strictTransportRejectsDuplicateCompileCoordinates()
            throws Exception {
        ScenarioArtifactRegistryService artifacts =
                mock(ScenarioArtifactRegistryService.class);
        ScenarioRehearsalIntegrationService rehearsals =
                mock(ScenarioRehearsalIntegrationService.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        IntegrationRequestAuthenticator authenticator =
                mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(
                any(HttpHeaders.class),
                eq(IntegrationOperation.MIRROR_REHEARSAL_PLAN_COMPILE)))
                .thenReturn(mock(IntegrationRequestContext.class));
        ScenarioArtifactRequestDecoder decoder =
                new ScenarioArtifactRequestDecoder(
                        new ObjectMapper().findAndRegisterModules());
        MockMvc mvc = mvc(new ScenarioRehearsalController(
                artifacts, rehearsals, runtime, authenticator, decoder));
        String request = """
                {
                  "schemaVersion":"resourceGateway.scenarioRehearsalCompileRequest.v1",
                  "revision":1,
                  "revision":2,
                  "fingerprint":"%s"
                }
                """.formatted(SHA_A);

        mvc.perform(post(
                        "/api/mirror/scenarios/packs/refund-pack/compiled-plans")
                        .contentType(APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "RG.MIRROR.SCENARIO_REQUEST_MALFORMED"));
        verifyNoInteractions(artifacts, rehearsals, runtime);
    }

    private MockMvc mvc(ScenarioRehearsalController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }
}
