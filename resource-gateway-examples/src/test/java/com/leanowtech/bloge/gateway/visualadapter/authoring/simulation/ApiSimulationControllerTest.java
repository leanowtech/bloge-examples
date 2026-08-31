package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResult;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationFailure;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRun;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiSimulationControllerTest {
    @Test
    void executesWithTrustedScopeAndReturnsReplayEvidence() throws Exception {
        SimulationModule module = mock(SimulationModule.class);
        when(module.execute(any(), any(), any())).thenReturn(new SimulationExecutionResult(run(), false));

        mvc(module).perform(post("/api/authoring/simulations")
                        .contentType("application/json")
                        .content("""
                                {"schemaVersion":"bloge.simulationRequest.v1","source":{
                                  "kind":"FIXTURE_CASE","fixtureSetId":"orders:r1","revision":1,
                                  "caseId":"happy"}}
                                """)
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("Idempotency-Key", "simulation-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("X-Simulation-Run-Id", "sim-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.subject.resourceId").value("orders"));

        verify(module).execute(eq(new AuthoringScope("tenant-a", "project-a", "test")),
                eq("simulation-key"), any());
    }

    @Test
    void readsOnlyThroughTheAuthenticatedModuleBoundary() throws Exception {
        SimulationModule module = mock(SimulationModule.class);
        when(module.readRequired(any(), any())).thenReturn(run());

        mvc(module).perform(get("/api/authoring/simulations/sim-1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.runId").value("sim-1"));
    }

    @Test
    void missingAuthAndIdempotencyNeverReachExecution() throws Exception {
        SimulationModule module = mock(SimulationModule.class);

        mvc(module).perform(post("/api/authoring/simulations")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc(module).perform(post("/api/authoring/simulations")
                        .contentType("application/json").content("{}")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.SIMULATION.IDEMPOTENCY_KEY_REQUIRED"));

        verify(module, never()).execute(any(), any(), any());
    }

    @Test
    void unsupportedSourceUsesPayloadFreeUnifiedProblem() throws Exception {
        SimulationModule module = mock(SimulationModule.class);
        when(module.execute(any(), any(), any()))
                .thenThrow(new SimulationFailure(SimulationFailure.Code.UNSUPPORTED));

        mvc(module).perform(post("/api/authoring/simulations")
                        .contentType("application/json")
                        .content("""
                                {"schemaVersion":"bloge.simulationRequest.v1","source":{"kind":"AD_HOC",
                                 "subject":{"kind":"API_RESOURCE","resourceId":"orders","revision":1,
                                 "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                                 "input":{}}}
                                """)
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING")
                        .header("Idempotency-Key", "simulation-key"))
                .andExpect(status().isFailedDependency())
                .andExpect(jsonPath("$.code")
                        .value("RG.AUTHORING.SIMULATION.CAPABILITY_UNAVAILABLE"));
    }

    private static MockMvc mvc(SimulationModule module) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ApiSimulationController(
                        module, authenticator, new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new ApiResourceAuthoringProblemHandler()).build();
    }

    private static SimulationRun run() {
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        return new SimulationRun(SimulationRun.SCHEMA_VERSION, "sim-1", SimulationRun.Status.SUCCEEDED,
                new FixtureSubjectRef.ApiResource("orders", 1, "sha256:" + "a".repeat(64)),
                new SimulationRun.FixtureCase("orders:r1", 1, "happy"), null, List.of(),
                new SimulationRun.Verdicts(SimulationRun.ExecutionVerdict.SIMULATED_ONLY,
                        SimulationRun.Verdict.PASSED, SimulationRun.Verdict.NOT_CHECKED,
                        SimulationRun.Verdict.NOT_CHECKED), List.of(), time, time);
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
