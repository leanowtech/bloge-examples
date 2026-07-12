package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SideEffectReconciliationControllerTest {

    @Test
    void exposesScopedSummaryAndMapsTrustedIdentity() throws Exception {
        SideEffectReconciliationService reconciliation = mock(SideEffectReconciliationService.class);
        SideEffectReconciliationSummary summary = new SideEffectReconciliationSummary(
                "", "run-1", "evidence:run-1", "sha256:" + "a".repeat(64),
                "OUTSTANDING", "QUARANTINED", List.of("attempt-1"), List.of("unknown commit"), List.of());
        when(reconciliation.summary(eq("run-1"), any())).thenReturn(IntegrationEnvelope.of(
                "SIDE_EFFECT_RECONCILIATION_SUMMARY", SideEffectReconciliationSummary.SCHEMA_VERSION, summary));
        MockMvc mvc = mvc(reconciliation);

        mvc.perform(get("/api/integration/runs/run-1/side-effects/reconciliations")
                        .headers(headers("GOVERNANCE_EVIDENCE_INGESTION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("SIDE_EFFECT_RECONCILIATION_SUMMARY"))
                .andExpect(jsonPath("$.payload.status").value("OUTSTANDING"))
                .andExpect(jsonPath("$.payload.outstandingAttemptIds[0]").value("attempt-1"));

        ArgumentCaptor<IntegrationRequestContext> context = ArgumentCaptor.forClass(IntegrationRequestContext.class);
        verify(reconciliation).summary(eq("run-1"), context.capture());
        assertThat(context.getValue().purpose()).isEqualTo("GOVERNANCE_EVIDENCE_INGESTION");
    }

    @Test
    void routesVersionedReconciliationCommandOnlyWithDedicatedPurpose() throws Exception {
        SideEffectReconciliationService reconciliation = mock(SideEffectReconciliationService.class);
        SideEffectReconciliationRecord record = SideEffectReconciliationRecord.create(
                "request-1", "sha256:" + "d".repeat(64), null, null, null, null, null);
        when(reconciliation.reconcile(eq("run-1"), eq("attempt-1"), any(), any()))
                .thenReturn(IntegrationEnvelope.of("SIDE_EFFECT_RECONCILIATION_RECORD",
                        SideEffectReconciliationRecord.SCHEMA_VERSION, record));
        MockMvc mvc = mvc(reconciliation);
        String body = """
                {
                  "schemaVersion": "toolStudio.resourceGateway.sideEffectReconciliationRequest.v1",
                  "requestId": "request-1",
                  "expectedEvidenceFingerprint": "sha256:%s",
                  "expectedAttemptFingerprint": "sha256:%s"
                }
                """.formatted("a".repeat(64), "b".repeat(64));

        mvc.perform(post("/api/integration/runs/run-1/side-effects/attempt-1/reconcile")
                        .headers(headers("SIDE_EFFECT_RECONCILIATION"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("SIDE_EFFECT_RECONCILIATION_RECORD"))
                .andExpect(jsonPath("$.payload.requestId").value("request-1"));

        ArgumentCaptor<SideEffectReconciliationRequest> request =
                ArgumentCaptor.forClass(SideEffectReconciliationRequest.class);
        verify(reconciliation).reconcile(eq("run-1"), eq("attempt-1"), request.capture(), any());
        assertThat(request.getValue().expectedAttemptFingerprint()).isEqualTo("sha256:" + "b".repeat(64));

        mvc.perform(post("/api/integration/runs/run-1/side-effects/attempt-1/reconcile")
                        .headers(headers("GOVERNANCE_EVIDENCE_INGESTION"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"))
                .andExpect(jsonPath("$.details.operation").value("SIDE_EFFECT_RECONCILIATION_EXECUTE"));
    }

    private static MockMvc mvc(SideEffectReconciliationService reconciliation) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "test-reconciler", "tenant-a", "payments", "tool-studio", "prod", "",
                "WORKLOAD", "reconciliation-worker", "",
                Set.of("GOVERNANCE_EVIDENCE_INGESTION", "SIDE_EFFECT_RECONCILIATION"), Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingIntegrationAccessAuditRepository());
        return MockMvcBuilders.standaloneSetup(new ToolStudioIntegrationController(
                        mock(ToolStudioIntegrationService.class), null, authenticator, reconciliation))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static HttpHeaders headers(String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Tenant-Id", "tenant-a");
        headers.add("X-Organization-Id", "payments");
        headers.add("X-Project-Id", "tool-studio");
        headers.add("X-Environment-Id", "prod");
        headers.add("X-Actor-Id", "reconciliation-worker");
        headers.add("X-Purpose", purpose);
        headers.add("X-Correlation-Id", "corr-reconciliation-controller");
        headers.setBearerAuth("test-token");
        return headers;
    }
}
