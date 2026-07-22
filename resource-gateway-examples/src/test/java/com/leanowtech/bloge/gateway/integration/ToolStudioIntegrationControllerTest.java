package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Set;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrationService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class ToolStudioIntegrationControllerTest {

    @Test
    void exposesVersionedCapabilityEnvelope() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(get("/api/integration/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value(ToolStudioResourceGatewayProtocol.NAME))
                .andExpect(jsonPath("$.protocolVersion").value(ToolStudioResourceGatewayProtocol.VERSION))
                .andExpect(jsonPath("$.payloadKind").value("CAPABILITIES"))
                .andExpect(jsonPath("$.payload.features.draftExportDependencyProfile").value(true))
                .andExpect(jsonPath("$.payload.features.runEvidenceBundle").value(true))
                .andExpect(jsonPath("$.payload.features.capabilitySnapshotApi").value(true))
                .andExpect(jsonPath("$.payload.features.mirrorServing").value(false));
    }

    @Test
    void rejectsSelfAssertedIdentityHeadersWithoutVerifiedCredential() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(get("/api/integration/drafts/draft-1/export")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Organization-Id", "knowledge-governance")
                        .header("X-Environment-Id", "prod")
                        .header("X-Actor-Id", "aneke-sync")
                        .header("X-Purpose", "GOVERNANCE_EVIDENCE_INGESTION"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"resource-gateway-integration\""))
                .andExpect(jsonPath("$.schemaVersion").value(IntegrationProblem.SCHEMA_VERSION))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void hidesDraftExistenceOutsideAuthorizedScope() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(get("/api/integration/drafts/draft-1/export")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Organization-Id", "knowledge-governance")
                        .header("X-Environment-Id", "prod")
                        .header("X-Actor-Id", "aneke-sync")
                        .header("X-Purpose", "GOVERNANCE_EVIDENCE_INGESTION")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Correlation-Id", "corr-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.DRAFT_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("corr-404"));
    }

    @Test
    void replayCommandRequiresExplicitPayloadReplayPurpose() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(post("/api/integration/runs/run-1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"request-1","caseType":"REGRESSION","assertions":[
                                  {"assertionId":"output","scope":"OUTPUT","mode":"PATH_EXISTS","path":"/id"}
                                ]}
                                """)
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Organization-Id", "knowledge-governance")
                        .header("X-Environment-Id", "prod")
                        .header("X-Actor-Id", "aneke-replay")
                        .header("X-Purpose", "GOVERNANCE_EVIDENCE_INGESTION")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Correlation-Id", "corr-purpose"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"))
                .andExpect(jsonPath("$.details.operation").value("RECORDED_REPLAY"));
    }

    @Test
    void semanticWorkbookRouteFailsClosedWithoutIsolatedTestRuntime() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(get("/api/integration/test-suites/suite-risk/revisions/2/"
                        + "semantic-correctness-workbook")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Organization-Id", "knowledge-governance")
                        .header("X-Environment-Id", "prod")
                        .header("X-Actor-Id", "aneke-sync")
                        .header("X-Purpose", "GOVERNANCE_EVIDENCE_INGESTION")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Correlation-Id", "corr-semantic-workbook"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("RG.INTEGRATION.SEMANTIC_WORKBOOK_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("corr-semantic-workbook"));
    }

    @Test
    void capabilitySnapshotReadRejectsPurposeOutsideItsOperationPolicy() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null),
                mock(CapabilitySnapshotIntegrationService.class));

        mvc.perform(get("/api/integration/capability-snapshots/resource:orders.get")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Organization-Id", "knowledge-governance")
                        .header("X-Environment-Id", "prod")
                        .header("X-Actor-Id", "aneke-sync")
                        .header("X-Purpose", "PAYLOAD_REPLAY")
                        .header("Authorization", "Bearer test-token")
                        .header("X-Correlation-Id", "corr-capability-purpose"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"))
                .andExpect(jsonPath("$.details.operation").value("CAPABILITY_SNAPSHOT_READ"));
    }

    private static MockMvc mvc(ToolStudioIntegrationService service) {
        return mvc(service, null);
    }

    private static MockMvc mvc(ToolStudioIntegrationService service,
                               CapabilitySnapshotIntegrationService capabilitySnapshots) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity("test-aneke", "tenant-a",
                "knowledge-governance", "tool-studio", "prod", "", "WORKLOAD", "aneke-sync", "",
                Set.of("GOVERNANCE_EVIDENCE_INGESTION", "PAYLOAD_REPLAY", "CHANGE_SYNC"), Instant.MAX, true);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingIntegrationAccessAuditRepository());
        return MockMvcBuilders.standaloneSetup(new ToolStudioIntegrationController(
                        service, null, authenticator, null, capabilitySnapshots))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }
}
