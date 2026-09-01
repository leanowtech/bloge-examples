package com.leanowtech.bloge.gateway.visualadapter.authoring.flow;

import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationWorkloadIdentity;
import com.leanowtech.bloge.gateway.integration.StaticBearerIntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalogItem;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ComposableCatalogControllerTest {
    @Test
    void listsBothKindsInTrustedScopeWithoutMutationHeaders() throws Exception {
        ComposableCatalog catalog = mock(ComposableCatalog.class);
        AuthoringScope scope = new AuthoringScope("tenant-a", "project-a", "test");
        ReusableFlowCommand.ComposableRef.FlowVersion reference =
                new ReusableFlowCommand.ComposableRef.FlowVersion(
                        "published-tool", 2, "sha256:" + "a".repeat(64));
        SchemaEnvelope schema = SchemaEnvelope.object(
                Map.of("id", Map.of("type", "string")), List.of("id"));
        when(catalog.list(eq(scope), eq(Set.of(ComposableCatalog.Kind.API_RESOURCE,
                ComposableCatalog.Kind.FLOW_VERSION)), eq(100))).thenReturn(List.of(
                        new ComposableCatalogItem(null, "Published tool", reference,
                                new ReusableFlowCommand.Contract(schema, schema))));

        mvc(catalog).perform(get("/api/authoring/catalog")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].reference.kind").value("FLOW_VERSION"))
                .andExpect(jsonPath("$[0].reference.publicationId").value("published-tool"))
                .andExpect(jsonPath("$[0].contract.input.schema.properties.id.type").value("string"));

        verify(catalog).list(scope, Set.of(ComposableCatalog.Kind.API_RESOURCE,
                ComposableCatalog.Kind.FLOW_VERSION), 100);
    }

    @Test
    void rejectsOutOfRangeLimitBeforeReadingTheCatalog() throws Exception {
        ComposableCatalog catalog = mock(ComposableCatalog.class);

        mvc(catalog).perform(get("/api/authoring/catalog")
                        .queryParam("limit", "0")
                        .header("Authorization", "Bearer author-token")
                        .header("X-Purpose", "API_RESOURCE_AUTHORING"))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    private static MockMvc mvc(ComposableCatalog catalog) {
        IntegrationWorkloadIdentity identity = new IntegrationWorkloadIdentity(
                "authoring-client", "tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "author", "", Set.of("API_RESOURCE_AUTHORING"), Instant.MAX, true,
                Set.of("authors"), "INTERNAL", "", Instant.MAX);
        IntegrationRequestAuthenticator authenticator = new IntegrationRequestAuthenticator(
                new StaticBearerIntegrationIdentityResolver("author-token", identity, false), new RecordingAudit());
        return MockMvcBuilders.standaloneSetup(new ComposableCatalogController(catalog, authenticator)).build();
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }
}
