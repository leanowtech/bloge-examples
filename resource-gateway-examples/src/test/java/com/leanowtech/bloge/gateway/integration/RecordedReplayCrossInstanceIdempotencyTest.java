package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.ReplayExecutionRequest;
import com.leanowtech.bloge.gateway.integration.ReplayExecutionResult;
import com.leanowtech.bloge.gateway.integration.ToolStudioIntegrationService;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordedReplayCrossInstanceIdempotencyTest {

    @Test
    void concurrentInstancesReturnTheSameReplayAndScopeRequestIdsByTenant() throws Exception {
        DataSource dataSource = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DatabaseVisualEvidenceSigner signer = new DatabaseVisualEvidenceSigner(jdbc);
        var policy = new ConfiguredVisualPayloadGovernancePolicy("replay-test", "1", "PUBLIC", Set.of(),
                Map.of("PUBLIC", Duration.ofDays(7)));
        DatabaseVisualRunPayloadRepository payloadsA = new DatabaseVisualRunPayloadRepository(
                jdbc, mapper, policy, signer);
        DatabaseVisualRunPayloadRepository payloadsB = new DatabaseVisualRunPayloadRepository(
                jdbc, mapper, policy, signer);
        payloadsA.init();
        payloadsB.init();
        DatabaseVisualGraphRunRepository runsA = new DatabaseVisualGraphRunRepository(
                jdbc, mapper, signer, null, payloadsA);
        DatabaseVisualGraphRunRepository runsB = new DatabaseVisualGraphRunRepository(
                jdbc, mapper, signer, null, payloadsB);
        runsA.init();
        runsB.init();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        VisualGraphRunRecord parent = transaction.execute(status -> runsA.create(record("parent-a", draft(
                "draft-a", "tenant-a"))));
        ToolStudioIntegrationService serviceA = new ToolStudioIntegrationService(null, null, null, runsA);
        ToolStudioIntegrationService serviceB = new ToolStudioIntegrationService(null, null, null, runsB);
        ReplayExecutionRequest request = request("same-request", true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ReplayExecutionResult> first = executor.submit(() -> execute(
                    transaction, ready, start, serviceA, parent.runId(), request, context("tenant-a", "corr-a")));
            Future<ReplayExecutionResult> second = executor.submit(() -> execute(
                    transaction, ready, start, serviceB, parent.runId(), request, context("tenant-a", "corr-b")));
            ready.await();
            start.countDown();

            assertThat(first.get().replayRunId()).isEqualTo(second.get().replayRunId());
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_run_payload_states", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> transaction.execute(status -> serviceB.executeReplay(parent.runId(),
                request("same-request", false), context("tenant-a", "corr-conflict"))))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.INTEGRATION.REPLAY_REQUEST_ID_CONFLICT"));

        VisualGraphRunRecord parentB = transaction.execute(status -> runsA.create(record(
                "parent-b", draft("draft-b", "tenant-b"))));
        ReplayExecutionResult tenantB = transaction.execute(status -> serviceA.executeReplay(
                parentB.runId(), request, context("tenant-b", "corr-tenant-b"))).payload();
        assertThat(tenantB.replayRunId()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visual_graph_run_records", Long.class)).isEqualTo(4);
    }

    private static ReplayExecutionResult execute(TransactionTemplate transaction,
                                                 CountDownLatch ready,
                                                 CountDownLatch start,
                                                 ToolStudioIntegrationService service,
                                                 String parentRunId,
                                                 ReplayExecutionRequest request,
                                                 IntegrationRequestContext context) throws Exception {
        ready.countDown();
        start.await();
        return transaction.execute(status -> service.executeReplay(parentRunId, request, context)).payload();
    }

    private static ReplayExecutionRequest request(String requestId, boolean expected) {
        return new ReplayExecutionRequest("", requestId, "RECORDED_ASSERTIONS", "REGRESSION", "DENY",
                List.of(new ReplayExecutionRequest.Assertion("decision", "OUTPUT", "", "PATH_EQUALS",
                        "/approved", expected)));
    }

    private static IntegrationRequestContext context(String tenantId, String correlationId) {
        return new IntegrationRequestContext(tenantId, "org-a", "project-a", "prod", "local", "WORKLOAD",
                "aneke", "", "PAYLOAD_REPLAY", correlationId, Set.of(), "PUBLIC", "");
    }

    private static GraphDraft draft(String draftId, String tenantId) {
        return new GraphDraft("", draftId, 1, "decisionGraph", tenantId, "knowledge", "prod", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection("decision", ""), Map.of(), Map.of(),
                GraphDraft.RevisionMetadata.empty());
    }

    private static VisualGraphRunRecord record(String runId, GraphDraft draft) {
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, draft.graphName(), "decision",
                Map.of("approved", true), Map.of("decision", Map.of("approved", true)),
                Map.of("decision", "SUCCESS"), 5, Map.of("decision", 3L), List.of(), List.of(), null, null,
                "graph decisionGraph {}");
        return VisualGraphRunRecord.storedDraft(draft, Map.of("subject", "customer-42"), response)
                .withIdentity(runId, Instant.parse("2026-07-13T00:00:00Z"));
    }
}
