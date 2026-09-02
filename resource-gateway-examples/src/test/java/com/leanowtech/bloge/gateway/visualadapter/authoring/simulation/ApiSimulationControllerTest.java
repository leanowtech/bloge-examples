package com.leanowtech.bloge.gateway.visualadapter.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.ExactFixtureSubjectRefV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResult;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationExecutionResultV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationFailure;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModule;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationModuleV2;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRun;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiResourceAuthoringProblemHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        when(module.execute(any(), any(), any(), any()))
                .thenReturn(new SimulationExecutionResult(run(), false));

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
                eq("simulation-key"), any(), any());
    }

    @Test
    void routesV2ThroughTheTrustedCallerDirectedModule() throws Exception {
        SimulationModule v1 = mock(SimulationModule.class);
        SimulationModuleV2 v2 = mock(SimulationModuleV2.class);
        when(v2.execute(any(), any(), any(), any()))
                .thenReturn(new SimulationExecutionResultV2(runV2(), false));

        mvc(v1, v2).perform(post("/api/authoring/simulations")
                        .contentType("application/json")
                        .content("""
                                {"schemaVersion":"bloge.simulationCommand.v2",
                                 "subject":{"kind":"API_RESOURCE","resourceId":"orders","revision":1,
                                  "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                                 "input":{"kind":"INLINE","value":{"id":"order-1"}},
                                 "fixturePlan":{"kind":"NONE"}}
                                """)
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "AUTHORING_SIMULATION_RUN")
                        .header("Idempotency-Key", "simulation-v2-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("X-Simulation-Run-Id", "sim-v2-1"))
                .andExpect(jsonPath("$.schemaVersion").value("bloge.simulationRun.v2"))
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        verify(v2).execute(eq(new AuthoringScope("tenant-a", "project-a", "test")),
                eq("simulation-v2-key"), any(), any());
        verify(v1, never()).execute(any(), any(), any(), any());
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
    void readsV2BeforeFallingBackToTheV1Authority() throws Exception {
        SimulationModule v1 = mock(SimulationModule.class);
        SimulationModuleV2 v2 = mock(SimulationModuleV2.class);
        when(v2.read(any(), eq("sim-v2-1"))).thenReturn(Optional.of(runV2()));

        mvc(v1, v2).perform(get("/api/authoring/simulations/sim-v2-1")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "AUTHORING_SIMULATION_RUN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("bloge.simulationRun.v2"));

        verify(v1, never()).readRequired(any(), any());
    }

    @Test
    void rejectsUnknownV2FieldsBeforeExecution() throws Exception {
        SimulationModule v1 = mock(SimulationModule.class);
        SimulationModuleV2 v2 = mock(SimulationModuleV2.class);

        mvc(v1, v2).perform(post("/api/authoring/simulations")
                        .contentType("application/json")
                        .content("""
                                {"schemaVersion":"bloge.simulationCommand.v2",
                                 "subject":{"kind":"API_RESOURCE","resourceId":"orders","revision":1,
                                  "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                                 "input":{"kind":"INLINE","value":{"id":"order-1"}},
                                 "fixturePlan":{"kind":"NONE"},
                                 "invocationKey":"caller-must-not-choose-this"}
                                """)
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "AUTHORING_SIMULATION_RUN")
                        .header("Idempotency-Key", "simulation-v2-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.AUTHORING.SIMULATION.REQUEST_INVALID"));

        verify(v2, never()).execute(any(), any(), any(), any());
    }

    @Test
    void mapsUnsatisfiedFixtureConditionsToAnExactSafeProblem() throws Exception {
        SimulationModule v1 = mock(SimulationModule.class);
        SimulationModuleV2 v2 = mock(SimulationModuleV2.class);
        when(v2.execute(any(), any(), any(), any())).thenThrow(new SimulationFailure(
                SimulationFailure.Code.FIXTURE_CONDITION_NOT_SATISFIED));

        mvc(v1, v2).perform(post("/api/authoring/simulations")
                        .contentType("application/json")
                        .content("""
                                {"schemaVersion":"bloge.simulationCommand.v2",
                                 "subject":{"kind":"API_RESOURCE","resourceId":"orders","revision":1,
                                  "fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                                 "input":{"kind":"INLINE","value":{"id":"order-1"}},
                                 "fixturePlan":{"kind":"NONE"}}
                                """)
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "AUTHORING_SIMULATION_RUN")
                        .header("Idempotency-Key", "simulation-v2-key"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "RG.AUTHORING.SIMULATION.FIXTURE_CONDITION_NOT_SATISFIED"));
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

        verify(module, never()).execute(any(), any(), any(), any());
    }

    @Test
    void unsupportedSourceUsesPayloadFreeUnifiedProblem() throws Exception {
        SimulationModule module = mock(SimulationModule.class);
        when(module.execute(any(), any(), any(), any()))
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
        SimulationModuleV2 moduleV2 = mock(SimulationModuleV2.class);
        when(moduleV2.read(any(), any())).thenReturn(Optional.empty());
        return mvc(module, moduleV2);
    }

    private static MockMvc mvc(SimulationModule module, SimulationModuleV2 moduleV2) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "",
                Set.of("API_RESOURCE_AUTHORING", "AUTHORING_SIMULATION_RUN"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false),
                new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ApiSimulationController(
                        module, moduleV2, authenticator, new ObjectMapper().findAndRegisterModules()))
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

    private static SimulationRunV2 runV2() {
        Instant time = Instant.parse("2030-01-01T00:00:00Z");
        return new SimulationRunV2(SimulationRunV2.SCHEMA_VERSION, "sim-v2-1",
                SimulationRunV2.Status.BLOCKED, new ExactFixtureSubjectRefV2.ApiResource(
                "orders", 1, "sha256:" + "a".repeat(64)), "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64), null, List.of(), new SimulationRunV2.Verdicts(
                SimulationRunV2.ExecutionVerdict.BLOCKED,
                SimulationRunV2.AssertionsVerdict.NOT_CHECKED,
                SimulationRunV2.ContractVerdict.NOT_CHECKED,
                SimulationRunV2.GovernanceVerdict.NOT_CHECKED,
                SimulationRunV2.AggregateVerdict.NOT_READY), List.of(
                new SimulationRunV2.Diagnostic("FIXTURE_UNMATCHED", "No Fixture was selected.")),
                time, time);
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
