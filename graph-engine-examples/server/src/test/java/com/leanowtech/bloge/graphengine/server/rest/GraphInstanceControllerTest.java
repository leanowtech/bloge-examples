package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.graphengine.model.GraphInstance;
import com.leanowtech.bloge.graphengine.model.GraphInstanceDiagram;
import com.leanowtech.bloge.graphengine.model.GraphInstanceContext;
import com.leanowtech.bloge.graphengine.model.GraphInstanceStatus;
import com.leanowtech.bloge.graphengine.model.GraphTransitionEntry;
import com.leanowtech.bloge.graphengine.service.SignalInstanceResult;
import com.leanowtech.bloge.graphengine.service.StartInstanceResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphInstanceControllerTest extends AbstractGraphControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties.setDefaultEnvironment("prod");
        mockMvc = mockMvc(new GraphInstanceController(graphEngineService, scopeResolver, properties));
    }

    @Test
    void startInstanceUsesConfiguredDefaultEnvironment() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.SUSPENDED);
        graphEngineService.startInstanceResult = new StartInstanceResult(instance, Map.of("approval", "WAITING"));

        mockMvc.perform(post("/api/v1/graphs/approval-flow/instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessKey": "approval-001",
                                  "initiator": "starter",
                                  "variables": {
                                    "orderId": "approval-001"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/instances/exec-1")))
                .andExpect(jsonPath("$.instance.instanceId").value("exec-1"))
                .andExpect(jsonPath("$.suspendedNodes.approval").value("WAITING"));

        assertEquals("approval-flow", graphEngineService.startInstanceCommand.definitionKey());
        assertEquals("prod", graphEngineService.startInstanceCommand.environment());
        assertEquals("approval-001", graphEngineService.startInstanceCommand.businessKey());
    }

    @Test
    void signalInstanceMapsPayloadFields() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.RUNNING);
        graphEngineService.signalInstanceResult = new SignalInstanceResult(instance, Map.of());

        mockMvc.perform(post("/api/v1/instances/exec-1/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nodeId": "approval",
                                  "eventName": "approved",
                                  "payload": {
                                    "approved": true
                                  },
                                  "callerId": "api-user"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.instanceId").value("exec-1"));

        assertEquals("exec-1", graphEngineService.signalInstanceCommand.instanceId());
        assertEquals("approval", graphEngineService.signalInstanceCommand.nodeId());
        assertEquals("approved", graphEngineService.signalInstanceCommand.eventName());
        assertEquals("api-user", graphEngineService.signalInstanceCommand.callerId());
    }

    @Test
    void cancelInstanceMapsRevisionAndReason() throws Exception {
        GraphInstance cancelled = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.CANCELLED);
        graphEngineService.cancelInstanceResult = cancelled;

        mockMvc.perform(post("/api/v1/instances/exec-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 3,
                                  "reason": "duplicate order"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals("exec-1", graphEngineService.cancelInstanceId);
        assertEquals("duplicate order", graphEngineService.cancelInstanceReason);
        assertEquals(3L, graphEngineService.cancelInstanceExpectedRevision);
    }

    @Test
    void auditEndpointReturnsSerializedAuditEntries() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.COMPLETED);
        graphEngineService.queryAuditLogResult = java.util.List.of(auditEntry(instance, "approval"));

        mockMvc.perform(get("/api/v1/instances/exec-1/audit?page=1&size=25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value("approval"))
                .andExpect(jsonPath("$[0].eventType").value("NODE_COMPLETE"));

        assertEquals("exec-1", graphEngineService.auditInstanceId);
        assertEquals(1, graphEngineService.auditPage);
        assertEquals(25, graphEngineService.auditSize);
    }

    @Test
    void controlActionsEndpointReturnsStructuredTimeline() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.COMPLETED);
        graphEngineService.queryControlActionsResult = java.util.List.of(controlActionEntry(instance));

        mockMvc.perform(get("/api/v1/instances/exec-1/control-actions?page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionCode").value("RETRY_DEAD_LETTER"))
                .andExpect(jsonPath("$[0].attemptStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].status").value("RESTORED"))
                .andExpect(jsonPath("$[0].requestId").value("INC-123"))
                .andExpect(jsonPath("$[0].restoredItemCount").value(1))
                .andExpect(jsonPath("$[0].restoredItemIds[0]").value("dead-1"));

        assertEquals("exec-1", graphEngineService.controlActionsInstanceId);
        assertEquals(2, graphEngineService.controlActionsPage);
        assertEquals(10, graphEngineService.controlActionsSize);
    }

    @Test
    void transitionsEndpointReturnsTransitionHistory() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.RUNNING);
        GraphTransitionEntry transition = transitionEntry(instance, GraphInstanceStatus.CANCELLED);
        graphEngineService.queryTransitionsResult = java.util.List.of(transition);

        mockMvc.perform(get("/api/v1/instances/exec-1/transitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toStatus").value("CANCELLED"))
                .andExpect(jsonPath("$[0].transitionSource").value("graph-engine-service"));

        assertEquals("exec-1", graphEngineService.transitionsInstanceId);
        assertEquals(0, graphEngineService.transitionsPage);
        assertEquals(50, graphEngineService.transitionsSize);
    }

    @Test
    void queryInstanceNodesReturnsNodeStates() throws Exception {
        graphEngineService.queryNodesResult = java.util.List.of(
                new com.leanowtech.bloge.graphengine.model.GraphNodeState(
                        "validate", "ValidateOp",
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.COMPLETED,
                        0, 0, null, null,
                        java.time.Instant.parse("2025-01-01T00:00:00Z"),
                        java.time.Instant.parse("2025-01-01T00:00:01Z")),
                new com.leanowtech.bloge.graphengine.model.GraphNodeState(
                        "process", "ProcessOp",
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.RUNNING,
                        1, 3, "timeout", null,
                        java.time.Instant.parse("2025-01-01T00:00:02Z"),
                        null),
                new com.leanowtech.bloge.graphengine.model.GraphNodeState(
                        "notify", "NotifyOp",
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.NOT_STARTED,
                        0, 0, null, null, null, null)
        );

        mockMvc.perform(get("/api/v1/instances/exec-1/nodes?status=COMPLETED,RUNNING&page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].nodeId").value("validate"))
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[1].nodeId").value("process"))
                .andExpect(jsonPath("$.items[1].status").value("RUNNING"))
                .andExpect(jsonPath("$.items[1].lastError").value("timeout"));

        assertEquals("exec-1", graphEngineService.queryNodesInstanceId);
        assertEquals(Set.of(
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.RUNNING,
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.COMPLETED),
                graphEngineService.queryNodeStatuses);
        assertEquals(0, graphEngineService.queryNodesPage);
        assertEquals(2, graphEngineService.queryNodesSize);
    }

    @Test
    void getInstanceDiagramReturnsLayoutAndNodeOverlay() throws Exception {
        graphEngineService.instanceDiagramResult = new GraphInstanceDiagram(
                "exec-1",
                "ver-1",
                "{\"nodes\":[{\"id\":\"approval\"}]}",
                java.util.List.of(new com.leanowtech.bloge.graphengine.model.GraphNodeState(
                        "approval",
                        null,
                        com.leanowtech.bloge.graphengine.model.GraphNodeStatus.WAITING,
                        0,
                        0,
                        null,
                        null,
                        java.time.Instant.parse("2025-01-01T00:00:00Z"),
                        null
                ))
        );

        mockMvc.perform(get("/api/v1/instances/exec-1/diagram"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value("exec-1"))
                .andExpect(jsonPath("$.versionId").value("ver-1"))
                .andExpect(jsonPath("$.visualLayout").value("{\"nodes\":[{\"id\":\"approval\"}]}"))
                .andExpect(jsonPath("$.nodeStates[0].nodeId").value("approval"))
                .andExpect(jsonPath("$.nodeStates[0].status").value("WAITING"));

        assertEquals("exec-1", graphEngineService.instanceDiagramInstanceId);
    }

    @Test
    void getInstanceContextReturnsSerializedProjection() throws Exception {
        graphEngineService.queryContextResult = new GraphInstanceContext(
                "exec-1",
                com.leanowtech.bloge.graphengine.model.GraphExecutionMode.SESSION,
                Map.of("orderId", "approval-001"),
                Map.of("approval", Map.of("approved", true)),
                Map.of("sessionOwner", "alice"),
                Map.of("review", Map.of("decision", "approve")),
                Map.of(),
                java.time.Instant.parse("2025-01-01T00:05:00Z")
        );

        mockMvc.perform(get("/api/v1/instances/exec-1/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value("exec-1"))
                .andExpect(jsonPath("$.executionMode").value("SESSION"))
                .andExpect(jsonPath("$.startVariables.orderId").value("approval-001"))
                .andExpect(jsonPath("$.nodeOutputs.approval.approved").value(true))
                .andExpect(jsonPath("$.sharedState.sessionOwner").value("alice"))
                .andExpect(jsonPath("$.phaseOutputs.review.decision").value("approve"))
                .andExpect(jsonPath("$.snapshotAt").exists());

        assertEquals("exec-1", graphEngineService.queryContextInstanceId);
    }

    @Test
    void queryPendingSignalsReturnsSerializedSignals() throws Exception {
        graphEngineService.queryPendingSignalsResult = java.util.List.of(
                new com.leanowtech.bloge.graphengine.model.GraphPendingSignal(
                        "approval",
                        "approval.completed",
                        "orderId",
                        "approval-001",
                        true,
                        OpaqueSchema.INSTANCE,
                        java.time.Instant.parse("2025-01-01T00:00:00Z"),
                        java.time.Instant.parse("2025-01-01T00:05:00Z")
                )
        );

        mockMvc.perform(get("/api/v1/instances/exec-1/pending-signals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value("approval"))
                .andExpect(jsonPath("$[0].eventName").value("approval.completed"))
                .andExpect(jsonPath("$[0].correlationKey").value("orderId"))
                .andExpect(jsonPath("$[0].optional").value(true))
                .andExpect(jsonPath("$[0].signalSchema.kind").value("opaque"))
                .andExpect(jsonPath("$[0].timeoutAt").exists());

        assertEquals("exec-1", graphEngineService.queryPendingSignalsInstanceId);
    }

    @Test
    void retryInstanceMapsNodeIdsAndRevision() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.RUNNING);
        graphEngineService.retryInstanceResult = new com.leanowtech.bloge.graphengine.service.RetryInstanceResult(instance, 2);

        mockMvc.perform(post("/api/v1/instances/exec-1/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nodeIds": ["validate", "process"],
                                  "expectedRevision": 5,
                                  "reason": "retry after downstream fix",
                                  "sourceActionCode": "RETRY_INSTANCE_DEAD_LETTERS",
                                  "sourceIndicatorCode": "FAILED_INSTANCE_BACKLOG",
                                  "actor": "ops-bot",
                                  "requestId": "REQ-7788"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.instanceId").value("exec-1"))
                .andExpect(jsonPath("$.retriedItemCount").value(2));

        assertEquals("exec-1", graphEngineService.retryInstanceId);
        assertEquals(java.util.Set.of("validate", "process"), graphEngineService.retryNodeIds);
        assertEquals(5L, graphEngineService.retryExpectedRevision);
        assertEquals("retry after downstream fix", graphEngineService.retryInstanceEvidence.reason());
        assertEquals("RETRY_INSTANCE_DEAD_LETTERS", graphEngineService.retryInstanceEvidence.sourceActionCode());
        assertEquals("FAILED_INSTANCE_BACKLOG", graphEngineService.retryInstanceEvidence.sourceIndicatorCode());
        assertEquals("ops-bot", graphEngineService.retryInstanceEvidence.actor());
        assertEquals("REQ-7788", graphEngineService.retryInstanceEvidence.requestId());
    }

    @Test
    void retryInstanceWithEmptyNodeIds() throws Exception {
        GraphInstance instance = instance("exec-1", "approval-flow", "ver-1", GraphInstanceStatus.RUNNING);
        graphEngineService.retryInstanceResult = new com.leanowtech.bloge.graphengine.service.RetryInstanceResult(instance, 3);

        mockMvc.perform(post("/api/v1/instances/exec-1/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retriedItemCount").value(3));

        assertEquals("exec-1", graphEngineService.retryInstanceId);
        assertNull(graphEngineService.retryNodeIds);
        assertEquals(1L, graphEngineService.retryExpectedRevision);
        assertTrue(graphEngineService.retryInstanceEvidence.emptyEvidence());
    }
}
