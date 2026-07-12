package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(jsonPath("$.payload.features.runEvidenceBundle").value(true));
    }

    @Test
    void returnsStableProblemWhenRequiredIdentityContextIsMissing() throws Exception {
        MockMvc mvc = mvc(new ToolStudioIntegrationService(null, null, null, null));

        mvc.perform(get("/api/integration/drafts/draft-1/export"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value(IntegrationProblem.SCHEMA_VERSION))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.details.tenantId").value("required"))
                .andExpect(jsonPath("$.details.organizationId").value("required"))
                .andExpect(jsonPath("$.details.environmentId").value("required"))
                .andExpect(jsonPath("$.details.actorId").value("required"))
                .andExpect(jsonPath("$.details.purpose").value("required"));
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
                        .header("X-Correlation-Id", "corr-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.DRAFT_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value("corr-404"));
    }

    private static MockMvc mvc(ToolStudioIntegrationService service) {
        return MockMvcBuilders.standaloneSetup(new ToolStudioIntegrationController(service))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }
}
