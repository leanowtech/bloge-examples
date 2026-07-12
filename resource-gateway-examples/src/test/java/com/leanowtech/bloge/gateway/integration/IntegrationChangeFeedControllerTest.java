package com.leanowtech.bloge.gateway.integration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IntegrationChangeFeedControllerTest {

    @Test
    void mapsOpaqueCursorPaginationAndTrustedContextToChangeFeedService() throws Exception {
        IntegrationChangeFeedService changes = mock(IntegrationChangeFeedService.class);
        ToolStudioIntegrationService integration = mock(ToolStudioIntegrationService.class);
        IntegrationChangeFeed feed = new IntegrationChangeFeed("", List.of(), "next", "checkpoint", false, 0);
        when(changes.events(eq("opaque-cursor"), eq(25), any()))
                .thenReturn(IntegrationEnvelope.of("INTEGRATION_CHANGE_FEED",
                        IntegrationChangeFeed.SCHEMA_VERSION, feed));
        MockMvc mvc = mvc(integration, changes);

        mvc.perform(get("/api/integration/events")
                        .param("cursor", "opaque-cursor")
                        .param("limit", "25")
                        .headers(headers()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("INTEGRATION_CHANGE_FEED"))
                .andExpect(jsonPath("$.payload.nextCursor").value("next"))
                .andExpect(jsonPath("$.payload.checkpointCursor").value("checkpoint"));

        ArgumentCaptor<IntegrationRequestContext> context = ArgumentCaptor.forClass(IntegrationRequestContext.class);
        verify(changes).events(eq("opaque-cursor"), eq(25), context.capture());
        assertThat(context.getValue())
                .extracting(IntegrationRequestContext::tenantId, IntegrationRequestContext::environmentId,
                        IntegrationRequestContext::purpose, IntegrationRequestContext::actorId)
                .containsExactly("tenant-a", "prod", "CHANGE_SYNC", "aneke-sync");
    }

    @Test
    void exposesReconciliationAsVersionedIntegrationEnvelope() throws Exception {
        IntegrationChangeFeedService changes = mock(IntegrationChangeFeedService.class);
        ToolStudioIntegrationService integration = mock(ToolStudioIntegrationService.class);
        IntegrationReconciliationSnapshot snapshot = new IntegrationReconciliationSnapshot("", "tenant-a",
                "prod", Instant.parse("2026-07-12T00:00:00Z"), "checkpoint", List.of(), Map.of(),
                "sha256:empty");
        when(changes.reconciliation(any())).thenReturn(IntegrationEnvelope.of(
                "INTEGRATION_RECONCILIATION_SNAPSHOT", IntegrationReconciliationSnapshot.SCHEMA_VERSION,
                snapshot));

        mvc(integration, changes).perform(get("/api/integration/reconciliation").headers(headers()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payloadKind").value("INTEGRATION_RECONCILIATION_SNAPSHOT"))
                .andExpect(jsonPath("$.payload.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.payload.checkpointCursor").value("checkpoint"));
    }

    private static MockMvc mvc(ToolStudioIntegrationService integration,
                               IntegrationChangeFeedService changes) {
        return MockMvcBuilders.standaloneSetup(new ToolStudioIntegrationController(integration, changes))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();
    }

    private static org.springframework.http.HttpHeaders headers() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Tenant-Id", "tenant-a");
        headers.add("X-Organization-Id", "org-a");
        headers.add("X-Project-Id", "project-a");
        headers.add("X-Environment-Id", "prod");
        headers.add("X-Actor-Id", "aneke-sync");
        headers.add("X-Purpose", "CHANGE_SYNC");
        headers.add("X-Correlation-Id", "corr-controller");
        return headers;
    }
}
