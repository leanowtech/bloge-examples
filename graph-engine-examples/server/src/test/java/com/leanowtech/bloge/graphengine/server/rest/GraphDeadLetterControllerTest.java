package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.service.RetryDeadLetterResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphDeadLetterControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphDeadLetterController(graphEngineService, scopeResolver));
    }

    @Test
    void queryDeadLettersUsesRequestScopeFilters() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.SUSPENDED);
        graphEngineService.queryDeadLettersResult = List.of(deadLetter(instance, "wi-1"));
        Instant cutoff = Instant.parse("2026-04-06T04:30:00Z").truncatedTo(ChronoUnit.SECONDS);

        mockMvc.perform(get("/api/v1/dead-letters")
                        .queryParam("itemId", "wi-1")
                        .queryParam("instanceId", "exec-1")
                        .queryParam("itemType", "TASK_RESUME")
                        .queryParam("shardId", "shard-a")
                        .queryParam("deadLetteredAfter", cutoff.toString())
                        .queryParam("page", "2")
                        .queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value("wi-1"))
                .andExpect(jsonPath("$[0].itemType").value("TASK_RESUME"));

        assertEquals("default", graphEngineService.deadLetterQuery.tenantId());
        assertEquals("default", graphEngineService.deadLetterQuery.namespace());
        assertEquals("wi-1", graphEngineService.deadLetterQuery.itemId());
        assertEquals("exec-1", graphEngineService.deadLetterQuery.instanceId());
        assertEquals(WorkItemType.TASK_RESUME, graphEngineService.deadLetterQuery.itemType());
        assertEquals("shard-a", graphEngineService.deadLetterQuery.shardId());
        assertEquals(cutoff, graphEngineService.deadLetterQuery.deadLetteredAfter());
        assertEquals(2, graphEngineService.deadLetterQuery.page());
        assertEquals(10, graphEngineService.deadLetterQuery.size());
    }

    @Test
    void retryDeadLetterReturnsResult() throws Exception {
        graphEngineService.retryDeadLetterResult = new RetryDeadLetterResult("wi-1", "exec-1", 1);

        mockMvc.perform(post("/api/v1/dead-letters/wi-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("wi-1"))
                .andExpect(jsonPath("$.instanceId").value("exec-1"))
                .andExpect(jsonPath("$.retriedItemCount").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.attemptStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.status").value("RESTORED"));

        assertEquals("wi-1", graphEngineService.retryDeadLetterItemId);
        assertTrue(graphEngineService.retryDeadLetterEvidence.emptyEvidence());
    }

    @Test
    void retryDeadLetterMapsRecoveryEvidence() throws Exception {
        graphEngineService.retryDeadLetterResult = new RetryDeadLetterResult(
                "wi-1",
                "exec-1",
                1,
                true,
                GraphControlActionEntry.AttemptStatus.SUCCEEDED,
                "RESTORED",
                "INC-123",
                null,
                null,
                null,
                Instant.parse("2026-07-06T00:00:00Z")
        );

        mockMvc.perform(post("/api/v1/dead-letters/wi-1/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "validated idempotency",
                                  "sourceActionCode": "RETRY_DEAD_LETTER",
                                  "sourceIndicatorCode": "DEAD_LETTER_OLDEST_AGE",
                                  "actor": "ops-alice",
                                  "requestId": "INC-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.requestId").value("INC-123"));

        assertEquals("wi-1", graphEngineService.retryDeadLetterItemId);
        assertEquals("validated idempotency", graphEngineService.retryDeadLetterEvidence.reason());
        assertEquals("RETRY_DEAD_LETTER", graphEngineService.retryDeadLetterEvidence.sourceActionCode());
        assertEquals("DEAD_LETTER_OLDEST_AGE", graphEngineService.retryDeadLetterEvidence.sourceIndicatorCode());
        assertEquals("ops-alice", graphEngineService.retryDeadLetterEvidence.actor());
        assertEquals("INC-123", graphEngineService.retryDeadLetterEvidence.requestId());
    }
}
