package com.leanowtech.bloge.examples.durable;

import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteWorkerExecutionDslExampleTest {

    @Test
    void executionModeRemote_enqueuesWorkItemAndSuspendsNode() {
        var dispatch = RemoteWorkerExecutionDslExample.executeUntilDispatch("RPT-42", "CUST-9");

        assertTrue(dispatch.suspendResult().suspendKey().startsWith("worker:remote:"));
        assertEquals(Duration.ofSeconds(30), dispatch.suspendResult().timeout());

        var workItems = dispatch.workItemStore().createdItems();
        assertEquals(1, workItems.size());
        assertEquals(workItems.getFirst().itemId(), dispatch.notifier().itemIds().getFirst());

        var workItem = workItems.getFirst();
        assertEquals(WorkItemType.EXECUTE_NODE, workItem.itemType());
        assertEquals(WorkItemStatus.READY, workItem.status());
        assertEquals(RemoteWorkerExecutionDslExample.NODE_RENDER_PDF, workItem.nodeId());
        assertEquals(RemoteWorkerExecutionDslExample.WORKER_TOPIC, workItem.identity().shardId());
        assertEquals("tenant-demo", workItem.identity().tenantId());
        assertEquals("examples", workItem.identity().namespace());
        assertEquals("RPT-42", workItem.identity().businessKey());

        RemoteWorkerEnvelope envelope = RemoteWorkerExecutionDslExample.decodeEnvelope(workItem);
        assertEquals("PdfRenderOperator", envelope.operatorRef());
        assertEquals(RemoteWorkerExecutionDslExample.WORKER_TOPIC, envelope.workerTopic());
        assertEquals(RemoteWorkerExecutionDslExample.NODE_RENDER_PDF, envelope.nodeId());
        assertEquals("remote-report-rendering", envelope.definitionKey());
        assertEquals("v1", envelope.versionId());
        assertEquals(2, envelope.retryPolicy().retryAttempts());
        assertEquals(Duration.ofSeconds(1), envelope.retryPolicy().backoff());

        Map<?, ?> input = (Map<?, ?>) envelope.input();
        assertEquals("RPT-42", input.get("reportId"));
        assertEquals("CUST-9", input.get("customerId"));
        assertEquals(12, ((Number) input.get("pageCount")).intValue());
    }

    @Test
    void workerCanPollClaimAndProduceJsonFriendlyOutput() {
        var dispatch = RemoteWorkerExecutionDslExample.executeUntilDispatch("RPT-84", "CUST-7");

        var claimed = RemoteWorkerExecutionDslExample.pollFirstJob(dispatch, "worker-pdf-1");

        assertEquals(WorkItemStatus.CLAIMED, claimed.workItem().status());
        assertEquals("worker-pdf-1", claimed.workItem().claimOwner());
        assertNotNull(claimed.workItem().claimToken());
        assertEquals(1, claimed.workItem().version());

        Map<String, Object> output = RemoteWorkerExecutionDslExample.renderPdf(claimed.envelope());
        assertEquals("s3://reports/RPT-84.pdf", output.get("documentUrl"));
        assertEquals("sha256:RPT-84:12", output.get("checksum"));
        assertFalse(dispatch.workItemStore()
                .pollReady(WorkItemType.EXECUTE_NODE, RemoteWorkerExecutionDslExample.WORKER_TOPIC, 1)
                .contains(claimed.workItem()));
    }
}