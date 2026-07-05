package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphOperationsSnapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphOperationsControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvc(new GraphOperationsController(graphEngineService, scopeResolver));
    }

    @Test
    void returnsOperationsSnapshotForCurrentScope() throws Exception {
        graphEngineService.operationsSnapshotResult = new GraphOperationsSnapshot(
                "default",
                "default",
                Instant.parse("2026-07-06T00:00:00Z"),
                500,
                false,
                GraphOperationsSnapshot.Health.CRITICAL,
                Map.of(GraphInstanceStatus.FAILED, 1),
                Map.of(GraphExecutionMode.GRAPH, 1),
                1,
                0,
                1,
                1,
                1,
                1,
                java.util.List.of(new GraphOperationsSnapshot.DeadLetterSample(
                        "dead-1",
                        "exec-1",
                        "approval-flow",
                        "order-1",
                        WorkItemType.EVENT_MATCHED,
                        "approval",
                        "boom",
                        "retry limit exceeded",
                        Instant.parse("2026-07-06T00:00:00Z")
                )),
                java.util.List.of(new GraphOperationsSnapshot.ActionItem(
                        "DEAD_LETTERS_PRESENT",
                        GraphOperationsSnapshot.Health.CRITICAL,
                        "Dead-lettered work items require retry.",
                        "dead-letters",
                        ""
                ))
        );

        mockMvc.perform(get("/api/v1/operations/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("default"))
                .andExpect(jsonPath("$.namespace").value("default"))
                .andExpect(jsonPath("$.health").value("CRITICAL"))
                .andExpect(jsonPath("$.instancesByStatus.FAILED").value(1))
                .andExpect(jsonPath("$.instancesByExecutionMode.GRAPH").value(1))
                .andExpect(jsonPath("$.deadLetterCount").value(1))
                .andExpect(jsonPath("$.recentDeadLetters[0].itemId").value("dead-1"))
                .andExpect(jsonPath("$.actionItems[0].code").value("DEAD_LETTERS_PRESENT"));

        assertEquals("default", graphEngineService.operationsSnapshotTenantId);
        assertEquals("default", graphEngineService.operationsSnapshotNamespace);
    }
}
