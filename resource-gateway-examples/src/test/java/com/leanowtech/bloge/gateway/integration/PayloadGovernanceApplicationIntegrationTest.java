package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "spring.datasource.url=jdbc:h2:mem:payload-governance-application;DB_CLOSE_DELAY=-1",
                "gateway.integration.payload-governance.default-classification=CONFIDENTIAL",
                "gateway.integration.payload-governance.retention-days.confidential=7",
                "gateway.integration.identity.clearance=CONFIDENTIAL",
                "gateway.integration.identity.groups=payload-governance"
        })
class PayloadGovernanceApplicationIntegrationTest {

    @Autowired
    private VisualGraphRunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realHttpAndDatabaseLifecycleDetachesHoldsPurgesAndRetainsVerifiableEvidence() throws Exception {
        VisualGraphRunRecord created = runs.create(record("run-http-retention"));

        String runJson = jdbc.queryForObject(
                "SELECT run_json FROM visual_graph_run_records WHERE run_id = ?", String.class, created.runId());
        assertThat(runJson).doesNotContain("customer-42", "approved");
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM visual_run_payload_blobs WHERE run_id = ?", String.class, created.runId()))
                .contains("customer-42");

        ResponseEntity<String> replay = exchange(HttpMethod.GET,
                "/api/integration/runs/run-http-retention/replay", "PAYLOAD_REPLAY", null);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode replayJson = objectMapper.readTree(replay.getBody());
        assertThat(replayJson.at("/payload/context/customerId").asText()).isEqualTo("customer-42");
        assertThat(replayJson.at("/payload/retention/descriptor/classification").asText())
                .isEqualTo("CONFIDENTIAL");

        ResponseEntity<String> hold = exchange(HttpMethod.POST,
                "/api/integration/runs/run-http-retention/payload-retention/holds", "LEGAL_HOLD",
                Map.of("requestId", "request-hold", "holdId", "case-http", "reason", "litigation"));
        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(hold.getBody()).at("/payload/status/state").asText())
                .isEqualTo("LEGAL_HOLD");

        ResponseEntity<String> blockedPurge = exchange(HttpMethod.POST,
                "/api/integration/runs/run-http-retention/payload-retention/purge", "PAYLOAD_RETENTION_ADMIN",
                Map.of("requestId", "request-purge-1", "reason", "expired"));
        assertThat(blockedPurge.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(blockedPurge.getBody()).path("code").asText())
                .isEqualTo("RG.INTEGRATION.PAYLOAD_LIFECYCLE_CONFLICT");

        assertThat(exchange(HttpMethod.POST,
                "/api/integration/runs/run-http-retention/payload-retention/holds/case-http/release", "LEGAL_HOLD",
                Map.of("requestId", "request-release", "reason", "case closed")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> purged = exchange(HttpMethod.POST,
                "/api/integration/runs/run-http-retention/payload-retention/purge", "PAYLOAD_RETENTION_ADMIN",
                Map.of("requestId", "request-purge-2", "reason", "retention ticket"));
        assertThat(objectMapper.readTree(purged.getBody()).at("/payload/status/state").asText())
                .isEqualTo("PURGED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM visual_run_payload_blobs WHERE run_id = ?", Long.class, created.runId()))
                .isZero();

        ResponseEntity<String> gone = exchange(HttpMethod.GET,
                "/api/integration/runs/run-http-retention/replay", "PAYLOAD_REPLAY", null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(objectMapper.readTree(gone.getBody()).path("code").asText())
                .isEqualTo("RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE");

        ResponseEntity<String> evidence = exchange(HttpMethod.GET,
                "/api/integration/runs/run-http-retention/evidence", "GOVERNANCE_EVIDENCE_INGESTION", null);
        JsonNode evidenceJson = objectMapper.readTree(evidence.getBody());
        assertThat(evidenceJson.at("/payload/retention/state").asText()).isEqualTo("PURGED");
        assertThat(evidenceJson.at("/payload/manifest/signatureStatus").asText()).isEqualTo("VERIFIED");

        JsonNode events = objectMapper.readTree(exchange(HttpMethod.GET,
                "/api/integration/events?limit=100", "CHANGE_SYNC", null).getBody());
        assertThat(events.at("/payload/events").findValuesAsText("eventType"))
                .contains("PAYLOAD_CAPTURED", "PAYLOAD_HOLD_PLACED", "PAYLOAD_HOLD_RELEASED", "PAYLOAD_PURGED");
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String purpose, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bloge-aneke-demo-token");
        headers.set("X-Purpose", purpose);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static VisualGraphRunRecord record(String runId) {
        GraphDraft draft = new GraphDraft("", "draft-http", 1, "visualPolicy", "tenant-a", "local", "prod",
                "", SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("response", ""));
        VisualGraphRunResponse response = new VisualGraphRunResponse(true, true, true, "visualPolicy", "response",
                Map.of("decision", "approved"), Map.of("response", Map.of("decision", "approved")),
                Map.of("response", "COMPLETED"), 10, Map.of("response", 5L), List.of(), List.of(), null, null,
                "graph visualPolicy {}");
        return VisualGraphRunRecord.storedDraft(draft,
                Map.of("customerId", "customer-42", "apiToken", "secret-token"), response)
                .withIdentity(runId, null);
    }
}
