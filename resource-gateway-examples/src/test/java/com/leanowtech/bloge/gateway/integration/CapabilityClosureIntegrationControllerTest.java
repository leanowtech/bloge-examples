package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityClosureIntegrationControllerTest {
    private final CapabilityClosureIntegrationService service = mock(CapabilityClosureIntegrationService.class);
    private final MockMvc mvc = mvc(service);

    @Test
    void projectsOnlyAfterCredentialAndPurposeAuthorization() throws Exception {
        when(service.project(any(), any())).thenReturn(new IntegrationEnvelope<>("", "", "", "", null,
                null, "CAPABILITY_CLOSURE", CapabilityClosure.SCHEMA_VERSION,
                "sha256:" + "a".repeat(64), null));

        mvc.perform(post("/api/integration/capability-closures/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson())
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CAPABILITY_PROJECTION")
                        .header("X-Correlation-Id", "corr-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("CAPABILITY_CLOSURE"))
                .andExpect(jsonPath("$.payloadSchemaVersion").value(CapabilityClosure.SCHEMA_VERSION));

        verify(service).project(any(), any());
    }

    @Test
    void rejectsPurposeThatIsValidForAnotherIntegrationOperation() throws Exception {
        mvc.perform(post("/api/integration/capability-closures/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson())
                        .header("Authorization", "Bearer test-token")
                        .header("X-Purpose", "CHANGE_SYNC")
                        .header("X-Correlation-Id", "corr-purpose"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.PURPOSE_FORBIDDEN"))
                .andExpect(jsonPath("$.details.operation").value("CAPABILITY_CLOSURE_PROJECTION"));
    }

    @Test
    void rejectsAnonymousProjectionBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/integration/capability-closures/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson())
                        .header("X-Purpose", "CAPABILITY_PROJECTION")
                        .header("X-Correlation-Id", "corr-auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    private static MockMvc mvc(CapabilityClosureIntegrationService service) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity("test-aneke", "tenant-a",
                "knowledge-governance", "tool-studio", "prod", "sg", "WORKLOAD", "aneke-sync", "",
                Set.of("CAPABILITY_PROJECTION", "CHANGE_SYNC"), Instant.MAX, true,
                Set.of("capability-governance"), "CONFIDENTIAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("test-token", identity, false),
                new RecordingIntegrationAccessAuditRepository());
        return MockMvcBuilders.standaloneSetup(new CapabilityClosureIntegrationController(service, authenticator))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static String requestJson() {
        return """
                {
                  "schemaVersion": "resourceGateway.capabilityClosureProjectionRequest.v1",
                  "draft": {
                    "schemaVersion": "bloge.visualGraphDraft.v1",
                    "graphName": "customerView",
                    "nodes": [],
                    "edges": [],
                    "output": {"nodeId": ""}
                  },
                  "revision": 1,
                  "createdAt": "2026-07-22T08:00:00Z",
                  "classification": "CONFIDENTIAL"
                }
                """;
    }
}
