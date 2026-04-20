package com.leanowtech.bloge.graphengine.server.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphRemoteWorkerControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphRemoteWorkerController(graphEngineService));
    }

    @Test
    void registerRemoteWorkerDelegatesToService() throws Exception {
        graphEngineService.registerRemoteWorkerResult = remoteWorkerRegistration("worker-ai", "workers.ai");

        mockMvc.perform(post("/api/v1/remote-workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "worker-ai",
                                  "workerTopic": "workers.ai"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("worker-ai"))
                .andExpect(jsonPath("$.assignments[0].operatorRef").value("RiskAssessment"));

        assertEquals("worker-ai", graphEngineService.registerRemoteWorkerCommand.workerId());
        assertEquals("workers.ai", graphEngineService.registerRemoteWorkerCommand.workerTopic());
    }

    @Test
    void pollRemoteWorkerJobsDelegatesToService() throws Exception {
        graphEngineService.pollRemoteWorkerJobsResult = java.util.List.of(
                remoteWorkerJob("remote-1", "workers.ai", "RiskAssessment")
        );

        mockMvc.perform(post("/api/v1/remote-workers/workers.ai/poll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "worker-ai",
                                  "limit": 2,
                                  "leaseDuration": "PT5M"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value("remote-1"))
                .andExpect(jsonPath("$[0].envelope.workerTopic").value("workers.ai"));

        assertEquals("worker-ai", graphEngineService.pollRemoteWorkerJobsCommand.workerId());
        assertEquals("workers.ai", graphEngineService.pollRemoteWorkerJobsCommand.workerTopic());
        assertEquals(2, graphEngineService.pollRemoteWorkerJobsCommand.limit());
    }

    @Test
    void heartbeatRemoteWorkerJobDelegatesToService() throws Exception {
        graphEngineService.heartbeatRemoteWorkerJobResult = remoteWorkerJob("remote-1", "workers.ai", "RiskAssessment");

        mockMvc.perform(post("/api/v1/remote-workers/items/remote-1/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "lease-remote-1",
                                  "leaseDuration": "PT2M"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimToken").value("lease-remote-1"));

        assertEquals("remote-1", graphEngineService.heartbeatRemoteWorkerJobCommand.itemId());
        assertEquals("lease-remote-1", graphEngineService.heartbeatRemoteWorkerJobCommand.leaseToken());
    }

    @Test
    void completeRemoteWorkerJobReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/remote-workers/items/remote-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "lease-remote-1",
                                  "expectedRevision": 3,
                                  "output": {
                                    "approved": true
                                  }
                                }
                                """))
                .andExpect(status().isNoContent());

        assertEquals("remote-1", graphEngineService.completeRemoteWorkerJobCommand.itemId());
        assertEquals("lease-remote-1", graphEngineService.completeRemoteWorkerJobCommand.leaseToken());
        assertEquals(3, graphEngineService.completeRemoteWorkerJobCommand.expectedRevision());
    }

    @Test
    void failRemoteWorkerJobReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/remote-workers/items/remote-1/fail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "leaseToken": "lease-remote-1",
                                  "expectedRevision": 4,
                                  "error": "upstream timeout"
                                }
                                """))
                .andExpect(status().isNoContent());

        assertEquals("remote-1", graphEngineService.failRemoteWorkerJobCommand.itemId());
        assertEquals("lease-remote-1", graphEngineService.failRemoteWorkerJobCommand.leaseToken());
        assertEquals("upstream timeout", graphEngineService.failRemoteWorkerJobCommand.error());
    }

    @Test
    void registerRemoteWorkerValidationErrorsReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/remote-workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerId": "",
                                  "workerTopic": "workers.ai"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("workerId: must not be blank"));
    }
}
